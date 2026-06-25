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

    static RoutingDecision of(CatalogClient.RoutingResult r) {
        return new RoutingDecision(r.serviceId(), r.escalationPolicyId(), r.matchedRuleId(),
                r.rulesetVersion(), r.unrouted(), false);
    }
}
