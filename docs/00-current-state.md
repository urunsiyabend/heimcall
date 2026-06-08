# Heimcall - Current State

Living document. Update at the end of every sprint. Reflects what is actually built and running, not what is planned. Plan lives in `01-development-plan.md`; this file is the source of truth for "where are we now".

Last updated: 2026-06-09 (end of Sprint 5)

## 1. Snapshot

| Area | State |
| --- | --- |
| Architecture | Microservices-first monorepo, Gradle multi-project |
| Build | `./gradlew build` green on Java 21 |
| Runtime verified | Sprint 5 scheduling slice (schedules, daily/weekly rotations, overrides, timezone-aware on-call) |
| Last sprint | Sprint 5 - Phase 4 schedule-service |
| Tests | First automated test: `OnCallCalculatorTest` (rotation math) |

## 2. Locked decisions

- Microservices from day one (no modular monolith phase). See plan section 1.
- Monorepo, Gradle multi-project, shared config via root `subprojects {}` block.
- Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.3, Gradle wrapper 8.10.2.
- Database per service. Local dev: one PostgreSQL, one database per service.
- Kafka as the event backbone; topic naming `<context>.<event-name>.v<version>`.
- Product name: Heimcall.

## 3. Modules

```
libs/
  common-domain     enums: MessageType, Severity, IncidentStatus, AlertStatus
  common-events      AlertReceivedEvent record, Topics constants
  test-support       Testcontainers singletons (PG + Kafka) - not yet used
services/
  api-gateway        Spring Cloud Gateway, routes -> catalog 8084, schedule 8085, identity 8083, integration 8081, incident 8082
  identity-service   org/user/team/membership CRUD + integration-key issue/resolve + internal lookups (port 8083)
  service-catalog-service  monitored services CRUD + team ownership + tags + escalation-policy placeholder (port 8084)
  schedule-service   on-call schedules, daily/weekly rotations, overrides, timezone-aware on-call resolution (port 8085)
  integration-service  webhook ingestion -> resolves key via identity -> stores raw -> publishes alert.received.v1 (acks=all)
  incident-service   consumes alert.received.v1 -> idempotency guard -> dedup -> incident + timeline -> REST; DLT on failure
deploy/
  docker-compose     postgres(5433, dbs: incident + integration + identity + catalog + schedule), kafka(9092 KRaft), redis(6379), mailhog(1025/8025)
```

Databases (one per service, single PG instance): `incident`, `integration`, `identity`, `catalog`, `schedule`.
The non-default db/role are created by `deploy/docker-compose/initdb/01-create-databases.sql` on a fresh data
volume; on an existing volume create them manually (see section 7).

Ports: api-gateway 8080, integration 8081, incident 8082, identity 8083, service-catalog 8084, schedule 8085.

## 4. Implemented behavior

### identity-service (port 8083)
- Tenant isolation is a **header-context stub**: org-scoped endpoints take `X-User-Id`; `TenantGuard`
  requires an existing membership in the path `orgId`, else 403 (missing header -> 400). JWT deferred.
- Persistence (Flyway V1): `organization`, `app_user`, `membership` (org role), `team`, `team_member`,
  `integration_key`
- Org/User: `POST/GET /v1/organizations`, `POST/GET /v1/users`
- Membership: `POST/GET /v1/organizations/{orgId}/memberships` - the **first** membership of an org is a
  bootstrap (no caller header needed); every later add must come from an existing member
- Teams: `POST/GET /v1/organizations/{orgId}/teams` and `.../teams/{teamId}/members`
- Integration keys: `POST/GET /v1/organizations/{orgId}/integration-keys` - plaintext (`hc_` + random)
  returned **once**; only a SHA-256 hash + prefix stored. `integrationId` is the stable id stamped on events.
- Resolve (internal): `POST /v1/integration-keys/resolve {key}` -> `{organizationId, integrationId, name}`,
  401 if unknown/inactive
- Internal lookups (service-to-service, not on the gateway): `GET /v1/internal/organizations/{orgId}/members/{userId}`
  (204/404), `GET /v1/internal/organizations/{orgId}/teams/{teamId}` (team-in-org check). Used by service-catalog.

### service-catalog-service (port 8084)
- Tenant rules it cannot check locally (membership, team-in-org) are enforced via identity's internal
  API (`IdentityClient`): non-member -> 403, foreign-org team -> 409, identity unreachable -> 503.
- Persistence (Flyway V1): `monitored_service`, `service_tag`
- Services: `POST/GET/PUT/DELETE /v1/organizations/{orgId}/services` - slug unique per org
- Ownership: `PUT .../services/{id}/owner {teamId}` - team verified to belong to the org (single owning team)
- Escalation policy: `PUT .../services/{id}/escalation-policy {escalationPolicyId}` - **placeholder**, id stored, not validated
- Tags: `PUT/GET/DELETE .../services/{id}/tags` - key/value, unique per (service, key)

### schedule-service (port 8085)
- Tenant rules enforced via identity internal API (`IdentityClient`): caller membership (403) and that
  participant/override users belong to the org (409); identity unreachable -> 503.
- Persistence (Flyway V1): `on_call_schedule` (timezone), `schedule_rotation`, `rotation_participant`, `schedule_override`
- Schedules: `POST/GET/PUT/DELETE /v1/organizations/{orgId}/schedules` - invalid timezone -> 400
- Rotations: `POST/GET/DELETE .../schedules/{id}/rotations` (DAILY/WEEKLY, start_date + handoff_time, priority)
  and `.../rotations/{id}/participants` (ordered, position unique)
- Overrides: `POST/GET/DELETE .../schedules/{id}/overrides` (user, [startAt, endAt))
- On-call: `GET .../schedules/{id}/on-call[?at=ISO8601]` -> `{userId, source: OVERRIDE|ROTATION, rotationId}`
  - Rotation is calendar-based in the schedule's zone (DAILY/WEEKLY, DST-aware via ChronoUnit on ZonedDateTime).
    Resolution: active override wins, else highest-priority started rotation. Pure math in `OnCallCalculator`.

### integration-service (port 8081)
- `POST /v1/integrations/{integrationKey}/events/{routingKey}` -> 202 accepted + eventId
- Resolves the integration key via identity-service (`IdentityClient`, sync REST). Invalid key -> **401**;
  identity unreachable -> **503** (cannot validate, so nothing is published). Dev placeholder org is gone.
- Validates payload (`messageType`, `entityId`, `source` required)
- Normalizes to `AlertReceivedEvent` with the resolved `organizationId` + `integrationId`,
  `dedupKey = source + ":" + entityId`
- Stores the raw payload (`raw_inbound_event`, Flyway V1) before publishing - audit + replay trace
- Publishes to Kafka topic `alert.received.v1` (key = dedupKey) with `acks=all` + idempotent producer,
  confirmed synchronously (10s timeout). On a confirmed write the raw row is marked `PUBLISHED`.
- If the publish is not confirmed: raw row marked `FAILED` with the error, request returns **503** (no silent loss)

### incident-service (port 8082)
- `@KafkaListener` on `alert.received.v1`
- Idempotency guard: `processed_event(event_id)` ledger (Flyway V2) written in the same tx as the
  incident change; a redelivered event whose id is already recorded is a no-op
- Resilience: `ErrorHandlingDeserializer` (poison-pill safe) + `DefaultErrorHandler` with bounded
  retry (1 + 2 retries, 1s apart) then `DeadLetterPublishingRecoverer` -> `alert.received.v1.DLT`
- Lifecycle rules:
  - CRITICAL / WARNING -> trigger new incident, or increment alertCount on existing open incident (+ DUPLICATE timeline)
  - RECOVERY -> resolve open incident
  - ACKNOWLEDGEMENT -> acknowledge open incident
  - INFO -> ignored
- Persistence (Flyway V1): `incident`, `incident_timeline_event`
  - Partial unique index `(organization_id, dedup_key) WHERE status IN (TRIGGERED, ACKNOWLEDGED)` enforces one open incident per dedup key
- Queries: `GET /v1/incidents[?status=]`, `GET /v1/incidents/{id}`, `GET /v1/incidents/{id}/timeline`

### Kafka topics in use
- `alert.received.v1` (produced by integration-service, consumed by incident-service)
- `alert.received.v1.DLT` (dead-letter, produced by incident-service on retry exhaustion / poison-pill)

## 5. Verified end-to-end (Sprint 5)

- weekly rotation (2 participants): week 0 -> P0, week 1 -> P1 (correct participant per period)
- override priority: inside the override window -> override user (OVERRIDE); outside -> rotation user (ROTATION)
- timezone respected: `OnCallCalculatorTest` shows the same instant resolves to a different period under UTC vs Asia/Tokyo
- invalid timezone on create -> 400; non-member access -> 403; non-member participant -> 409
- `OnCallCalculatorTest` green (daily rotate + wraparound, handoff-time boundary, weekly, not-started, tz)

(Sprint 4 still holds: monitored services, team ownership w/ cross-service tenant check, tags, gateway routing.
Sprint 3: key issue/resolve, real tenant on ingest. Sprint 2: idempotency, DLT, broker-outage 503. Sprint 1: dedup, recovery, timeline.)

## 6. Known gaps / deliberately deferred

| Gap | Where it is addressed |
| --- | --- |
| `ApiKey` (user/programmatic key) - only `IntegrationKey` built | Phase 1a deferred list |
| Real JWT + Spring Security (still header-context stub) | later phase |
| Redis cache for integration-key resolution + cross-service tenant checks (sync REST each call) | later |
| RBAC beyond membership (role stored, not enforced per-action) | later |
| Integration-key revoke/rotate endpoints (revoke on entity, not exposed) | later |
| Single owning team only; multi-team ownership table | later |
| MonitoredService not wired to incident routing (routing-key -> service) | later (Phase 5 area) |
| Rotation length DAILY/WEEKLY only; custom N-day rotations | later |
| DST transition-instant edge not tested; on-call has no Redis cache | later |
| Schedule not yet consumed by escalation (no escalation engine yet) | Phase 5 |
| Separate Alert table (count lives on incident) | Phase 3 |
| `processed_event` ledger has no TTL/pruning (grows unbounded) | later |
| Transactional outbox; FAILED raw events have no auto-republisher | later |
| Notifications, escalation | Phases 5-6 |
| Tests: only `OnCallCalculatorTest` so far; no integration/contract tests | ongoing |

## 7. How to run locally

```bash
# 1. infra
docker compose -f deploy/docker-compose/docker-compose.yml up -d

# 1b. on an EXISTING data volume the extra dbs are not auto-created; create once:
docker exec heimcall-local-postgres-1 psql -U incident \
  -c "CREATE ROLE integration WITH LOGIN PASSWORD 'integration';" \
  -c "CREATE DATABASE integration OWNER integration;" \
  -c "CREATE ROLE identity WITH LOGIN PASSWORD 'identity';" \
  -c "CREATE DATABASE identity OWNER identity;" \
  -c "CREATE ROLE catalog WITH LOGIN PASSWORD 'catalog';" \
  -c "CREATE DATABASE catalog OWNER catalog;" \
  -c "CREATE ROLE schedule WITH LOGIN PASSWORD 'schedule';" \
  -c "CREATE DATABASE schedule OWNER schedule;"
# (or wipe and let initdb run: docker compose ... down -v && up -d)

# 2. services (separate shells), JAVA_HOME must point at JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :services:identity-service:bootRun
./gradlew :services:service-catalog-service:bootRun
./gradlew :services:schedule-service:bootRun
./gradlew :services:incident-service:bootRun
./gradlew :services:integration-service:bootRun

# 3. bootstrap a tenant + issue a key, then ingest with it
OID=$(curl -s -XPOST localhost:8083/v1/organizations -H 'Content-Type: application/json' \
  -d '{"name":"Acme","slug":"acme"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
UID=$(curl -s -XPOST localhost:8083/v1/users -H 'Content-Type: application/json' \
  -d '{"email":"a@acme.io","displayName":"Alice"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -XPOST localhost:8083/v1/organizations/$OID/memberships -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$UID\",\"role\":\"OWNER\"}"
KEY=$(curl -s -XPOST localhost:8083/v1/organizations/$OID/integration-keys -H "X-User-Id: $UID" \
  -H 'Content-Type: application/json' -d '{"name":"grafana-prod"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["key"])')

curl -XPOST "localhost:8081/v1/integrations/$KEY/events/backend-critical" \
  -H 'Content-Type: application/json' \
  -d '{"messageType":"CRITICAL","entityId":"payment-api-5xx","source":"grafana","severity":"CRITICAL"}'

curl localhost:8082/v1/incidents
```

## 8. Next sprint

Phases 1 + 4 complete. Recommended: **Phase 5 - Escalation Engine** (escalation policies/rules,
Kafka-driven incident.triggered handling, notify-in-order with schedule lookups, cancel on ACK/RESOLVE).
It ties together incident-service, schedule-service, and the (future) notification-service. Alternative:
finish **Phase 3** (separate Alert table + lifecycle REST commands). Decide at sprint start.
