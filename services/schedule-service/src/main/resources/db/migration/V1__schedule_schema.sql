-- An on-call schedule belongs to one organization and carries a timezone; all rotation
-- handoff boundaries are computed in that zone.
CREATE TABLE on_call_schedule (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    name            VARCHAR(255) NOT NULL,
    timezone        VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_schedule_org ON on_call_schedule (organization_id);

-- A rotation cycles its participants. DAILY hands off every day, WEEKLY every 7 days,
-- both at handoff_time on the schedule's zone, anchored at start_date. Higher priority wins
-- when a schedule has more than one rotation.
CREATE TABLE schedule_rotation (
    id            UUID PRIMARY KEY,
    schedule_id   UUID         NOT NULL REFERENCES on_call_schedule (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    type          VARCHAR(16)  NOT NULL,
    start_date    DATE         NOT NULL,
    handoff_time  TIME         NOT NULL,
    priority      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_rotation_schedule ON schedule_rotation (schedule_id);

-- Ordered participants of a rotation. position defines the cycle order.
CREATE TABLE rotation_participant (
    id          UUID PRIMARY KEY,
    rotation_id UUID    NOT NULL REFERENCES schedule_rotation (id) ON DELETE CASCADE,
    user_id     UUID    NOT NULL,
    position    INTEGER NOT NULL,
    UNIQUE (rotation_id, user_id),
    UNIQUE (rotation_id, position)
);
CREATE INDEX ix_participant_rotation ON rotation_participant (rotation_id);

-- An override pins a user as on-call for [start_at, end_at), taking priority over any rotation.
CREATE TABLE schedule_override (
    id          UUID PRIMARY KEY,
    schedule_id UUID        NOT NULL REFERENCES on_call_schedule (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL,
    start_at    TIMESTAMPTZ NOT NULL,
    end_at      TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_override_schedule ON schedule_override (schedule_id);
