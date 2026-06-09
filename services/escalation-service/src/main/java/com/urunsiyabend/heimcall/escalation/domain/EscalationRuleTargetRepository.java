package com.urunsiyabend.heimcall.escalation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EscalationRuleTargetRepository extends JpaRepository<EscalationRuleTarget, UUID> {
    List<EscalationRuleTarget> findByRuleId(UUID ruleId);
}
