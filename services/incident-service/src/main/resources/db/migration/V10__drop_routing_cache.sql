-- Phase 17 T2 cleanup: the Phase 10 T4 last-known-good routing availability cache and its audit-only
-- reconciliation are superseded by the local ruleset projection (V9) + local evaluation. A catalog
-- outage is now tolerated by routing on the replicated ruleset, so the key-only cache (unsafe once
-- routing depends on more than the key) and the routed_from_cache / reconcile bookkeeping are removed.
DROP TABLE IF EXISTS routing_cache;

ALTER TABLE incident DROP COLUMN IF EXISTS routed_from_cache;
ALTER TABLE incident DROP COLUMN IF EXISTS reconciled_at;
ALTER TABLE incident DROP COLUMN IF EXISTS reconcile_result;
