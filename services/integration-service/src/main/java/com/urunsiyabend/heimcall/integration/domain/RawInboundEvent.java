package com.urunsiyabend.heimcall.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of a raw inbound webhook payload, written before the Kafka publish.
 * Acts as a lightweight outbox/audit log: a publish failure leaves a FAILED row that
 * carries the original payload for inspection and replay.
 */
@Entity
@Table(name = "raw_inbound_event")
public class RawInboundEvent {

    public enum Status { RECEIVED, PUBLISHED, FAILED }

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "integration_key", nullable = false)
    private String integrationKey;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column
    private String source;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected RawInboundEvent() {
    }

    public static RawInboundEvent received(UUID eventId, String integrationKey, String routingKey,
                                           String source, String dedupKey, String payload, Instant receivedAt) {
        RawInboundEvent raw = new RawInboundEvent();
        raw.id = UUID.randomUUID();
        raw.eventId = eventId;
        raw.integrationKey = integrationKey;
        raw.routingKey = routingKey;
        raw.source = source;
        raw.dedupKey = dedupKey;
        raw.payload = payload;
        raw.status = Status.RECEIVED;
        raw.receivedAt = receivedAt;
        return raw;
    }

    public void markPublished(Instant at) {
        this.status = Status.PUBLISHED;
        this.publishedAt = at;
        this.error = null;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Status getStatus() {
        return status;
    }
}
