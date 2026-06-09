package com.urunsiyabend.heimcall.notification.domain;

import com.urunsiyabend.heimcall.common.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A consumed {@code notification.requested.v1} event. The {@code eventId} is the primary key, so this
 * table doubles as the idempotency ledger: a redelivered request whose id already exists is a no-op.
 */
@Entity
@Table(name = "notification_request")
public class NotificationRequest {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(nullable = false)
    private int level;

    @Column(name = "target_source")
    private String targetSource;

    @Column
    private String title;

    @Enumerated(EnumType.STRING)
    @Column
    private Severity severity;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected NotificationRequest() {
    }

    public static NotificationRequest of(UUID eventId, UUID organizationId, UUID incidentId,
                                         UUID recipientUserId, int level, String targetSource,
                                         String title, Severity severity, Instant receivedAt) {
        NotificationRequest r = new NotificationRequest();
        r.eventId = eventId;
        r.organizationId = organizationId;
        r.incidentId = incidentId;
        r.recipientUserId = recipientUserId;
        r.level = level;
        r.targetSource = targetSource;
        r.title = title;
        r.severity = severity;
        r.receivedAt = receivedAt;
        return r;
    }

    public UUID getEventId() {
        return eventId;
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

    public int getLevel() {
        return level;
    }

    public String getTargetSource() {
        return targetSource;
    }

    public String getTitle() {
        return title;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
