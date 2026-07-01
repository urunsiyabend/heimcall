package com.urunsiyabend.heimcall.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt-track for a (request, contact method) pair. The worker fires PENDING rows whose
 * {@code nextAttemptAt} has passed; success -> DELIVERED, failure -> retry with backoff until
 * {@code attempts} hits the max, then FAILED. Delivery state is tracked separately from incident state.
 */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    @Id
    private UUID id;

    @Column(name = "request_event_id", nullable = false)
    private UUID requestEventId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "contact_method_id", nullable = false)
    private UUID contactMethodId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    // Phase 20 T1: two-phase claim lease. Set when claimed (status SENDING), cleared on any terminal/retry
    // transition. lease_token is a fencing token — the result write only applies while it still matches.
    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationDelivery() {
    }

    public static NotificationDelivery pending(UUID requestEventId, UUID organizationId, UUID incidentId,
                                               UUID recipientUserId, UUID contactMethodId,
                                               NotificationChannel channel, String destination, Instant now) {
        NotificationDelivery d = new NotificationDelivery();
        d.id = UUID.randomUUID();
        d.requestEventId = requestEventId;
        d.organizationId = organizationId;
        d.incidentId = incidentId;
        d.recipientUserId = recipientUserId;
        d.contactMethodId = contactMethodId;
        d.channel = channel;
        d.destination = destination;
        d.status = DeliveryStatus.PENDING;
        d.attempts = 0;
        d.nextAttemptAt = now;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    /**
     * Phase 20 T1: claim this row for sending — flip PENDING (or an expired-lease SENDING) to SENDING and
     * stamp the lease. Does NOT touch {@code attempts}; the attempt is counted only when the send result
     * is recorded. After the claim tx commits the row lock is released and the send runs outside it.
     */
    public void claim(UUID leaseToken, Instant leaseExpiresAt, Instant now) {
        this.status = DeliveryStatus.SENDING;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = now;
    }

    public void markDelivered(Instant now) {
        this.status = DeliveryStatus.DELIVERED;
        this.attempts++;
        this.lastError = null;
        this.lastAttemptAt = now;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    /** Record a failed attempt and schedule the next one — back to PENDING, lease released. */
    public void markRetry(String error, Instant nextAttemptAt, Instant now) {
        this.status = DeliveryStatus.PENDING;
        this.attempts++;
        this.lastError = error;
        this.lastAttemptAt = now;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    public void markFailed(String error, Instant now) {
        this.status = DeliveryStatus.FAILED;
        this.attempts++;
        this.lastError = error;
        this.lastAttemptAt = now;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestEventId() {
        return requestEventId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public UUID getContactMethodId() {
        return contactMethodId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getDestination() {
        return destination;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
