package com.urunsiyabend.heimcall.incident.domain;

/**
 * Explicit state of an org's local routing ruleset projection (Phase 17 T2), kept distinct so ops is
 * not blind — these must NOT collapse into a single "UNROUTED" metric.
 *
 * <ul>
 *   <li>{@link #READY} — a ruleset (version &gt;= 1) is held and current.</li>
 *   <li>{@link #ABSENT_CONFIRMED} — catalog confirmed the org genuinely has no rules (version 0);
 *       routing falls to UNROUTED, but this is a known answer, not a gap.</li>
 *   <li>{@link #UNINITIALIZED} — no row yet (never hydrated); the absence of a projection row.</li>
 *   <li>{@link #STALE} — a READY projection whose {@code observed_at} is older than the freshness
 *       policy; derived at read time, never persisted. Routing keeps using the last-known ruleset.</li>
 * </ul>
 * Only {@link #READY} and {@link #ABSENT_CONFIRMED} are ever stored.
 */
public enum ProjectionState {
    READY,
    ABSENT_CONFIRMED,
    UNINITIALIZED,
    STALE
}
