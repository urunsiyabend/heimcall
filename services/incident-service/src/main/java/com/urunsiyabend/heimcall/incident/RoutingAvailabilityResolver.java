package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.incident.RoutingProjectionStore.Loaded;
import com.urunsiyabend.heimcall.incident.domain.ProjectionState;
import com.urunsiyabend.heimcall.routing.RoutingContext;
import com.urunsiyabend.heimcall.routing.Ruleset;
import com.urunsiyabend.heimcall.routing.TreeRoutingEvaluator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves routing by evaluating the org's <b>locally replicated</b> ruleset (Phase 17 T2) — catalog is
 * off the hot path entirely, so a catalog outage no longer affects routing (it only delays the next
 * ruleset version). Generalizes the Phase 10 T4 cache: a durable, versioned, reconciled read-model of
 * the ruleset that produces the route, not "last route per key".
 *
 * <p>Cold miss (no projection yet — new tenant / empty system / DB restore): a one-time synchronous pull
 * from catalog hydrates the projection, then serves. If catalog is also down at that instant, fall to
 * UNROUTED (visible + counted, never a misroute). Staleness never causes a fallback or drop: routing
 * keeps using the last-known ruleset (config is slow-changing) and a metric/alert flags it instead.
 */
@Component
public class RoutingAvailabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingAvailabilityResolver.class);

    private final RoutingProjectionStore projections;
    private final CatalogClient catalog;
    private final MeterRegistry registry;
    private final TreeRoutingEvaluator evaluator = new TreeRoutingEvaluator();

    public RoutingAvailabilityResolver(RoutingProjectionStore projections, CatalogClient catalog,
                                       MeterRegistry registry) {
        this.projections = projections;
        this.catalog = catalog;
        this.registry = registry;
    }

    public RoutingDecision resolve(AlertReceivedEvent event) {
        Instant now = Instant.now();
        Optional<Loaded> loaded = projections.load(event.organizationId(), now);

        Ruleset ruleset;
        if (loaded.isEmpty()) {
            ruleset = hydrateOnColdMiss(event.organizationId(), now);
            if (ruleset == null) {
                // Cold projection AND catalog unavailable: deliberate UNROUTED, never a guessed misroute.
                count("routing.projection.cold_miss_unrouted");
                log.warn("Routing projection cold for org={} and catalog unavailable; UNROUTED",
                        event.organizationId());
                return RoutingDecision.unroutedFallback();
            }
        } else {
            Loaded l = loaded.get();
            if (l.state() == ProjectionState.STALE) {
                // Keep routing on the last-known ruleset (almost certainly still correct); flag it.
                count("routing.projection.stale_use");
                log.warn("Routing projection STALE for org={} (version={}, observedAt={}); routing on last-known",
                        event.organizationId(), l.version(), l.observedAt());
            }
            ruleset = l.ruleset();
        }

        com.urunsiyabend.heimcall.routing.RoutingDecision decision =
                evaluator.evaluate(context(event), ruleset, false);
        return RoutingDecision.fromCore(decision);
    }

    /** Synchronous one-time pull to populate a cold projection; null if catalog is also down. */
    private Ruleset hydrateOnColdMiss(java.util.UUID orgId, Instant now) {
        try {
            RoutingRulesetSnapshotEvent snapshot = catalog.fetchRuleset(orgId);
            projections.apply(snapshot, now);
            count("routing.projection.hydrated");
            log.info("Lazily hydrated routing projection org={} version={}", orgId, snapshot.rulesetVersion());
            return snapshot.ruleset();
        } catch (RoutingUnavailableException unavailable) {
            return null;
        }
    }

    private static RoutingContext context(AlertReceivedEvent event) {
        return new RoutingContext(
                event.routingKey(), event.source(),
                event.messageType() == null ? null : event.messageType().name(),
                event.severity() == null ? null : event.severity().name(),
                event.externalEntityId(), event.title(), event.description(),
                event.metadata(), event.occurredAt());
    }

    private void count(String name) {
        registry.counter(name).increment();
    }
}
