-- Phase 10 T3: a definitive no-match (no service carries the routingKey AND no org-default
-- escalation policy is configured) is now a deliberate, visible UNROUTED outcome rather than a
-- silent null-policy afterthought. The flag lets the UI surface "nobody was paged" and backs the
-- incident_unrouted_total counter. Existing rows are routed-or-unknown under the old behavior; FALSE
-- is the safe default (they keep whatever policy they were stamped with).
ALTER TABLE incident ADD COLUMN unrouted BOOLEAN NOT NULL DEFAULT FALSE;
