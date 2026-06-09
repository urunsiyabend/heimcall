package com.urunsiyabend.heimcall.escalation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled execution of one policy level for one incident. The worker fires PENDING tasks whose
 * {@code scheduledAt} has passed; an ACK/RESOLVE cancels still-PENDING tasks for the incident.
 */
@Entity
@Table(name = "escalation_task")
public class EscalationTask {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(nullable = false)
    private int level;

    @Column(name = "repeat_index", nullable = false)
    private int repeatIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EscalationTask() {
    }

    public static EscalationTask pending(UUID organizationId, UUID incidentId, UUID policyId, UUID ruleId,
                                         int level, int repeatIndex, Instant scheduledAt, Instant createdAt) {
        EscalationTask t = new EscalationTask();
        t.id = UUID.randomUUID();
        t.organizationId = organizationId;
        t.incidentId = incidentId;
        t.policyId = policyId;
        t.ruleId = ruleId;
        t.level = level;
        t.repeatIndex = repeatIndex;
        t.status = TaskStatus.PENDING;
        t.scheduledAt = scheduledAt;
        t.createdAt = createdAt;
        return t;
    }

    public void markExecuted(Instant at) {
        this.status = TaskStatus.EXECUTED;
        this.executedAt = at;
    }

    public void cancel() {
        this.status = TaskStatus.CANCELED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public int getLevel() {
        return level;
    }

    public int getRepeatIndex() {
        return repeatIndex;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
