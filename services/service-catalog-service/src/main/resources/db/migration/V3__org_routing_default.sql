-- Org-level catch-all escalation policy (Phase 10 T2). Makes routing resolution total: when no specific
-- service carries the routingKey (or the matched service has no policy), routing falls back to this org
-- default. default_escalation_policy_id references a policy in escalation-service (validated on set,
-- not an FK -- it lives in another service's database). One row per org; absence means no default.
CREATE TABLE org_routing_default (
    organization_id             UUID PRIMARY KEY,
    default_escalation_policy_id UUID        NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL
);
