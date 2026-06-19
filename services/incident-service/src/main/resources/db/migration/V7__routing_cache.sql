-- Phase 10 T4: routing AVAILABILITY cache (last-known-good).
--
-- A catalog outage longer than the consumer retry budget previously dead-lettered a real incident
-- (RoutingUnavailableException -> retry -> DLT). This table lets incident-service page from the
-- last-known-good routing during such an outage instead of dropping to the DLT. It is an
-- availability/correctness cache, NOT a latency cache (the resolve hop is ~2-3ms): no TTL, freshness
-- comes from write-through on every successful (up) resolve and invalidation on a catalog 404.
--
-- Only ROUTED answers (catalog 200 carrying a non-null escalation policy) are ever cached; a definitive
-- no-match (404) is never cached and additionally TOMBSTONES any positive row, so a dead route can never
-- survive to page on the next outage.
CREATE TABLE routing_cache (
    organization_id      UUID        NOT NULL,
    routing_key          TEXT        NOT NULL,
    service_id           UUID,                       -- nullable: org-default fallthrough has no specific service
    escalation_policy_id UUID        NOT NULL,       -- only 200-with-policy is cached
    owner_team_id        UUID,
    last_refreshed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, routing_key)
);

-- An incident paged from the cache during a catalog outage (degraded routing). Surfaced on the API +
-- UI (mirrors `unrouted`) and backs the incident_routed_from_cache_total counter, so a page on stale
-- routing is visible, not silent.
ALTER TABLE incident ADD COLUMN routed_from_cache BOOLEAN NOT NULL DEFAULT FALSE;

-- Reconciliation (audit-only, scoped to routed_from_cache incidents): after catalog recovery the job
-- re-resolves the incident's routingKey and records how catalog answers NOW vs the cached route it used.
-- reconciled_at NULL = not yet reconciled (also covers "catalog still unavailable" -> retried next run).
-- reconcile_result in {CURRENT_MATCH, CURRENT_DRIFT, CURRENT_NOT_FOUND}; CURRENT_ = "catalog differs now",
-- NOT proof the cached route was wrong at outage time (no catalog as-of history).
ALTER TABLE incident ADD COLUMN reconciled_at   TIMESTAMPTZ;
ALTER TABLE incident ADD COLUMN reconcile_result TEXT;
