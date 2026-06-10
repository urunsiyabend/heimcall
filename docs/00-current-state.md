# Heimcall - Current State

Living document. Update at the end of every sprint. Reflects what is actually built and running, not what is planned. Plan lives in `01-development-plan.md`; this file is the source of truth for "where are we now".

Last updated: 2026-06-10 (Sprint 9 - Phase 7 tickets 1-3.1: notification feedback + real JWT auth)

## 1. Snapshot

| Area | State |
| --- | --- |
| Architecture | Microservices-first monorepo, Gradle multi-project |
| Build | `./gradlew build` green on Java 21 |
| Runtime verified | Sprint 9 Phase-7 tickets 1-3.1: full loop under real JWT through the gateway (register/login -> token -> CRUD across all services -> ingest -> incident TRIGGER + NOTIFIED), JWT enforced (401 no-token, refresh-as-access rejected, client X-User-Id spoof stripped), incident queries tenant-scoped (cross-org 403) |
| Last sprint | Sprint 9 - Phase 7 tickets 1-3.1 (notification->timeline, real JWT auth across all services + gateway, tenant-scoped incident queries) |
| Tests | First automated test: `OnCallCalculatorTest` (rotation math) |
| Auth | Real JWT (HS256, `libs/common-security`): identity issues access+refresh; every service validates Bearer and derives `X-User-Id` from the verified token. Header-context stub retired. |

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
  common-events      event records (Alert*, Incident triggered/acknowledged/resolved/canceled, Notification*), Topics constants
  common-security    HS256 JWT auto-config: JwtSupport (issue/verify), JwtAuthenticationFilter (Bearer -> principal + derives X-User-Id), stateless SecurityFilterChain. Added by every service via one dependency.
  test-support       Testcontainers singletons (PG + Kafka) - not yet used
services/
  api-gateway        Spring Cloud Gateway, routes -> catalog 8084, schedule 8085, escalation 8086, identity 8083 (incl. /v1/auth/**), integration 8081, incident 8082; CORS for the UI origin; forwards Authorization (validation is per-service)
  identity-service   auth (register/login/refresh/me, JWT) + org/user/team/membership CRUD + integration-key issue/resolve + internal lookups, incl. team-member list (port 8083)
  service-catalog-service  monitored services CRUD + team ownership + tags + routing-key + validated escalation-policy + routing lookup (port 8084)
  schedule-service   on-call schedules, daily/weekly rotations, overrides, timezone-aware on-call resolution + internal on-call (port 8085)
  integration-service  webhook ingestion -> resolves key via identity -> stores raw -> publishes alert.received.v1 (acks=all)
  incident-service   consumes alert.received.v1 -> Event->Alert->Incident (alert dedup aggregate + occurrence log) -> routing/policy stamp -> timeline; lifecycle REST commands ACK/resolve/cancel; publishes incident.* (incl. canceled); DLT on failure
  escalation-service consumes incident.triggered -> runs policy (level tasks, worker, repeat) -> resolves targets -> notification.requested; cancels on ACK/RESOLVE (port 8086)
  notification-service consumes notification.requested -> fans out to recipient contact methods -> delivers (email/webhook) with bounded retry -> notification.delivered/failed (port 8087)
deploy/
  docker-compose     postgres(5433, dbs: incident + integration + identity + catalog + schedule + escalation + notification), kafka(9092 KRaft), redis(6379), mailhog(1025/8025)
```

Databases (one per service, single PG instance): `incident`, `integration`, `identity`, `catalog`, `schedule`, `escalation`, `notification`.
The non-default db/role are created by `deploy/docker-compose/initdb/01-create-databases.sql` on a fresh data
volume; on an existing volume create them manually (see section 7).

Ports: api-gateway 8080, integration 8081, incident 8082, identity 8083, service-catalog 8084, schedule 8085, escalation 8086, notification 8087.

## 4. Implemented behavior

### identity-service (port 8083)
- **Auth (Phase 7 tickets 2-3, real JWT)**: `POST /v1/auth/register` (BCrypt password), `POST /v1/auth/login`
  -> `{accessToken, refreshToken, user}` (access 60m, refresh 30d, HS256 via `common-security`),
  `POST /v1/auth/refresh`, `GET /v1/auth/me` (user + memberships). `password_hash` on `app_user` (Flyway V2).
- Tenant isolation: callers authenticate with a Bearer JWT; the shared filter validates it and derives
  `X-User-Id` from the verified subject, so `TenantGuard` (membership-in-`orgId`, else 403) now runs off a
  signed token, not a client-trusted header. The old header-context stub is retired.
- Persistence (Flyway V1-V2): `organization`, `app_user` (+ `password_hash`), `membership` (org role),
  `team`, `team_member`, `integration_key`
- Org/User: `POST/GET /v1/organizations`, `POST/GET /v1/users`
- Membership: `POST/GET /v1/organizations/{orgId}/memberships` - the **first** membership of an org is a
  bootstrap (no caller header needed); every later add must come from an existing member
- Teams: `POST/GET /v1/organizations/{orgId}/teams` and `.../teams/{teamId}/members`
- Integration keys: `POST/GET /v1/organizations/{orgId}/integration-keys` - plaintext (`hc_` + random)
  returned **once**; only a SHA-256 hash + prefix stored. `integrationId` is the stable id stamped on events.
- Resolve (internal): `POST /v1/integration-keys/resolve {key}` -> `{organizationId, integrationId, name}`,
  401 if unknown/inactive
- Internal lookups (service-to-service, not on the gateway): `GET /v1/internal/organizations/{orgId}/members/{userId}`
  (204/404), `GET /v1/internal/organizations/{orgId}/teams/{teamId}` (team-in-org check),
  `GET /v1/internal/organizations/{orgId}/teams/{teamId}/members` (member userIds, for escalation TEAM fan-out).

### service-catalog-service (port 8084)
- Tenant rules it cannot check locally (membership, team-in-org) are enforced via identity's internal
  API (`IdentityClient`): non-member -> 403, foreign-org team -> 409, identity unreachable -> 503.
- Persistence (Flyway V1): `monitored_service`, `service_tag`
- Services: `POST/GET/PUT/DELETE /v1/organizations/{orgId}/services` - slug unique per org
- Ownership: `PUT .../services/{id}/owner {teamId}` - team verified to belong to the org (single owning team)
- Escalation policy: `PUT .../services/{id}/escalation-policy {escalationPolicyId}` - **validated** against
  escalation-service (`EscalationClient`): unknown/foreign policy -> 409, escalation unreachable -> 503
- Routing key: `PUT .../services/{id}/routing-key {routingKey}` - maps an inbound alert routingKey to this
  service, unique per org (`routing_key`, Flyway V2)
- Tags: `PUT/GET/DELETE .../services/{id}/tags` - key/value, unique per (service, key)
- Internal routing lookup (service-to-service): `GET /v1/internal/organizations/{orgId}/routing?routingKey=`
  -> `{serviceId, escalationPolicyId, ownerTeamId}` (404 if no service carries that key). Used by incident-service.

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
- Internal on-call (service-to-service, no user header): `GET /v1/internal/organizations/{orgId}/schedules/{id}/on-call`
  -> 200 `{userId, source, rotationId}` or 204 if no one on call. Used by escalation-service to resolve SCHEDULE targets.

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
  change; a redelivered event whose id is already recorded is a no-op
- Resilience: `ErrorHandlingDeserializer` (poison-pill safe) + `DefaultErrorHandler` with bounded
  retry (1 + 2 retries, 1s apart) then `DeadLetterPublishingRecoverer` -> `alert.received.v1.DLT`
- Domain flow `Event -> Alert -> Incident` (glossary §2). Every inbound signal is logged as an
  `alert_occurrence` under the deduplicated `alert` aggregate; an actionable alert opens an incident:
  - **Alert** = dedup aggregate: at most one OPEN alert per `(org, dedupKey)` (partial unique index
    `ux_alert_open_dedup`); repeats bump `occurrence_count` + `last_seen_at`. `incident_id` is nullable
    (an alert MAY exist without an incident).
  - **alert_occurrence** = immutable per-signal log (one row per inbound event: messageType, severity,
    occurred_at, received_at, event_id -> integration raw event).
  - CRITICAL / WARNING -> open alert (or dedup onto the open one) and, on a new alert, open an incident
    + DUPLICATE timeline on a repeat. RECOVERY -> close the alert + resolve its incident.
    ACKNOWLEDGEMENT -> acknowledge the alert + its TRIGGERED incident. INFO -> record a (no-incident) alert.
- Lifecycle REST commands (operator actions): `POST /v1/incidents/{id}/{acknowledge,resolve,cancel}`
  - member-gated via identity (`IdentityClient.requireMember`, `X-User-Id`): non-member -> 403, identity
    unreachable -> 503; idempotent (no-op when already in the target state); illegal transition -> 409
  - each appends a timeline event (records the actor) and publishes the matching domain event; the
    linked OPEN/ACK alerts are transitioned to follow the incident (ACK -> ACKNOWLEDGED, resolve/cancel -> CLOSED)
  - `reassign` not built (needs `IncidentAssignment`); cancel emits the new `incident.canceled.v1`
- Routing/ownership stamping (Phase 5): on a new trigger, `CatalogClient` resolves the routingKey to
  `{serviceId, escalationPolicyId}` and stamps them (Flyway V3). Best-effort: a missing mapping / catalog
  error / unreachable catalog still creates the incident (`NO_POLICY` timeline event); core never depends on it.
- Domain events: publishes `incident.triggered/acknowledged/resolved/canceled.v1` via a
  `@TransactionalEventListener` (`AFTER_COMMIT`) so a rolled-back change emits no ghost event. At-least-once.
- Persistence (Flyway V1-V4): `incident` (`alert_count` dropped in V4; still carries `routing_key`,
  `service_id`, `escalation_policy_id`, `dedup_key`/`source`/`external_entity_id`), `incident_timeline_event`,
  `alert`, `alert_occurrence`
  - Incident-level partial unique index `(organization_id, dedup_key) WHERE status IN (TRIGGERED, ACKNOWLEDGED)`
    kept as a backstop alongside the alert-level open-dedup index.
- Notification feedback loop (Phase 7 ticket 1): a second `@KafkaListener` (group
  `incident-service.notification-consumer`, dedicated type-header container factory) consumes
  `notification.delivered.v1`/`notification.failed.v1` and appends a `NOTIFIED`/`NOTIFY_FAILED`
  timeline event. Idempotent on event id (shared `processed_event` ledger), tenant-checked against
  the incident's org; unknown/foreign incident -> warn + no-op. Same `ErrorHandlingDeserializer`+DLT.
- Queries (tenant-scoped, Phase 7 ticket 3.1): `GET /v1/incidents?organizationId=&status=` (member-gated,
  org-filtered), `GET /v1/incidents/{id}`, `.../timeline`, `.../alerts`, `.../alerts/{alertId}/occurrences`
  - each per-incident read derives the org from the incident and enforces caller membership: 404 if absent,
    403 if not a member (closes the prior gap where queries returned incidents across all tenants).

### escalation-service (port 8086)
- Tenant rules via identity internal API (`IdentityClient`): caller membership (403), USER/TEAM targets in org (409).
- Persistence (Flyway V1): `escalation_policy` (repeat_count), `escalation_rule` (level, delay_seconds),
  `escalation_rule_target` (USER|SCHEDULE|TEAM, target_id), `escalation_task`, `escalation_incident`
  (incident context snapshot), `processed_event` (idempotency ledger).
- CRUD: `POST/GET/PUT/DELETE /v1/organizations/{orgId}/escalation-policies` and nested
  `.../rules` (level unique per policy) and `.../rules/{ruleId}/targets`.
- Engine: consumes `incident.triggered.v1` -> materializes one `EscalationTask` per level per repeat round,
  scheduled at `triggeredAt + delaySeconds`. Idempotent on event id and on "tasks already exist for incident".
- Worker (`@Scheduled`, poll-interval-ms): fires due PENDING tasks in their own tx; resolves targets
  (USER direct, SCHEDULE -> schedule internal on-call, TEAM -> identity member list) -> publishes one
  `notification.requested.v1` per recipient; marks EXECUTED. A dependency error leaves the task PENDING (retried).
- Cancellation: consumes `incident.acknowledged/resolved/canceled.v1` -> cancels still-PENDING tasks for the incident.
- Internal: `GET /v1/internal/organizations/{orgId}/escalation-policies/{policyId}` (204/404) - policy existence
  check for service-catalog validation.
- Resilience: `ErrorHandlingDeserializer` + bounded retry + DLT (mirrors incident-service Phase 3.5).

### notification-service (port 8087)
- Tenant rules via identity internal API (`IdentityClient`): caller membership (403), contact-method target user in org (409).
- Persistence (Flyway V1): `contact_method` (org+user+channel+destination, enabled), `notification_request`
  (PK = request eventId, doubles as idempotency ledger), `notification_delivery` (per contact method,
  status/attempts/backoff, unique per (request, contact method)).
- Contact methods (notification context owns them, plan 4.7): `POST/GET/PUT/DELETE
  /v1/organizations/{orgId}/users/{userId}/contact-methods`. `channel` in {EMAIL, WEBHOOK}; `enabled`
  doubles as a basic per-channel preference (PUT toggles it).
- Consumer: `@KafkaListener` on `notification.requested.v1` -> persists the request (idempotent on
  eventId) -> fans out to the recipient's **enabled** contact methods -> one PENDING `notification_delivery`
  each. No enabled contact method -> nothing to deliver (logged).
- Worker (`@Scheduled`, poll-interval-ms): fires due PENDING deliveries in their own tx. On success ->
  DELIVERED + `notification.delivered.v1`. On failure -> retry with backoff (`attempts * retry-backoff-ms`)
  up to `max-attempts` (default 3), then FAILED + `notification.failed.v1`. Delivery state is tracked
  separately from incident/request state (engineering rule).
- Senders: `EmailSender` (Spring `JavaMailSender` -> SMTP, mailhog locally) and `WebhookSender`
  (HTTP POST of a JSON body, bounded connect/read timeout; non-2xx throws -> retry).
- Visibility: `GET /v1/organizations/{orgId}/deliveries[?incidentId=&status=]` so failed messages are
  operationally visible.
- Resilience: `ErrorHandlingDeserializer` + bounded retry + DLT (mirrors incident-service Phase 3.5).

### Kafka topics in use
- `alert.received.v1` (integration-service -> incident-service) + `alert.received.v1.DLT`
- `incident.triggered.v1` / `incident.acknowledged.v1` / `incident.resolved.v1` / `incident.canceled.v1` (incident-service -> escalation-service)
- `notification.requested.v1` (escalation-service -> notification-service) + `notification.requested.v1.DLT`
- `notification.delivered.v1` / `notification.failed.v1` (notification-service -> incident-service: appended to the incident timeline as NOTIFIED/NOTIFY_FAILED)

## 5. Verified end-to-end (Sprint 9)

Live run, full 6-service path + real Kafka/Postgres (Phase 7 ticket 1):
- CRITICAL ingest -> incident TRIGGER -> escalation runs policy (USER target, level 1 delay 0) ->
  `notification.requested.v1` -> EMAIL delivered (mailhog) -> `notification.delivered.v1` ->
  incident-service consumed it -> `NOTIFIED` timeline event on the incident (loop closed).

Sprint 8 run, incident + integration + identity + escalation + real Kafka/Postgres (Phase 3 finish):
- CRITICAL ingest -> alert OPEN + 1 occurrence + incident TRIGGERED
- duplicate CRITICAL (same dedupKey) -> alert `occurrence_count=2`, 2 `alert_occurrence` rows, DUPLICATE timeline (incident not double-counted)
- manual ACK (`POST /incidents/{id}/acknowledge`, `X-User-Id`) -> incident + linked alert ACKNOWLEDGED; repeat -> idempotent 200
- manual resolve -> incident RESOLVED + alert CLOSED; a follow-up cancel on the RESOLVED incident -> 409
- non-member ACK -> 403
- RECOVERY -> alert CLOSED + incident RESOLVED
- INFO -> alert recorded with `incident_id` NULL (alert-without-incident)
- manual cancel -> incident CANCELED + alert CLOSED; escalation consumed `incident.canceled.v1` (processed-event ledger advanced)

(Sprint 7 still holds: contact-method CRUD, request fan-out, EMAIL -> mailhog DELIVERED, WEBHOOK bounded-retry -> FAILED,
delivered/failed events, idempotent on request eventId.
Sprint 6 still holds: escalation policy/rule/target CRUD, routing->policy stamping, level tasks + worker, cancel on ACK/RESOLVE,
SCHEDULE target -> on-call. Sprint 5: schedules, rotations, overrides, timezone-aware on-call; `OnCallCalculatorTest` green.
Sprint 4: monitored services, ownership, tags, gateway. Sprint 3: key issue/resolve, real tenant on ingest.
Sprint 2: idempotency, DLT, broker-outage 503. Sprint 1: dedup, recovery, timeline.)

## 6. Known gaps / deliberately deferred

| Gap | Where it is addressed |
| --- | --- |
| `ApiKey` (user/programmatic key) - only `IntegrationKey` built | Phase 1a deferred list |
| ~~Real JWT + Spring Security (header-context stub)~~ DONE: HS256 JWT across all services (`common-security`) + identity auth | Phase 7 tickets 2-3 |
| JWT secret is a shared HS256 dev default in yaml; no rotation, no RS256/JWKS, no refresh-token rotation/revocation | later |
| Redis cache for integration-key resolution + cross-service tenant checks (sync REST each call) | later |
| RBAC beyond membership (role stored, not enforced per-action) | later |
| Integration-key revoke/rotate endpoints (revoke on entity, not exposed) | later |
| Single owning team only; multi-team ownership table | later |
| Rotation length DAILY/WEEKLY only; custom N-day rotations | later |
| DST transition-instant edge not tested; on-call has no Redis cache | later |
| `reassign` endpoint + `IncidentAssignment` model | later |
| Incident not fully leaned: still carries `dedup_key`/`source`/`external_entity_id` + backstop open-dedup index | later |
| `processed_event` ledger has no TTL/pruning (grows unbounded) | later |
| Transactional outbox; incident.* + notification.* publish is at-least-once, no outbox | later |
| Routing is a flat `routingKey -> service` map; no Opsgenie-style rule engine (labels/severity/time) | later |
| TEAM target fan-out built (identity member list) but not runtime-exercised (USER + SCHEDULE were) | ongoing |
| Telegram channel (only EMAIL + WEBHOOK built) | later |
| Notification preference is just per-contact-method `enabled`; no cooldown / per-incident throttle / quiet hours | later |
| Contact-method `destination` not format-validated (email/url); no verification step | later |
| No Redis notification-cooldown cache; delivery worker has no distributed lock (single instance assumed) | later |
| ~~`notification_delivered/failed` events have no consumer yet~~ DONE: incident-service consumes both -> incident timeline (NOTIFIED/NOTIFY_FAILED) | Phase 7 ticket 1 |
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
  -c "CREATE DATABASE schedule OWNER schedule;" \
  -c "CREATE ROLE escalation WITH LOGIN PASSWORD 'escalation';" \
  -c "CREATE DATABASE escalation OWNER escalation;" \
  -c "CREATE ROLE notification WITH LOGIN PASSWORD 'notification';" \
  -c "CREATE DATABASE notification OWNER notification;"
# (or wipe and let initdb run: docker compose ... down -v && up -d)

# 2. services (separate shells), JAVA_HOME must point at JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :services:identity-service:bootRun
./gradlew :services:service-catalog-service:bootRun
./gradlew :services:schedule-service:bootRun
./gradlew :services:incident-service:bootRun
./gradlew :services:integration-service:bootRun
./gradlew :services:escalation-service:bootRun
./gradlew :services:notification-service:bootRun

# 3. register (real JWT now) -> token; create org + bootstrap membership; issue a key; ingest; read incidents.
#    All user-facing calls go through the gateway (8080) with `Authorization: Bearer`. Ingest uses the key.
ACC=$(curl -s -XPOST localhost:8080/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"a@acme.io","displayName":"Alice","password":"supersecret1"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
UID=$(curl -s localhost:8080/v1/auth/me -H "Authorization: Bearer $ACC" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["user"]["id"])')
OID=$(curl -s -XPOST localhost:8080/v1/organizations -H "Authorization: Bearer $ACC" \
  -H 'Content-Type: application/json' -d '{"name":"Acme","slug":"acme"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -XPOST localhost:8080/v1/organizations/$OID/memberships -H "Authorization: Bearer $ACC" \
  -H 'Content-Type: application/json' -d "{\"userId\":\"$UID\",\"role\":\"OWNER\"}"
KEY=$(curl -s -XPOST localhost:8080/v1/organizations/$OID/integration-keys -H "Authorization: Bearer $ACC" \
  -H 'Content-Type: application/json' -d '{"name":"grafana-prod"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["key"])')

# ingest (integration key auth, no JWT). Needs a service with routingKey=backend-critical for routing/policy.
curl -XPOST "localhost:8080/v1/integrations/$KEY/events/backend-critical" \
  -H 'Content-Type: application/json' \
  -d '{"messageType":"CRITICAL","entityId":"payment-api-5xx","source":"grafana","severity":"CRITICAL"}'

# incident queries are tenant-scoped: pass organizationId + the Bearer token.
curl "localhost:8080/v1/incidents?organizationId=$OID" -H "Authorization: Bearer $ACC"
```

(Every service needs `heimcall.jwt.secret`; a shared dev default is baked into each `application.yml`,
overridable via `HEIMCALL_JWT_SECRET`. The api-gateway also needs `HEIMCALL_UI_ORIGIN` for CORS, default
`http://localhost:5173`.)

## 8. Next sprint

Phases 1 + 3 + 4 + 5 + 6 complete; the trigger->notify loop is closed end to end and incidents have
real lifecycle REST commands. **Phase 7 in progress** (see plan ticket breakdown):
- Ticket 1 DONE - notification.delivered/failed -> incident timeline (NOTIFIED/NOTIFY_FAILED).
- Ticket 2 DONE - real JWT auth: `libs/common-security` + identity register/login/refresh/me.
- Ticket 3 DONE - JWT propagated to all 6 services + gateway CORS + `/v1/auth` route.
- Ticket 3.1 DONE - tenant-scoped incident queries + ERROR-dispatch security fix.
- **Ticket 4 TODO** - SSE incident stream `GET /v1/incidents/stream` (per-org `SseEmitter` registry fed by
  the `IncidentDomainEvents` listener; auth via `access_token` query param since EventSource can't set
  headers). Plumbing reviewed: feed off `IncidentDomainEvents.{Triggered,Acknowledged,Resolved,Canceled}`
  (in-process app events, also forwarded to Kafka by `IncidentEventPublisher` AFTER_COMMIT).
- **Ticket 5 TODO** - React + Vite + TS UI in `web/`: login/register, incident list/detail/timeline,
  ACK/resolve/cancel, SSE live updates.

Operational note: only incident-service is currently running the ERROR-dispatch `common-security` fix
(ticket 3.1); rebuild+restart all services to propagate it (happy paths unaffected; only 4xx error bodies
were masked as 401 on the others).

Then **Phase 8 - Observability** and cross-cutting hardening (transactional outbox, Redis caches/cooldown,
`processed_event` TTL, `reassign` + `IncidentAssignment`, JWT secret rotation/RS256).
