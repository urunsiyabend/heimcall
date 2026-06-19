package com.urunsiyabend.heimcall.incident.domain;

/**
 * Audit-only outcome of reconciling a {@code routed_from_cache} incident against catalog after recovery
 * (Phase 10 T4). The {@code CURRENT_} prefix is deliberate: it states how catalog answers NOW, not that
 * the cached route used at outage time was wrong (there is no catalog as-of history to prove that).
 */
public enum ReconcileResult {
    /** Catalog now resolves the routingKey to the same policy the incident was cache-routed to. */
    CURRENT_MATCH,
    /** Catalog now resolves to a different policy than the cached one. Counted as routing_cache_drift. */
    CURRENT_DRIFT,
    /** Catalog now returns a definitive no-match (404); the cache row was tombstoned. */
    CURRENT_NOT_FOUND
}
