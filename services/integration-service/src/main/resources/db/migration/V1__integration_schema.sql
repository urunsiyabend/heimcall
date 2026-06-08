-- Audit + replay store for every inbound webhook payload. Persisted before the
-- Kafka publish so a broker outage leaves a durable FAILED record (no silent loss).
CREATE TABLE raw_inbound_event (
    id              UUID PRIMARY KEY,
    event_id        UUID         NOT NULL,
    integration_key VARCHAR(255) NOT NULL,
    routing_key     VARCHAR(255) NOT NULL,
    source          VARCHAR(255),
    dedup_key       VARCHAR(512),
    payload         TEXT         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    error           TEXT,
    received_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX ix_raw_inbound_status ON raw_inbound_event (status);
CREATE INDEX ix_raw_inbound_event_id ON raw_inbound_event (event_id);
