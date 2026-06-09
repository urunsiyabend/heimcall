package com.urunsiyabend.heimcall.escalation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A notification target of a rule: a user, an on-call schedule, or a team. */
@Entity
@Table(name = "escalation_rule_target")
public class EscalationRuleTarget {

    @Id
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    protected EscalationRuleTarget() {
    }

    public static EscalationRuleTarget create(UUID ruleId, TargetType targetType, UUID targetId) {
        EscalationRuleTarget t = new EscalationRuleTarget();
        t.id = UUID.randomUUID();
        t.ruleId = ruleId;
        t.targetType = targetType;
        t.targetId = targetId;
        return t;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }
}
