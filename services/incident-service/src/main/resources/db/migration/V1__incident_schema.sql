CREATE TABLE incident (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL,
    source             VARCHAR(255) NOT NULL,
    dedup_key          VARCHAR(512) NOT NULL,
    external_entity_id VARCHAR(512),
    title              VARCHAR(1024) NOT NULL,
    description        TEXT,
    severity           VARCHAR(32) NOT NULL,
    status             VARCHAR(32) NOT NULL,
    alert_count        INTEGER     NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    last_event_at      TIMESTAMPTZ NOT NULL
);

-- At most one OPEN incident per (organization, dedup_key). Enforces deduplication
-- at the database level; resolved/canceled incidents do not block a new trigger.
CREATE UNIQUE INDEX ux_incident_open_dedup
    ON incident (organization_id, dedup_key)
    WHERE status IN ('TRIGGERED', 'ACKNOWLEDGED');

CREATE INDEX ix_incident_status ON incident (status);

CREATE TABLE incident_timeline_event (
    id          UUID PRIMARY KEY,
    incident_id UUID        NOT NULL REFERENCES incident (id),
    type        VARCHAR(64) NOT NULL,
    message     TEXT,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_timeline_incident ON incident_timeline_event (incident_id);
