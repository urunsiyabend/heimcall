-- Contact methods owned by notification-service (Notification context, plan 4.7). Maps a user to a
-- concrete destination per channel. A notification request fans out to the recipient's enabled
-- contact methods. enabled doubles as a basic channel preference (disable a channel for a user).
CREATE TABLE contact_method (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    channel         VARCHAR(16)  NOT NULL,   -- EMAIL | WEBHOOK
    destination     VARCHAR(1024) NOT NULL,  -- email address | webhook url
    label           VARCHAR(255),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (organization_id, user_id, channel, destination)
);
CREATE INDEX ix_contact_method_user ON contact_method (organization_id, user_id);

-- A consumed notification.requested.v1 event. event_id is the PK, so it doubles as the idempotency
-- ledger: a redelivered request whose id is already present is a no-op.
CREATE TABLE notification_request (
    event_id          UUID PRIMARY KEY,
    organization_id   UUID         NOT NULL,
    incident_id       UUID         NOT NULL,
    recipient_user_id UUID         NOT NULL,
    level             INTEGER      NOT NULL,
    target_source     VARCHAR(16),            -- how escalation resolved the recipient (USER/SCHEDULE/TEAM)
    title             VARCHAR(1024),
    severity          VARCHAR(32),
    received_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_notification_request_incident ON notification_request (organization_id, incident_id);

-- One delivery attempt-track per (request, contact method). The worker fires PENDING deliveries whose
-- next_attempt_at has passed; on success -> DELIVERED, on failure -> retry with backoff until
-- max attempts, then FAILED. Tracked separately from incident/request state (engineering rule).
CREATE TABLE notification_delivery (
    id               UUID PRIMARY KEY,
    request_event_id UUID         NOT NULL REFERENCES notification_request (event_id) ON DELETE CASCADE,
    organization_id  UUID         NOT NULL,
    incident_id      UUID         NOT NULL,
    recipient_user_id UUID        NOT NULL,
    contact_method_id UUID        NOT NULL,
    channel          VARCHAR(16)  NOT NULL,
    destination      VARCHAR(1024) NOT NULL,
    status           VARCHAR(16)  NOT NULL,   -- PENDING | DELIVERED | FAILED
    attempts         INTEGER      NOT NULL DEFAULT 0,
    last_error       VARCHAR(2048),
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    last_attempt_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    -- A request notifies a given contact method at most once.
    UNIQUE (request_event_id, contact_method_id)
);
CREATE INDEX ix_notification_delivery_due ON notification_delivery (status, next_attempt_at);
CREATE INDEX ix_notification_delivery_incident ON notification_delivery (organization_id, incident_id);
