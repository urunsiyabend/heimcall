package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import org.springframework.stereotype.Component;

/**
 * Resolves routing for an inbound alert (Phase 17 T1). Thin wrapper over {@link CatalogClient}: the
 * ruleset is total, so a successful resolve is either ROUTED or a deliberate UNROUTED — both are real
 * decisions. A catalog OUTAGE is neither and propagates as {@link RoutingUnavailableException} to
 * retry/DLT.
 *
 * <p>The Phase 10 T4 last-known-good cache fallback is deliberately <b>not</b> used here: once routing
 * depends on more than the routingKey, a key-only cache could misroute a different-field event, which is
 * exactly the silent paging black hole Phase 10 set out to kill. T1 accepts a documented availability
 * regression (outage -> DLT, delayed-but-correct); T2 restores outage tolerance correctly by evaluating
 * a locally-replicated ruleset.
 */
@Component
public class RoutingAvailabilityResolver {

    private final CatalogClient catalog;

    public RoutingAvailabilityResolver(CatalogClient catalog) {
        this.catalog = catalog;
    }

    /**
     * @throws RoutingUnavailableException catalog is unavailable — the genuine unknown; let it propagate
     *                                     to retry/DLT (no orphan, no misroute).
     */
    public RoutingDecision resolve(AlertReceivedEvent event) {
        return RoutingDecision.of(catalog.resolve(event.organizationId(), event));
    }
}
