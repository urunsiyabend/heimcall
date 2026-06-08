package com.urunsiyabend.heimcall.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "schedule_rotation")
public class ScheduleRotation {

    @Id
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RotationType type;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "handoff_time", nullable = false)
    private LocalTime handoffTime;

    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScheduleRotation() {
    }

    public static ScheduleRotation create(UUID scheduleId, String name, RotationType type,
                                          LocalDate startDate, LocalTime handoffTime, int priority, Instant at) {
        ScheduleRotation r = new ScheduleRotation();
        r.id = UUID.randomUUID();
        r.scheduleId = scheduleId;
        r.name = name;
        r.type = type;
        r.startDate = startDate;
        r.handoffTime = handoffTime;
        r.priority = priority;
        r.createdAt = at;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public String getName() {
        return name;
    }

    public RotationType getType() {
        return type;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalTime getHandoffTime() {
        return handoffTime;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
