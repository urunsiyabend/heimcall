package com.urunsiyabend.heimcall.catalog.routing;

import java.util.UUID;

/**
 * One ordered routing rule (Phase 17): if {@code enabled}, its {@code condition} matches, and any
 * {@code timeRestriction} is satisfied, the engine selects {@code action} and stops (first-match-wins).
 * Ordering is carried by the enclosing {@link Ruleset#rules()} list, not a field here.
 *
 * @param timeRestriction optional; {@code null} means no time gating.
 */
public record Rule(UUID id, String name, boolean enabled, ConditionNode condition,
                   RoutingAction action, TimeRestriction timeRestriction) {

    public Rule {
        if (id == null) {
            throw new IllegalArgumentException("rule id required");
        }
        if (condition == null) {
            throw new IllegalArgumentException("rule condition required");
        }
        if (action == null) {
            throw new IllegalArgumentException("rule action required");
        }
    }
}
