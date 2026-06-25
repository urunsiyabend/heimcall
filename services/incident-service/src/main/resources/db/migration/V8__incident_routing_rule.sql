-- Phase 17 T1: stamp the routing-engine decision on the incident for explainability. matched_rule_id
-- is the routing_rule that selected the target (null when the ruleset fallback supplied the policy);
-- ruleset_version is the per-org ruleset version that was evaluated. Both nullable: pre-Phase-17 rows
-- and UNROUTED incidents carry no rule/version.
ALTER TABLE incident ADD COLUMN matched_rule_id UUID;
ALTER TABLE incident ADD COLUMN ruleset_version BIGINT;
