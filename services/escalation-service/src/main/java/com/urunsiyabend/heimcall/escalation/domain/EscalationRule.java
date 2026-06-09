package com.urunsiyabend.heimcall.escalation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A level within a policy. {@code delaySeconds} is the wait, measured from the incident trigger,
 * before this level fires. Level 1 usually has delay 0 (notify immediately).
 */
@Entity
@Table(name = "escalation_rule")
public class EscalationRule {

    @Id
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(nullable = false)
    private int level;

    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EscalationRule() {
    }

    public static EscalationRule create(UUID policyId, int level, int delaySeconds, Instant at) {
        EscalationRule r = new EscalationRule();
        r.id = UUID.randomUUID();
        r.policyId = policyId;
        r.level = level;
        r.delaySeconds = delaySeconds;
        r.createdAt = at;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public int getLevel() {
        return level;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
