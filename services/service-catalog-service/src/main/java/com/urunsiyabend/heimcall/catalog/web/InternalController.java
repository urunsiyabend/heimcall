package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.RoutingRuleService;
import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.routing.RoutingContext;
import com.urunsiyabend.heimcall.routing.RoutingDecision;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service routing resolution (Phase 17). incident-service POSTs the full
 * {@link RoutingContext} (built from the normalized {@code AlertReceivedEvent}) and the routing rule
 * engine returns exactly one {@link RoutingDecision}. The ruleset is total — a decision is always
 * produced (a matched rule, the fallback, or UNROUTED) — so this never 404s for a routing reason; only
 * infra failure (catalog/escalation down) yields a 5xx that incident-service treats as "unavailable".
 *
 * <p>Replaces the Phase 10 {@code GET .../routing?routingKey=} single-key lookup: routing now depends
 * on severity, source, metadata and time-of-day, not a key alone. Still service-token-gated
 * (Phase 16 T3, {@code catalog.routing.resolve} scope); not for external callers.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final RoutingRuleService routing;

    public InternalController(RoutingRuleService routing) {
        this.routing = routing;
    }

    @PostMapping("/organizations/{orgId}/routing/resolve")
    @PreAuthorize("hasAuthority('SCOPE_catalog.routing.resolve')")
    public RoutingDecision resolve(@PathVariable UUID orgId, @RequestBody RoutingContext context) {
        return routing.resolve(orgId, context);
    }

    /**
     * Full current ruleset snapshot for an org (Phase 17 T2). incident-service pulls this to lazily
     * hydrate or reconcile its local read-model when the snapshot stream has not (yet) populated it.
     * Always returns a snapshot (the ruleset is total; an org with no rules yields version 0 + an empty,
     * UNROUTED-fallback ruleset). Same service-token scope as resolve.
     */
    @GetMapping("/organizations/{orgId}/routing/ruleset")
    @PreAuthorize("hasAuthority('SCOPE_catalog.routing.resolve')")
    public RoutingRulesetSnapshotEvent ruleset(@PathVariable UUID orgId) {
        return routing.snapshot(orgId);
    }
}
