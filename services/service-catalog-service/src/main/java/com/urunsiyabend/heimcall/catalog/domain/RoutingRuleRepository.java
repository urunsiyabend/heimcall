package com.urunsiyabend.heimcall.catalog.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {

    List<RoutingRule> findByOrganizationIdOrderByPositionAsc(UUID organizationId);

    Optional<RoutingRule> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    void deleteByIdAndOrganizationId(UUID id, UUID organizationId);
}
