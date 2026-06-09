-- Escalation policy: an ordered set of levels that notify responders for an incident, escalating
-- when no acknowledgement arrives in time. Owned by escalation-service; referenced by service-catalog
-- (monitored_service.escalation_policy_id) and stamped on incidents at trigger time.
CREATE TABLE escalation_policy (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    name            VARCHAR(255) NOT NULL,
    -- After the last level fires and the incident is still open, repeat the whole policy this many
    -- times (0 = no repeat).
    repeat_count    INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_escalation_policy_org ON escalation_policy (organization_id);

-- A level within a policy. delay_seconds is the wait, measured from the incident trigger, before
-- this level fires. Level 1 typically has delay 0 (notify immediately).
CREATE TABLE escalation_rule (
    id            UUID PRIMARY KEY,
    policy_id     UUID    NOT NULL REFERENCES escalation_policy (id) ON DELETE CASCADE,
    level         INTEGER NOT NULL,
    delay_seconds INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (policy_id, level)
);
CREATE INDEX ix_escalation_rule_policy ON escalation_rule (policy_id);

-- A notification target of a rule. target_type in (USER, SCHEDULE, TEAM); target_id is the user,
-- schedule, or team id resolved at fire time.
CREATE TABLE escalation_rule_target (
    id          UUID PRIMARY KEY,
    rule_id     UUID        NOT NULL REFERENCES escalation_rule (id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL,
    target_id   UUID        NOT NULL,
    UNIQUE (rule_id, target_type, target_id)
);
CREATE INDEX ix_escalation_rule_target_rule ON escalation_rule_target (rule_id);

-- Incident context captured at trigger time, so the worker can build a notification (title,
-- severity) when a task fires later without calling back into incident-service.
CREATE TABLE escalation_incident (
    incident_id     UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    policy_id       UUID         NOT NULL,
    title           VARCHAR(1024),
    severity        VARCHAR(32),
    triggered_at    TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- A scheduled execution of one level for one incident. The worker fires PENDING tasks whose
-- scheduled_at has passed; ACK/RESOLVE cancels still-PENDING tasks for the incident.
CREATE TABLE escalation_task (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    incident_id     UUID        NOT NULL,
    policy_id       UUID        NOT NULL,
    rule_id         UUID        NOT NULL,
    level           INTEGER     NOT NULL,
    repeat_index    INTEGER     NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL,
    executed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (incident_id, level, repeat_index)
);
CREATE INDEX ix_escalation_task_due ON escalation_task (status, scheduled_at);
CREATE INDEX ix_escalation_task_incident ON escalation_task (incident_id);

-- Idempotency ledger: an incident event id already recorded is a redelivery and must be a no-op.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
