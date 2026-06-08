CREATE TABLE organization (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL
);

-- Users are global; their tie to an organization is the membership row below.
CREATE TABLE app_user (
    id           UUID PRIMARY KEY,
    email        VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- Org membership with a role. Enforces "who belongs to which organization".
CREATE TABLE membership (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    user_id         UUID        NOT NULL REFERENCES app_user (id),
    role            VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, user_id)
);
CREATE INDEX ix_membership_org ON membership (organization_id);
CREATE INDEX ix_membership_user ON membership (user_id);

CREATE TABLE team (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (organization_id, name)
);
CREATE INDEX ix_team_org ON team (organization_id);

CREATE TABLE team_member (
    id         UUID PRIMARY KEY,
    team_id    UUID        NOT NULL REFERENCES team (id),
    user_id    UUID        NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (team_id, user_id)
);
CREATE INDEX ix_team_member_team ON team_member (team_id);

-- Integration keys are hashed at rest; the plaintext is shown once on creation.
-- resolve() hashes an inbound key and looks it up here to recover org + integration id.
CREATE TABLE integration_key (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    integration_id  UUID         NOT NULL,
    name            VARCHAR(255) NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_integration_key_org ON integration_key (organization_id);
