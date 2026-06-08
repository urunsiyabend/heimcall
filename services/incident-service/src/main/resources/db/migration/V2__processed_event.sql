-- Consumer idempotency ledger. The listener records every handled event id here in
-- the same transaction as the incident change; a Kafka redelivery whose id already
-- exists is skipped, so alertCount and timeline are not inflated by reprocessing.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
