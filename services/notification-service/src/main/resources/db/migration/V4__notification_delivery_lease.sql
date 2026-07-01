-- Phase 20 T1: two-phase claim for the delivery worker. A claim flips PENDING -> SENDING with a lease
-- (lease_token + lease_expires_at) and commits, releasing the FOR UPDATE row lock so the actual
-- SMTP/webhook send runs OUTSIDE the transaction and a pool of senders can run concurrently (the
-- single-thread send-in-tx worker capped delivery at ~87/s; everything upstream sustains ~670/s).
--
-- at-least-once is preserved: a crash mid-send leaves the row SENDING; once lease_expires_at passes it
-- is re-claimable, so the send is retried, never lost. lease_token is a fencing token — the result write
-- only applies while the token still matches, so a revived worker whose lease expired cannot overwrite a
-- row the new owner already re-claimed.
ALTER TABLE notification_delivery
    ADD COLUMN lease_token      UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_delivery.status IS 'PENDING | SENDING | DELIVERED | FAILED';

-- Claimable scan: a due PENDING row, or a due SENDING row whose lease has expired (crashed worker).
-- Partial index keeps the claim query tight as DELIVERED/FAILED rows accumulate.
CREATE INDEX ix_notification_delivery_claimable
    ON notification_delivery (next_attempt_at)
    WHERE status IN ('PENDING', 'SENDING');
