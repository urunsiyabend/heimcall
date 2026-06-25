package com.urunsiyabend.heimcall.incident.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RoutingRulesetProjectionRepository extends JpaRepository<RoutingRulesetProjection, UUID> {

    /** Projections not refreshed since the cutoff — reconciliation re-pull candidates. */
    List<RoutingRulesetProjection> findByObservedAtBefore(Instant cutoff);

    long countByState(ProjectionState state);
}
