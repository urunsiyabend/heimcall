package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import com.urunsiyabend.heimcall.notification.sender.NotificationSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 20 T1: the un-transactional half of the two-phase delivery worker. {@link #claim} delegates the
 * SENDING+lease flip to {@link DeliveryTx} (a committed transaction), then {@link #sendClaimed} performs
 * the actual SMTP/webhook send <b>outside</b> any transaction (no row lock, no DB connection held) and
 * records the result back through {@code DeliveryTx}. Because the send is lock-free, a pool of
 * {@link DeliveryDispatcher} virtual threads can run many sends concurrently — the throughput fix (the old
 * single-thread send-in-tx worker capped at ~87/s).
 *
 * <p>On success → DELIVERED + {@code notification.delivered.v1}; on failure → linear backoff retry
 * ({@code attempts × backoff}) up to {@code max-attempts}, then FAILED + {@code notification.failed.v1}.
 * Metrics increment only when {@code DeliveryTx} confirms this worker still owned the lease (fencing).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryTx tx;
    private final NotificationRequestRepository requests;
    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final int maxAttempts;
    private final long retryBackoffMs;
    private final Duration lease;
    // Phase 8 T2: notification_delivery_success_total / notification_delivery_failure_total (terminal only).
    private final Counter deliverySuccess;
    private final Counter deliveryFailure;
    // Phase 19 T4: notification stage latency (request received -> delivered). Histogram for p50/90/99.
    private final Timer deliveryLatency;
    // Phase 19 T5: true end-to-end latency (originating alert occurredAt -> delivered). Histogram.
    private final Timer e2eLatency;

    public DeliveryService(DeliveryTx tx, NotificationRequestRepository requests,
                           List<NotificationSender> senderList, MeterRegistry registry,
                           @Value("${notification.delivery.max-attempts:3}") int maxAttempts,
                           @Value("${notification.delivery.retry-backoff-ms:10000}") long retryBackoffMs,
                           @Value("${notification.delivery.lease-ms:60000}") long leaseMs) {
        this.tx = tx;
        this.requests = requests;
        senderList.forEach(s -> senders.put(s.channel(), s));
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
        this.lease = Duration.ofMillis(leaseMs);
        this.deliverySuccess = registry.counter("notification.delivery.success");
        this.deliveryFailure = registry.counter("notification.delivery.failure");
        // Phase 20 T3: Micrometer's publishPercentileHistogram() defaults to a 1ms..30s bucket range, so
        // under load (measured e2e avg ~80s, 2026-06-26) p95/p99 pinned at the 30s ceiling and the real tail
        // was invisible. Extend maximumExpectedValue past the latencies the system actually hits and add
        // explicit SLO boundaries so tail quantiles read true and alerting has named thresholds.
        this.deliveryLatency = Timer.builder("notification.delivery.latency")
                .description("notification stage latency: request received -> delivered")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofMinutes(2))
                .serviceLevelObjectives(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120))
                .register(registry);
        this.e2eLatency = Timer.builder("notification.e2e.latency")
                .description("end-to-end latency: originating alert occurredAt -> delivered")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .serviceLevelObjectives(
                        Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                        Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300))
                .register(registry);
    }

    /** Phase 1: claim up to {@code limit} due rows (each returned SENDING with its lease stamped). */
    public List<NotificationDelivery> claim(int limit) {
        return tx.claimDue(Instant.now(), limit, lease);
    }

    /**
     * Phase 2: send one already-claimed delivery (status SENDING, lease held) outside any transaction, then
     * record the result through {@link DeliveryTx} under the fencing-token guard. Safe to call concurrently
     * for distinct claimed rows.
     */
    public void sendClaimed(NotificationDelivery claimed) {
        UUID id = claimed.getId();
        UUID token = claimed.getLeaseToken();

        NotificationRequest request = requests.findById(claimed.getRequestEventId()).orElse(null);
        if (request == null) {
            log.warn("Delivery {} has no request {}; marking failed", id, claimed.getRequestEventId());
            failTerminal(id, token, "notification request not found");
            return;
        }

        NotificationSender sender = senders.get(claimed.getChannel());
        if (sender == null) {
            // Unknown channel cannot succeed; do not waste retries on it.
            failTerminal(id, token, "no sender for channel " + claimed.getChannel());
            return;
        }

        try {
            sender.send(claimed, request);
            Instant now = Instant.now();
            if (tx.finalizeDelivered(id, token, now)) {
                deliverySuccess.increment();
                if (request.getReceivedAt() != null) {
                    deliveryLatency.record(Duration.between(request.getReceivedAt(), now));
                }
                if (request.getAlertOccurredAt() != null) {
                    e2eLatency.record(Duration.between(request.getAlertOccurredAt(), now));
                }
                log.info("Delivered notification {} via {} to {} (incident {})",
                        id, claimed.getChannel(), claimed.getDestination(), claimed.getIncidentId());
            }
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Instant now = Instant.now();
            int attemptJustMade = claimed.getAttempts() + 1;
            if (attemptJustMade >= maxAttempts) {
                if (tx.finalizeFailure(id, token, reason, now)) {
                    deliveryFailure.increment();
                }
                log.warn("Delivery {} failed permanently after {} attempt(s) via {}: {}",
                        id, attemptJustMade, claimed.getChannel(), reason);
            } else {
                Instant next = now.plusMillis((long) attemptJustMade * retryBackoffMs);
                tx.finalizeRetry(id, token, reason, next, now);
                log.warn("Delivery {} attempt {} failed via {}, retrying at {}: {}",
                        id, attemptJustMade, claimed.getChannel(), next, reason);
            }
        }
    }

    private void failTerminal(UUID id, UUID token, String reason) {
        if (tx.finalizeFailure(id, token, reason, Instant.now())) {
            deliveryFailure.increment();
        }
    }
}
