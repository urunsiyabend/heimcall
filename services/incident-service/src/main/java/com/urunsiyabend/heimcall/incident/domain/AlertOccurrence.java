package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.MessageType;
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
 * The immutable per-signal log under an {@link Alert}: one row per inbound
 * {@link com.urunsiyabend.heimcall.common.events.AlertReceivedEvent} that hit the alert, capturing that
 * signal's facts. {@code eventId} links back to integration-service's raw inbound event for replay.
 */
@Entity
@Table(name = "alert_occurrence")
public class AlertOccurrence {

    @Id
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "title")
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AlertOccurrence() {
    }

    public static AlertOccurrence of(UUID alertId, UUID eventId, MessageType messageType, Severity severity,
                                     String title, String description, Instant occurredAt, Instant receivedAt) {
        AlertOccurrence occurrence = new AlertOccurrence();
        occurrence.id = UUID.randomUUID();
        occurrence.alertId = alertId;
        occurrence.eventId = eventId;
        occurrence.messageType = messageType;
        occurrence.severity = severity != null ? severity : Severity.WARNING;
        occurrence.title = title;
        occurrence.description = description;
        occurrence.occurredAt = occurredAt;
        occurrence.receivedAt = receivedAt;
        return occurrence;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlertId() {
        return alertId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
