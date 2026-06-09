package com.urunsiyabend.heimcall.escalation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {
    List<EscalationPolicy> findByOrganizationId(UUID organizationId);
    Optional<EscalationPolicy> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
