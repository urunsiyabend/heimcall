package com.urunsiyabend.heimcall.escalation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationRuleRepository extends JpaRepository<EscalationRule, UUID> {
    List<EscalationRule> findByPolicyIdOrderByLevelAsc(UUID policyId);
    Optional<EscalationRule> findByIdAndPolicyId(UUID id, UUID policyId);
    boolean existsByPolicyIdAndLevel(UUID policyId, int level);
}
