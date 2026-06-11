package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.NotificationDeliveredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationFailedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.notification.domain.DeliveryStatus;
import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import com.urunsiyabend.heimcall.notification.sender.NotificationSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fires one delivery in its own transaction: dispatch through the channel's sender, then either mark
 * DELIVERED (and publish {@code notification.delivered.v1}) or, on failure, retry with backoff up to
 * the configured max attempts before marking FAILED (and publishing {@code notification.failed.v1}).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRequestRepository requests;
    private final KafkaTemplate<String, Object> eventsKafkaTemplate;
    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final int maxAttempts;
    private final long retryBackoffMs;
    // Phase 8 T2: notification_delivery_success_total / notification_delivery_failure_total (terminal only).
    private final Counter deliverySuccess;
    private final Counter deliveryFailure;

    public DeliveryService(NotificationDeliveryRepository deliveries, NotificationRequestRepository requests,
                           KafkaTemplate<String, Object> eventsKafkaTemplate, List<NotificationSender> senderList,
                           MeterRegistry registry,
                           @Value("${notification.delivery.max-attempts:3}") int maxAttempts,
                           @Value("${notification.delivery.retry-backoff-ms:10000}") long retryBackoffMs) {
        this.deliveries = deliveries;
        this.requests = requests;
        this.eventsKafkaTemplate = eventsKafkaTemplate;
        senderList.forEach(s -> senders.put(s.channel(), s));
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
        this.deliverySuccess = registry.counter("notification.delivery.success");
        this.deliveryFailure = registry.counter("notification.delivery.failure");
    }

    @Transactional
    public void fireDelivery(UUID deliveryId) {
        NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.PENDING) {
            return;
        }
        NotificationRequest request = requests.findById(delivery.getRequestEventId()).orElse(null);
        if (request == null) {
            log.warn("Delivery {} has no request {}; marking failed", deliveryId, delivery.getRequestEventId());
            failAndPublish(delivery, "notification request not found", Instant.now());
            return;
        }

        NotificationSender sender = senders.get(delivery.getChannel());
        Instant now = Instant.now();
        if (sender == null) {
            // Unknown channel cannot succeed; do not waste retries on it.
            failAndPublish(delivery, "no sender for channel " + delivery.getChannel(), now);
            return;
        }

        try {
            sender.send(delivery, request);
            delivery.markDelivered(now);
            deliveries.save(delivery);
            deliverySuccess.increment();
            eventsKafkaTemplate.send(Topics.NOTIFICATION_DELIVERED, delivery.getIncidentId().toString(),
                    new NotificationDeliveredEvent(UUID.randomUUID(), now, delivery.getOrganizationId(),
                            delivery.getIncidentId(), delivery.getRecipientUserId(), delivery.getChannel().name(),
                            delivery.getDestination(), delivery.getRequestEventId()));
            log.info("Delivered notification {} via {} to {} (incident {})",
                    deliveryId, delivery.getChannel(), delivery.getDestination(), delivery.getIncidentId());
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            int attemptJustMade = delivery.getAttempts() + 1;
            if (attemptJustMade >= maxAttempts) {
                failAndPublish(delivery, reason, now);
                log.warn("Delivery {} failed permanently after {} attempt(s) via {}: {}",
                        deliveryId, attemptJustMade, delivery.getChannel(), reason);
            } else {
                Instant next = now.plusMillis((long) attemptJustMade * retryBackoffMs);
                delivery.markRetry(reason, next, now);
                deliveries.save(delivery);
                log.warn("Delivery {} attempt {} failed via {}, retrying at {}: {}",
                        deliveryId, attemptJustMade, delivery.getChannel(), next, reason);
            }
        }
    }

    private void failAndPublish(NotificationDelivery delivery, String reason, Instant now) {
        delivery.markFailed(reason, now);
        deliveries.save(delivery);
        deliveryFailure.increment();
        eventsKafkaTemplate.send(Topics.NOTIFICATION_FAILED, delivery.getIncidentId().toString(),
                new NotificationFailedEvent(UUID.randomUUID(), now, delivery.getOrganizationId(),
                        delivery.getIncidentId(), delivery.getRecipientUserId(), delivery.getChannel().name(),
                        delivery.getDestination(), delivery.getAttempts(), reason, delivery.getRequestEventId()));
    }
}
