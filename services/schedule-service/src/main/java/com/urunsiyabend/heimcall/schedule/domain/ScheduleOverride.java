package com.urunsiyabend.heimcall.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Pins a user on-call for [startAt, endAt), overriding any rotation. */
@Entity
@Table(name = "schedule_override")
public class ScheduleOverride {

    @Id
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScheduleOverride() {
    }

    public static ScheduleOverride create(UUID scheduleId, UUID userId, Instant startAt, Instant endAt, Instant at) {
        ScheduleOverride o = new ScheduleOverride();
        o.id = UUID.randomUUID();
        o.scheduleId = scheduleId;
        o.userId = userId;
        o.startAt = startAt;
        o.endAt = endAt;
        o.createdAt = at;
        return o;
    }

    public boolean coversInstant(Instant t) {
        return !t.isBefore(startAt) && t.isBefore(endAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
