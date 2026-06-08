package com.urunsiyabend.heimcall.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "on_call_schedule")
public class OnCallSchedule {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OnCallSchedule() {
    }

    public static OnCallSchedule create(UUID organizationId, String name, String timezone, Instant at) {
        OnCallSchedule s = new OnCallSchedule();
        s.id = UUID.randomUUID();
        s.organizationId = organizationId;
        s.name = name;
        s.timezone = timezone;
        s.createdAt = at;
        s.updatedAt = at;
        return s;
    }

    public void update(String name, String timezone, Instant at) {
        this.name = name;
        this.timezone = timezone;
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

    public String getTimezone() {
        return timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
