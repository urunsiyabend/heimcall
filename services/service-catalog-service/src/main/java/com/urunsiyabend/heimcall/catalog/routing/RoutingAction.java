package com.urunsiyabend.heimcall.catalog.routing;

import java.util.UUID;

/**
 * The target a rule (or the ruleset fallback) selects (Phase 17). {@code ROUTE} carries a service +
 * escalation policy; {@code UNROUTED} is the deliberate, observable "nobody paged" outcome (Phase 10
 * T3) — used as a fallback when an org has no default policy, or as an explicit rule action.
 */
public record RoutingAction(Type type, UUID serviceId, UUID escalationPolicyId) {

    public enum Type { ROUTE, UNROUTED }

    public RoutingAction {
        if (type == null) {
            throw new IllegalArgumentException("action type required");
        }
        if (type == Type.ROUTE && escalationPolicyId == null) {
            throw new IllegalArgumentException("ROUTE action requires an escalation policy");
        }
    }

    public static RoutingAction route(UUID serviceId, UUID escalationPolicyId) {
        return new RoutingAction(Type.ROUTE, serviceId, escalationPolicyId);
    }

    public static RoutingAction unrouted() {
        return new RoutingAction(Type.UNROUTED, null, null);
    }
}
