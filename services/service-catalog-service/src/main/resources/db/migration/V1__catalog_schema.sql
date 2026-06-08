-- A monitored service: the thing alerts are about and on-call covers. Belongs to one organization.
-- owner_team_id / escalation_policy_id reference identity-service / escalation-service (not FKs here,
-- they live in other services' databases). escalation_policy_id is a placeholder until Phase 5.
CREATE TABLE monitored_service (
    id                   UUID PRIMARY KEY,
    organization_id      UUID         NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    slug                 VARCHAR(255) NOT NULL,
    description          TEXT,
    owner_team_id        UUID,
    escalation_policy_id UUID,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    UNIQUE (organization_id, slug)
);
CREATE INDEX ix_monitored_service_org ON monitored_service (organization_id);

CREATE TABLE service_tag (
    id         UUID PRIMARY KEY,
    service_id UUID         NOT NULL REFERENCES monitored_service (id) ON DELETE CASCADE,
    tag_key    VARCHAR(128) NOT NULL,
    tag_value  VARCHAR(512) NOT NULL,
    UNIQUE (service_id, tag_key)
);
CREATE INDEX ix_service_tag_service ON service_tag (service_id);
