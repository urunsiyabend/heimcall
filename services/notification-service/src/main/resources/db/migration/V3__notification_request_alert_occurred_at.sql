-- Phase 19 T5: pipeline origin for end-to-end alert→delivered latency. Nullable so in-flight pre-T5
-- requests (no origin) are accepted; the e2e timer skips a row whose alert_occurred_at is null.
ALTER TABLE notification_request ADD COLUMN alert_occurred_at timestamptz;
