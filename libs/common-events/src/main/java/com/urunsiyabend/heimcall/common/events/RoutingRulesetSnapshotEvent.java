package com.urunsiyabend.heimcall.common.events;

import com.urunsiyabend.heimcall.routing.Ruleset;

import java.time.Instant;
import java.util.UUID;

/**
 * A complete, versioned snapshot of one organization's routing ruleset (Phase 17 T2). Published by
 * service-catalog on every rule write (via the transactional outbox, in the same transaction as the
 * write) and consumed by incident-service into a local read-model so routing is evaluated locally —
 * catalog leaves the hot path entirely.
 *
 * <p>A <b>full snapshot, not a delta</b>: duplicate delivery is harmless, out-of-order delivery is
 * resolved by the {@code rulesetVersion} gate (a consumer applies a snapshot only if it is newer than
 * what it holds), and a stale snapshot stuck in a DLT is superseded by any newer one. {@code ruleset}
 * is the {@code routing-core} model so the consumer reconstructs exactly what catalog evaluated.
 *
 * @param eventId        unique id for this event (dedup of the event itself).
 * @param organizationId tenant; also the Kafka message key (latest snapshot per org).
 * @param rulesetVersion monotonic per-org version (mirrors {@code ruleset.version()}); the gate value.
 * @param ruleset        the full evaluable ruleset (ordered rules + pinned fallback + timezone).
 * @param publishedAt    when catalog published this version.
 */
public record RoutingRulesetSnapshotEvent(
        UUID eventId,
        UUID organizationId,
        long rulesetVersion,
        Ruleset ruleset,
        Instant publishedAt
) {
}
