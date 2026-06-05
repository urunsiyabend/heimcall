# Heimcall - Current State

Living document. Update at the end of every sprint. Reflects what is actually built and running, not what is planned. Plan lives in `01-development-plan.md`; this file is the source of truth for "where are we now".

Last updated: 2026-06-05 (end of Sprint 1)

## 1. Snapshot

| Area | State |
| --- | --- |
| Architecture | Microservices-first monorepo, Gradle multi-project |
| Build | `./gradlew build` green on Java 21 |
| Runtime verified | Sprint 1 vertical slice (webhook -> incident -> dedup -> recovery) |
| Last sprint | Sprint 1 - alert ingestion + incident lifecycle core |

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
  integration-service  webhook ingestion -> publishes alert.received.v1
  incident-service   consumes alert.received.v1 -> dedup -> incident + timeline -> REST
deploy/
  docker-compose     postgres(5433), kafka(9092 KRaft), redis(6379), mailhog(1025/8025)
```

## 4. Implemented behavior

### integration-service (port 8081)
- `POST /v1/integrations/{integrationKey}/events/{routingKey}` -> 202 accepted + eventId
- Validates payload (`messageType`, `entityId`, `source` required)
- Normalizes to `AlertReceivedEvent`, `dedupKey = source + ":" + entityId`
- Publishes to Kafka topic `alert.received.v1` (key = dedupKey)

### incident-service (port 8082)
- `@KafkaListener` on `alert.received.v1`
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

## 5. Verified end-to-end (Sprint 1)

- 2x CRITICAL same dedupKey -> 1 incident, alertCount=2 (dedup works)
- RECOVERY -> incident RESOLVED
- timeline -> TRIGGER, DUPLICATE, RESOLVE

## 6. Known gaps / deliberately deferred

| Gap | Where it is addressed |
| --- | --- |
| Producer is fire-and-forget (silent loss if Kafka down) | Phase 3.5 |
| Consumer not idempotent (redelivery inflates alertCount) | Phase 3.5 - correctness, prioritize early |
| No retry / dead-letter topic | Phase 3.5 |
| RawInboundEvent not stored | Phase 3.5 |
| Real integration-key validation (org is a fixed dev placeholder) | Phase 1 (identity) |
| Separate Alert table (count lives on incident) | Phase 3 |
| Idempotency on lifecycle commands (Idempotency-Key) | Phase 3.5 |
| Notifications, escalation, schedules | Phases 4-6 |
| Tests (unit / integration / contract) | ongoing, none yet |
| api-gateway not exercised (Sprint 1 hit services directly) | next |

## 7. How to run locally

```bash
# 1. infra
docker compose -f deploy/docker-compose/docker-compose.yml up -d

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

Recommended: Phase 3.5 - Reliability and Idempotency (close the Kafka-failure gaps above before adding more consumers).
