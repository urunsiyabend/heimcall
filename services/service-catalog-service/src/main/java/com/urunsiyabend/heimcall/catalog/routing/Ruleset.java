package com.urunsiyabend.heimcall.catalog.routing;

import java.time.ZoneId;
import java.util.List;

/**
 * A complete, evaluable routing ruleset for one organization (Phase 17): an ordered rule list plus a
 * pinned {@code fallbackAction} that runs when no rule matches. The fallback (NOT an entry in
 * {@code rules}) makes every ruleset <b>total</b> — it always produces an outcome for any event.
 *
 * @param version       monotonic per-org version; stamped on every routing decision for explainability
 *                      and used to version-gate the T2 snapshot read-model.
 * @param timezone      the org timezone used to evaluate rule {@link TimeRestriction}s; defaults to UTC.
 * @param rules         rules in evaluation order (first match wins).
 * @param fallbackAction taken when no rule matches; {@code UNROUTED} when the org has no default policy.
 */
public record Ruleset(long version, ZoneId timezone, List<Rule> rules, RoutingAction fallbackAction) {

    public Ruleset {
        timezone = timezone == null ? ZoneId.of("UTC") : timezone;
        rules = rules == null ? List.of() : List.copyOf(rules);
        fallbackAction = fallbackAction == null ? RoutingAction.unrouted() : fallbackAction;
    }
}
