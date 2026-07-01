package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.NotificationDeliveredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationFailedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.notification.domain.DeliveryStatus;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 20 T1: the transactional half of the two-phase delivery worker. Lives in its own bean so the
 * orchestration in {@link DeliveryService} (which performs the un-transactional SMTP/webhook send between
 * the claim and the result) reaches these through the Spring proxy — a self-invocation would skip the
 * {@code @Transactional} boundary.
 *
 * <p>Two phases:
 * <ol>
 *   <li>{@link #claimDue} flips due rows PENDING (or expired-lease SENDING) → SENDING + a fresh lease and
 *       commits, releasing the row lock so the send can run lock-free and concurrently.</li>
 *   <li>{@code finalize*} re-locks the row by id and applies the send result <b>only if the fencing
 *       {@code lease_token} still matches</b>. If a reaper handed the row to another worker (lease expired
 *       while this sender stalled), the token differs → the result is abandoned (returns {@code false}),
 *       so a zombie worker can never overwrite the new owner's row.</li>
 * </ol>
 * at-least-once holds: a crash anywhere after the claim leaves the row SENDING with an expiring lease, so
 * it is re-claimed and retried — never lost. The cost is a possible duplicate send (accepted under
 * at-least-once; bounded by the Phase 14 cooldown + a best-effort delivery-id header).
 */
@Component
public class DeliveryTx {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTx.class);

    private final NotificationDeliveryRepository deliveries;
    private final OutboxAppender outbox;

    public DeliveryTx(NotificationDeliveryRepository deliveries, OutboxAppender outbox) {
        this.deliveries = deliveries;
        this.outbox = outbox;
    }

    /** Phase 1: claim up to {@code limit} due rows, flipping each to SENDING with a fresh lease. */
    @Transactional
    public List<NotificationDelivery> claimDue(Instant now, int limit, Duration lease) {
        List<NotificationDelivery> rows = deliveries.claimDue(now, limit);
        Instant expiry = now.plus(lease);
        for (NotificationDelivery row : rows) {
            row.claim(UUID.randomUUID(), expiry, now);
        }
        deliveries.saveAll(rows);
        return rows;
    }

    /** Phase 2 success: mark DELIVERED + publish {@code notification.delivered.v1}. */
    @Transactional
    public boolean finalizeDelivered(UUID id, UUID leaseToken, Instant now) {
        NotificationDelivery row = lockOwned(id, leaseToken);
        if (row == null) {
            return false;
        }
        row.markDelivered(now);
        deliveries.save(row);
        outbox.append("notification", row.getIncidentId().toString(), Topics.NOTIFICATION_DELIVERED,
                row.getIncidentId().toString(),
                new NotificationDeliveredEvent(UUID.randomUUID(), now, row.getOrganizationId(),
                        row.getIncidentId(), row.getRecipientUserId(), row.getChannel().name(),
                        row.getDestination(), row.getRequestEventId()));
        return true;
    }

    /** Phase 2 transient failure: back to PENDING with backoff (lease released). */
    @Transactional
    public boolean finalizeRetry(UUID id, UUID leaseToken, String reason, Instant nextAttemptAt, Instant now) {
        NotificationDelivery row = lockOwned(id, leaseToken);
        if (row == null) {
            return false;
        }
        row.markRetry(reason, nextAttemptAt, now);
        deliveries.save(row);
        return true;
    }

    /** Phase 2 terminal failure: mark FAILED + publish {@code notification.failed.v1}. */
    @Transactional
    public boolean finalizeFailure(UUID id, UUID leaseToken, String reason, Instant now) {
        NotificationDelivery row = lockOwned(id, leaseToken);
        if (row == null) {
            return false;
        }
        row.markFailed(reason, now);
        deliveries.save(row);
        outbox.append("notification", row.getIncidentId().toString(), Topics.NOTIFICATION_FAILED,
                row.getIncidentId().toString(),
                new NotificationFailedEvent(UUID.randomUUID(), now, row.getOrganizationId(),
                        row.getIncidentId(), row.getRecipientUserId(), row.getChannel().name(),
                        row.getDestination(), row.getAttempts(), reason, row.getRequestEventId()));
        return true;
    }

    /**
     * Lock the row by id and confirm this worker still owns the lease. Returns {@code null} (logged) if the
     * row vanished, is no longer SENDING, or carries a different {@code lease_token} — the fencing guard.
     */
    private NotificationDelivery lockOwned(UUID id, UUID leaseToken) {
        NotificationDelivery row = deliveries.findByIdForUpdate(id).orElse(null);
        if (row == null || row.getStatus() != DeliveryStatus.SENDING || !leaseToken.equals(row.getLeaseToken())) {
            log.warn("Delivery {} lease no longer owned ({}); abandoning send result",
                    id, row == null ? "row absent" : "status=" + row.getStatus());
            return null;
        }
        return row;
    }
}
