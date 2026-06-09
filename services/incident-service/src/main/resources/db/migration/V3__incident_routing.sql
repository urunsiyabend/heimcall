-- Routing/ownership stamped on the incident at trigger time (Phase 5).
-- routing_key comes from the alert; service_id / escalation_policy_id are resolved from
-- service-catalog. All nullable: an incident is still created when routing has no match
-- or the catalog is unreachable (the core path must not depend on escalation).
ALTER TABLE incident ADD COLUMN routing_key         VARCHAR(255);
ALTER TABLE incident ADD COLUMN service_id          UUID;
ALTER TABLE incident ADD COLUMN escalation_policy_id UUID;
