package com.urunsiyabend.heimcall.routing;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of evaluating a {@link RoutingContext} against a {@link Ruleset} (Phase 17). Always
 * produced (the ruleset is total): either a matched rule's action, or the ruleset fallback.
 *
 * @param matchedRuleId  the rule that selected this target; {@code null} when the fallback was used.
 * @param rulesetVersion the version evaluated (stamped on the incident for explainability).
 * @param unrouted       true when the selected action is {@code UNROUTED} (nobody paged, deliberate).
 * @param trace          per-rule evaluation trace; populated only for the dry-run preview (off on the
 *                       hot path). Empty, never null.
 */
public record RoutingDecision(UUID serviceId, UUID escalationPolicyId, UUID matchedRuleId,
                              long rulesetVersion, boolean unrouted, List<TraceEntry> trace) {

    public RoutingDecision {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    /** One evaluated rule's result: whether it matched and a short human-readable reason. */
    public record TraceEntry(UUID ruleId, String ruleName, boolean matched, String detail) {
    }

    static RoutingDecision matched(Rule rule, long version, List<TraceEntry> trace) {
        RoutingAction a = rule.action();
        return new RoutingDecision(a.serviceId(), a.escalationPolicyId(), rule.id(), version,
                a.type() == RoutingAction.Type.UNROUTED, trace);
    }

    static RoutingDecision fallback(RoutingAction fallback, long version, List<TraceEntry> trace) {
        return new RoutingDecision(fallback.serviceId(), fallback.escalationPolicyId(), null, version,
                fallback.type() == RoutingAction.Type.UNROUTED, trace);
    }
}
