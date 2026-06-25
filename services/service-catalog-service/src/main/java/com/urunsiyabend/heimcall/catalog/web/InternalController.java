package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.RoutingRuleService;
import com.urunsiyabend.heimcall.catalog.routing.RoutingContext;
import com.urunsiyabend.heimcall.catalog.routing.RoutingDecision;
import org.springframework.security.access.prepost.PreAuthorize;
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
}
