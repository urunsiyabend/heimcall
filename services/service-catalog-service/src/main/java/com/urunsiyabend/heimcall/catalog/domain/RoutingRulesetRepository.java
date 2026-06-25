package com.urunsiyabend.heimcall.catalog.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RoutingRulesetRepository extends JpaRepository<RoutingRuleset, UUID> {
}
