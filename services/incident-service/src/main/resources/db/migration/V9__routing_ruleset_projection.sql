-- Phase 17 T2: local read-model of each org's routing ruleset. incident-service consumes the
-- routing.ruleset-published.v1 snapshot stream into this table and evaluates routing locally against it
-- (the shared routing-core engine), so service-catalog leaves the hot path entirely and a catalog outage
-- no longer affects routing (only delays the NEXT version). Persisting in incident's OWN database (not
-- catalog's -- database-per-service holds) means a restart/scale-up inherits populated state: there is no
-- process-level cold start, only genuine first-population (new tenant / empty system / DB restore).
--
-- payload_json is the serialized routing-core Ruleset; the upsert is version-gated (apply only if the
-- incoming version is strictly greater), so duplicate and out-of-order snapshot delivery are safe.
-- state: READY (a ruleset with version >= 1) | ABSENT_CONFIRMED (catalog confirmed the org has no rules).
-- UNINITIALIZED is the absence of a row; STALE is derived from observed_at + the freshness policy.
CREATE TABLE routing_ruleset_projection (
    organization_id UUID PRIMARY KEY,
    version         BIGINT       NOT NULL,
    payload_json    JSONB        NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    observed_at     TIMESTAMPTZ  NOT NULL
);
