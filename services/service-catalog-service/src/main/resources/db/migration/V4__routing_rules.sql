-- Phase 17 T1: ordered, conditional routing rule engine. Replaces the flat
-- routingKey -> service -> policy map (V2) + the org-default catch-all (V3) with a per-org ruleset:
-- an ordered rule list plus a pinned fallback action. routing_ruleset.version is a monotonic per-org
-- counter bumped on every rule write, so T2's snapshot events carry a version to gate on.
-- All service/policy references live in other services' databases (not FKs here).

CREATE TABLE routing_ruleset (
    organization_id     UUID PRIMARY KEY,
    version             BIGINT       NOT NULL,
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',  -- IANA zone for time-of-day rules (DST-aware)
    published_at        TIMESTAMPTZ  NOT NULL,
    fallback_service_id UUID,                                  -- nullable; fallback is usually policy-only
    fallback_policy_id  UUID                                   -- null fallback => UNROUTED (visible, counted)
);

-- One ordered rule. condition_json is the typed condition tree (ConditionNode); time_restriction_json
-- is an optional day/window gate evaluated in the ruleset timezone. The action lives in columns, not
-- JSON, so service/policy references are queryable and validatable.
CREATE TABLE routing_rule (
    id                   UUID PRIMARY KEY,
    organization_id      UUID         NOT NULL,
    position             INT          NOT NULL,          -- 0-based evaluation order (first match wins)
    name                 VARCHAR(255) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    condition_json       JSONB        NOT NULL,
    action_type          VARCHAR(16)  NOT NULL,          -- ROUTE | UNROUTED
    action_service_id    UUID,
    action_policy_id     UUID,                            -- non-null when action_type = ROUTE
    time_restriction_json JSONB,
    created_at           TIMESTAMPTZ  NOT NULL,
    UNIQUE (organization_id, position)
);
CREATE INDEX ix_routing_rule_org ON routing_rule (organization_id, position);

-- Migrate current behavior: one "EQUALS routingKey -> {service, policy}" rule per monitored_service that
-- carries a routing_key AND has an escalation policy. Reproduces pre-Phase-17 routing exactly.
INSERT INTO routing_rule (id, organization_id, position, name, enabled, condition_json,
                          action_type, action_service_id, action_policy_id, created_at)
SELECT gen_random_uuid(),
       ms.organization_id,
       (row_number() OVER (PARTITION BY ms.organization_id ORDER BY ms.created_at, ms.id)) - 1,
       'Route ' || ms.name,
       TRUE,
       jsonb_build_object(
           'node', 'LEAF',
           'field', jsonb_build_object('kind', 'SYSTEM', 'name', 'routingKey'),
           'operator', 'EQUALS',
           'values', jsonb_build_array(ms.routing_key)
       ),
       'ROUTE',
       ms.id,
       ms.escalation_policy_id,
       now()
FROM monitored_service ms
WHERE ms.routing_key IS NOT NULL
  AND ms.escalation_policy_id IS NOT NULL;

-- Seed one ruleset per org that has either migrated rules or an org default. Fallback comes from the
-- existing org_routing_default (policy-only); orgs with rules but no default get a null (UNROUTED)
-- fallback. version starts past the seeded rule count so the next write is strictly greater.
INSERT INTO routing_ruleset (organization_id, version, timezone, published_at,
                             fallback_service_id, fallback_policy_id)
SELECT o.organization_id,
       COALESCE(rc.cnt, 0) + 1,
       'UTC',
       now(),
       NULL,
       d.default_escalation_policy_id
FROM (
    SELECT organization_id FROM monitored_service
        WHERE routing_key IS NOT NULL AND escalation_policy_id IS NOT NULL
    UNION
    SELECT organization_id FROM org_routing_default
) o
LEFT JOIN (
    SELECT organization_id, count(*) AS cnt FROM routing_rule GROUP BY organization_id
) rc ON rc.organization_id = o.organization_id
LEFT JOIN org_routing_default d ON d.organization_id = o.organization_id;

-- V2 routing_key + V3 org_routing_default columns/tables stay (deprecated) so nothing breaks; the engine
-- is now the single resolution path. They are removed/repurposed in a later phase.
