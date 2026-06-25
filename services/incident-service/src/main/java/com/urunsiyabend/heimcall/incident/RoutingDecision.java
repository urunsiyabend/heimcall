package com.urunsiyabend.heimcall.incident;

import java.util.UUID;

/**
 * Outcome of resolving an alert through the service-catalog routing rule engine (Phase 17). The ruleset
 * is total, so a non-throwing resolve always yields one of:
 *
 * <ul>
 *   <li><b>ROUTED</b>: {@code policyId} non-null, {@code unrouted=false}; {@code matchedRuleId} is the
 *       rule that matched (null when the ruleset fallback supplied the policy).</li>
 *   <li><b>UNROUTED</b>: {@code policyId=null}, {@code unrouted=true} — the deliberate "nobody paged"
 *       outcome (Phase 10 T3), now produced by an UNROUTED rule action or a fallback with no policy.</li>
 * </ul>
 *
 * {@code rulesetVersion} is stamped on the incident for explainability. A catalog OUTAGE is neither
 * outcome: it throws {@link RoutingUnavailableException} (retry/DLT). {@code fromCache} is retained for
 * the dormant Phase 10 T4 cache path and is always false in T1 (no last-known-good cache for rule
 * decisions); T2 reinstates outage tolerance via a local ruleset projection instead.
 */
record RoutingDecision(UUID serviceId, UUID policyId, UUID matchedRuleId, long rulesetVersion,
                       boolean unrouted, boolean fromCache) {

    /** Map a routing-core decision (from local evaluation, Phase 17 T2) onto the incident shape. */
    static RoutingDecision fromCore(com.urunsiyabend.heimcall.routing.RoutingDecision d) {
        return new RoutingDecision(d.serviceId(), d.escalationPolicyId(), d.matchedRuleId(),
                d.rulesetVersion(), d.unrouted(), false);
    }

    /** Deliberate UNROUTED when the projection is cold and catalog is also unavailable — visible and
     *  counted, never a misroute. {@code rulesetVersion} 0 signals "no ruleset was applied". */
    static RoutingDecision unroutedFallback() {
        return new RoutingDecision(null, null, null, 0L, true, false);
    }
}
