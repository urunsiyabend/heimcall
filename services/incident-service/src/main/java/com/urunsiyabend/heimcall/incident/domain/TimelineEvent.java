package com.urunsiyabend.heimcall.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a meaningful action on an incident.
 */
@Entity
@Table(name = "incident_timeline_event")
public class TimelineEvent {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TimelineEvent() {
    }

    public static TimelineEvent of(UUID incidentId, String type, String message, Instant at) {
        TimelineEvent event = new TimelineEvent();
        event.id = UUID.randomUUID();
        event.incidentId = incidentId;
        event.type = type;
        event.message = message;
        event.createdAt = at;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
