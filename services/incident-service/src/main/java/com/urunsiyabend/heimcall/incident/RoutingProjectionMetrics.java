package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.domain.ProjectionState;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Exposes routing projection read-model freshness so ops is not blind (Phase 17 T2). The distinct
 * projection states must NOT collapse into one "UNROUTED" number, so each is its own gauge. Combined
 * with the {@code routing.projection.apply_lag} timer (event lag) and the per-decision
 * {@code ruleset_version} stamped on each incident, this answers "is local routing current?".
 */
@Component
public class RoutingProjectionMetrics {

    private final MeterRegistry registry;
    private final RoutingRulesetProjectionRepository projections;

    public RoutingProjectionMetrics(MeterRegistry registry, RoutingRulesetProjectionRepository projections) {
        this.registry = registry;
        this.projections = projections;
    }

    @PostConstruct
    void bind() {
        registry.gauge("routing.projection.ready", projections,
                r -> r.countByState(ProjectionState.READY));
        registry.gauge("routing.projection.absent_confirmed", projections,
                r -> r.countByState(ProjectionState.ABSENT_CONFIRMED));
        registry.gauge("routing.projection.total", projections,
                org.springframework.data.repository.CrudRepository::count);
    }
}
