-- Phase 3 (finish): Alert as a first-class aggregate with a per-signal occurrence log
-- (plan 4.4, glossary §2/§Alert). Domain flow: Event -> Alert -> (if actionable) Incident.
--
--   alert            the deduplicated signal aggregate. One OPEN alert per (org, dedup_key);
--                    repeated signals bump occurrence_count + last_seen_at instead of spawning rows.
--                    incident_id is nullable: an alert MAY exist without an incident (INFO / suppressed).
--   alert_occurrence the immutable per-signal log. One row per inbound AlertReceivedEvent that hits
--                    the alert, capturing that signal's facts (message_type / severity / timing).
CREATE TABLE alert (
    id                 UUID PRIMARY KEY,
    organization_id    UUID         NOT NULL,
    incident_id        UUID         REFERENCES incident (id),
    source             VARCHAR(255) NOT NULL,
    dedup_key          VARCHAR(512) NOT NULL,
    external_entity_id VARCHAR(512),
    status             VARCHAR(32)  NOT NULL,
    severity           VARCHAR(32)  NOT NULL,
    title              VARCHAR(1024),
    occurrence_count   INTEGER      NOT NULL DEFAULT 1,
    first_seen_at      TIMESTAMPTZ  NOT NULL,
    last_seen_at       TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

-- Deduplication at the alert level: at most one OPEN alert per (organization, dedup_key).
CREATE UNIQUE INDEX ux_alert_open_dedup
    ON alert (organization_id, dedup_key)
    WHERE status = 'OPEN';

CREATE INDEX ix_alert_incident ON alert (incident_id);

CREATE TABLE alert_occurrence (
    id           UUID PRIMARY KEY,
    alert_id     UUID        NOT NULL REFERENCES alert (id),
    event_id     UUID,
    message_type VARCHAR(32) NOT NULL,
    severity     VARCHAR(32) NOT NULL,
    title        VARCHAR(1024),
    description  TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_alert_occurrence_alert ON alert_occurrence (alert_id);

-- Occurrence count now lives on the alert aggregate; the incident no longer owns a signal counter.
ALTER TABLE incident DROP COLUMN alert_count;
