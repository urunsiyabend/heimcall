# Heimcall - Current State

Living document. Update at the end of every sprint. Reflects what is actually built and running, not what is planned. Plan lives in `01-development-plan.md`; this file is the source of truth for "where are we now".

Last updated: 2026-06-05 (end of Sprint 2)

## 1. Snapshot

| Area | State |
| --- | --- |
| Architecture | Microservices-first monorepo, Gradle multi-project |
| Build | `./gradlew build` green on Java 21 |
| Runtime verified | Sprint 2 reliability slice (raw store + acks=all, idempotent consumer, DLT, poison-pill) |
| Last sprint | Sprint 2 - Phase 3.5 reliability and idempotency |

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
  api-gateway        Spring Cloud Gateway, routes /v1/integrations -> 8081, /v1/incidents -> 8082
  integration-service  webhook ingestion -> stores raw -> publishes alert.received.v1 (acks=all, confirmed)
  incident-service   consumes alert.received.v1 -> idempotency guard -> dedup -> incident + timeline -> REST; DLT on failure
deploy/
  docker-compose     postgres(5433, dbs: incident + integration), kafka(9092 KRaft), redis(6379), mailhog(1025/8025)
```

Databases (one per service, single PG instance): `incident`, `integration`. The `integration`
db + role are created by `deploy/docker-compose/initdb/01-create-databases.sql` on a fresh data
volume; on an existing volume create them manually (see section 7).

## 4. Implemented behavior

### integration-service (port 8081)
- `POST /v1/integrations/{integrationKey}/events/{routingKey}` -> 202 accepted + eventId
- Validates payload (`messageType`, `entityId`, `source` required)
- Normalizes to `AlertReceivedEvent`, `dedupKey = source + ":" + entityId`
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

## 5. Verified end-to-end (Sprint 2)

- normal CRITICAL -> incident TRIGGERED, raw_inbound_event `PUBLISHED`
- same `eventId` delivered twice -> alertCount stays 1, one `processed_event` row (idempotent, no inflation)
- malformed payload -> lands in `alert.received.v1.DLT`, consumer keeps processing the next valid event
- Kafka stopped during ingest -> HTTP 503, raw_inbound_event `FAILED` (no silent loss)

(Sprint 1 still holds: 2x CRITICAL same dedupKey -> alertCount=2; RECOVERY -> RESOLVED; timeline TRIGGER/DUPLICATE/RESOLVE.)

## 6. Known gaps / deliberately deferred

| Gap | Where it is addressed |
| --- | --- |
| Real integration-key validation (org is a fixed dev placeholder) | Phase 1 (identity) |
| Separate Alert table (count lives on incident) | Phase 3 |
| Idempotency-Key on inbound HTTP ingest (event-id idempotency on consumer done) | later |
| `processed_event` ledger has no TTL/pruning (grows unbounded) | later (Redis key w/ TTL option in plan) |
| Transactional outbox (raw store + sync confirm used instead) | optional, deferred |
| FAILED raw events have no automatic republisher (manual replay only) | later |
| Notifications, escalation, schedules | Phases 4-6 |
| Tests (unit / integration / contract) | ongoing, none yet |
| api-gateway not exercised (hit services directly) | next |

## 7. How to run locally

```bash
# 1. infra
docker compose -f deploy/docker-compose/docker-compose.yml up -d

# 1b. on an EXISTING data volume the integration db/role are not auto-created; create once:
docker exec heimcall-local-postgres-1 psql -U incident \
  -c "CREATE ROLE integration WITH LOGIN PASSWORD 'integration';" \
  -c "CREATE DATABASE integration OWNER integration;"
# (or wipe and let initdb run: docker compose ... down -v && up -d)

# 2. services (separate shells), JAVA_HOME must point at JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :services:incident-service:bootRun
./gradlew :services:integration-service:bootRun

# 3. fire a test alert
curl -XPOST localhost:8081/v1/integrations/dev-key/events/backend-critical \
  -H 'Content-Type: application/json' \
  -d '{"messageType":"CRITICAL","entityId":"payment-api-5xx","source":"grafana","severity":"CRITICAL"}'

curl localhost:8082/v1/incidents
```

## 8. Next sprint

Recommended: Phase 1 - Identity, Teams, and Service Catalog (real org/integration-key resolution
replaces the dev placeholder), or Phase 4 - Scheduling. Reliability path (Phase 3.5) is now closed.
