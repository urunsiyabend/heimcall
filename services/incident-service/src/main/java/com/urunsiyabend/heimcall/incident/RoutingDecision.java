package com.urunsiyabend.heimcall.incident;

import java.util.UUID;

/**
 * Outcome of resolving an alert routingKey through {@link RoutingAvailabilityResolver} (Phase 10 T4).
 * Total over the three non-throwing cases; an outage with no cached fallback throws
 * {@link RoutingUnavailableException} instead (retry/DLT).
 *
 * <ul>
 *   <li><b>ROUTED</b> (catalog 200): {@code policyId} non-null, {@code unrouted=false}, {@code fromCache=false}.</li>
 *   <li><b>UNROUTED</b> (catalog 404): {@code policyId=null}, {@code unrouted=true} — the deliberate
 *       "nobody paged" outcome from Phase 10 T3.</li>
 *   <li><b>ROUTED_FROM_CACHE</b> (catalog outage + cache hit): {@code policyId} from the last-known-good
 *       cache, {@code fromCache=true} — escalation pages on the cached policy instead of dead-lettering.</li>
 * </ul>
 */
record RoutingDecision(UUID serviceId, UUID policyId, UUID ownerTeamId, boolean unrouted, boolean fromCache) {

    static RoutingDecision routed(CatalogClient.Routing r) {
        return new RoutingDecision(r.serviceId(), r.escalationPolicyId(), r.ownerTeamId(), false, false);
    }

    static RoutingDecision noMatch() {
        return new RoutingDecision(null, null, null, true, false);
    }

    static RoutingDecision fromCache(CatalogClient.Routing r) {
        return new RoutingDecision(r.serviceId(), r.escalationPolicyId(), r.ownerTeamId(), false, true);
    }
}
