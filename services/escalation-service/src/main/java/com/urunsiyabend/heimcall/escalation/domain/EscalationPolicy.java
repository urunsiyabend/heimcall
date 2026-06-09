package com.urunsiyabend.heimcall.escalation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An escalation policy: a named, ordered set of levels for an organization. {@code repeatCount}
 * repeats the whole policy when it is exhausted and the incident is still open (0 = no repeat).
 */
@Entity
@Table(name = "escalation_policy")
public class EscalationPolicy {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "repeat_count", nullable = false)
    private int repeatCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EscalationPolicy() {
    }

    public static EscalationPolicy create(UUID organizationId, String name, int repeatCount, Instant at) {
        EscalationPolicy p = new EscalationPolicy();
        p.id = UUID.randomUUID();
        p.organizationId = organizationId;
        p.name = name;
        p.repeatCount = repeatCount;
        p.createdAt = at;
        p.updatedAt = at;
        return p;
    }

    public void update(String name, int repeatCount, Instant at) {
        this.name = name;
        this.repeatCount = repeatCount;
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
