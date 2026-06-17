# Incident Management / On-call Platform - Development Plan

Version: 0.1
Owner: Siyabend Ürün
Stack: Spring Boot, Spring Cloud, Kafka, Redis, PostgreSQL, Kubernetes

## 1. Product Direction

We are building a mini PagerDuty / Opsgenie / Splunk On-Call style platform. The product receives operational signals from external systems, normalizes them into alerts, creates or updates incidents, routes incidents to the correct service/team, notifies the current on-call responder, escalates if no one acknowledges, and preserves a timeline of every meaningful action.

This plan intentionally follows a textbook architecture path:

1. Understand the domain and define ubiquitous language.
2. Model bounded contexts and map them directly to independent microservices.
3. Build separate Spring Boot services from day one, each owning its database/schema and communicating via REST and Kafka events.
4. Use event-driven architecture for lifecycle actions.
5. Use Kubernetes, Kafka, Redis, and Spring Cloud for production-like distributed-system behavior.

Architecture decision: this project goes microservices-first. We do NOT start as a modular monolith. Each bounded context in section 4 is implemented as its own deployable service with its own datastore from the first sprint. Trade-off accepted: more upfront operational complexity (multiple services, Kafka contracts, distributed debugging) in exchange for clean service boundaries and a production-like distributed system as a learning goal.

## 2. Inspiration from Existing Systems

### PagerDuty-inspired principles

- A service is connected to an escalation policy.
- Escalation policies automate incident assignment.
- Escalation policies notify one target at a time until someone acknowledges.
- Schedules define who is on call.

### Opsgenie-inspired principles

- Alert management is a first-class concern.
- Deduplication prevents repeated alerts from becoming repeated incidents.
- Escalation depends on alert or incident state, especially whether it is acknowledged or closed.
- Teams, schedules, routing rules, and escalation policies work together.

### Splunk On-Call / VictorOps-inspired principles

- External integrations should accept generic JSON events.
- Events may trigger, acknowledge, update, or recover an incident.
- External identity fields such as entity id should map to deduplication keys.
- Routing keys should direct payloads toward the correct service/team/policy.

## 3. Architecture Principles

### 3.1 Core principles

- Domain-first design.
- Explicit lifecycle states.
- Idempotent commands.
- Event-driven integration between bounded contexts.
- Append-only timeline for observability and audit.
- Async processing for incident actions where appropriate.
- Resilience around notification providers.
- Tenant isolation from day one.

### 3.2 Non-negotiable engineering rules

- Every external payload must be normalized before domain processing.
- Every lifecycle command must be idempotent.
- Every incident action must create a timeline event.
- Escalation workers must re-check incident state before notifying.
- Notification delivery must be tracked separately from incident state.
- Kafka consumers must be retry-safe.
- Failed messages must go to a dead-letter topic.
- Distributed scheduled work must be lock-safe.
- Cache must never be the source of truth.

## 4. Proposed Bounded Contexts

## 4.1 Identity & Access Context

Responsibilities:

- Organizations
- Users
- Roles
- Memberships
- API keys
- Integration keys
- Authentication and authorization

Suggested service: `identity-service`

Main entities:

- Organization
- User
- Membership
- Role
- ApiKey
- IntegrationKey

## 4.2 Service Catalog Context

Responsibilities:

- Monitored services
- Service ownership
- Team-service relationship
- Service escalation policy assignment

Suggested service: `service-catalog-service`

Main entities:

- MonitoredService
- ServiceOwnership
- ServiceTag

## 4.3 Integration Context

Responsibilities:

- Generic webhook ingestion
- Provider-specific integrations
- Payload normalization
- Routing key resolution
- Validation and authentication of inbound alerts

Suggested service: `integration-service`

Main entities:

- Integration
- RoutingKey
- RawInboundEvent
- NormalizedAlertEvent

## 4.4 Alert & Incident Context

Responsibilities:

- Alert creation
- Deduplication
- Incident lifecycle
- Acknowledge, resolve, cancel, reopen rules
- Assignment
- Timeline production

Suggested service: `incident-service`

Main entities:

- Alert
- Incident
- IncidentTimelineEvent
- IncidentAssignment

## 4.5 On-call Scheduling Context

Responsibilities:

- Schedules
- Rotations
- Participants
- Overrides
- Current on-call calculation

Suggested service: `schedule-service`

Main entities:

- OnCallSchedule
- ScheduleRotation
- RotationParticipant
- ScheduleOverride

## 4.6 Escalation Context

Responsibilities:

- Escalation policies
- Escalation steps/rules
- Escalation task scheduling
- Escalation execution
- Repeat logic

Suggested service: `escalation-service`

Main entities:

- EscalationPolicy
- EscalationRule
- EscalationTask
- EscalationExecution

## 4.7 Notification Context

Responsibilities:

- Notification requests
- User contact methods
- Channel preferences
- Provider integrations
- Delivery attempts
- Retry and failure handling

Suggested service: `notification-service`

Main entities:

- NotificationRequest
- NotificationDelivery
- ContactMethod
- NotificationChannel

## 4.8 Timeline & Audit Context

Responsibilities:

- Incident timeline
- Audit log
- User actions
- System actions
- Immutable event history

Suggested service: can start inside `incident-service`, later split into `timeline-service`.

## 5. Suggested Microservice Topology

MVP topology:

```text
api-gateway
identity-service
integration-service
incident-service
schedule-service
escalation-service
notification-service
```

Later topology:

```text
api-gateway
identity-service
service-catalog-service
integration-service
alert-service
incident-service
schedule-service
escalation-service
notification-service
timeline-service
reporting-service
```

## 6. Technology Choices

## 6.1 Spring Boot

Use Spring Boot for each service.

Locked baseline:

- Java 21
- Spring Boot 3.3.x
- Gradle multi-project; shared config via a root `subprojects {}` block (not `build-logic` convention plugins — kept simple, extract later if it grows)
- Spring Security
- Spring Data JPA
- Spring Validation
- Spring Actuator
- Flyway or Liquibase
- Testcontainers

## 6.2 Spring Cloud Gateway

Use as the external API entry point.

Responsibilities:

- Routing
- Authentication filter
- Rate limiting
- Request correlation id
- CORS
- Basic request logging

## 6.3 Spring Cloud Stream + Kafka

Use Kafka as the event backbone.

Responsibilities:

- Incident lifecycle events
- Notification requests
- Escalation tasks
- Timeline events
- Integration payload events

Topic examples:

```text
alert.received.v1
incident.triggered.v1
incident.acknowledged.v1
incident.resolved.v1
escalation.requested.v1
notification.requested.v1
notification.delivered.v1
notification.failed.v1
timeline.event-created.v1
```

## 6.4 Redis

Use Redis for ephemeral and coordination-heavy data.

Use cases:

- API rate limiting counters
- Idempotency key cache
- Short-lived deduplication acceleration
- Distributed locks for scheduled workers
- Current on-call calculation cache
- Notification cooldown cache

Do not use Redis as the source of truth for incidents, alerts, schedules, or escalations.

## 6.5 PostgreSQL

Use PostgreSQL as the system of record.

Pattern (microservices-first):

- Database per service. Each service owns its own logical database and migrations.
- For local dev, run one PostgreSQL instance with one database per service (cheap parity, still no shared tables).
- Never share tables directly between services. Cross-service reads happen via REST or Kafka events, never via the other service's tables.

## 6.6 Kubernetes

Use Kubernetes for production-like deployment.

Core resources:

- Deployment
- Service
- ConfigMap
- Secret
- Ingress
- HorizontalPodAutoscaler
- PodDisruptionBudget
- NetworkPolicy
- ServiceAccount

Recommended local setup:

- kind or k3d
- Helm charts
- Skaffold or Tilt for inner loop

## 7. High-level Runtime Flow

### 7.1 Trigger flow

```text
External Monitoring System
  -> POST /v1/integrations/{integrationKey}/events/{routingKey}
  -> API Gateway
  -> integration-service validates and normalizes payload
  -> publishes alert.received.v1
  -> incident-service consumes event
  -> deduplication check
  -> creates or updates Alert
  -> creates or updates Incident
  -> publishes incident.triggered.v1
  -> escalation-service consumes incident.triggered.v1
  -> resolves first escalation target
  -> publishes notification.requested.v1
  -> notification-service sends notification
  -> publishes notification.delivered.v1 or notification.failed.v1
  -> timeline event recorded
```

### 7.2 Acknowledge flow

```text
Responder clicks ACK
  -> POST /v1/incidents/{id}/acknowledge
  -> incident-service validates transition
  -> incident status becomes ACKNOWLEDGED
  -> publishes incident.acknowledged.v1
  -> escalation-service cancels pending escalation tasks
  -> timeline event recorded
```

### 7.3 Resolve flow

```text
Responder resolves incident
  -> POST /v1/incidents/{id}/resolve
  -> incident-service validates transition
  -> incident status becomes RESOLVED
  -> publishes incident.resolved.v1
  -> escalation-service cancels pending escalation tasks
  -> timeline event recorded
```

### 7.4 Recovery event flow

```text
External provider sends RECOVERY for entityId
  -> integration-service normalizes payload
  -> incident-service finds open incident by source + entityId
  -> resolves incident automatically
  -> timeline event recorded
```

## 8. Domain Event Contracts

## 8.1 AlertReceivedEvent

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-06-03T12:00:00Z",
  "organizationId": "uuid",
  "integrationId": "uuid",
  "routingKey": "backend-critical",
  "source": "grafana",
  "messageType": "CRITICAL",
  "externalEntityId": "payment-api-5xx-rate",
  "dedupKey": "grafana:payment-api-5xx-rate",
  "title": "Payment API 5xx rate high",
  "description": "Error rate exceeded 5% for 5 minutes",
  "severity": "CRITICAL",
  "metadata": {
    "env": "production",
    "region": "eu-central-1"
  }
}
```

## 8.2 IncidentTriggeredEvent

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-06-03T12:00:01Z",
  "organizationId": "uuid",
  "incidentId": "uuid",
  "serviceId": "uuid",
  "escalationPolicyId": "uuid",
  "severity": "CRITICAL",
  "dedupKey": "grafana:payment-api-5xx-rate"
}
```

## 8.3 NotificationRequestedEvent

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-06-03T12:00:02Z",
  "organizationId": "uuid",
  "incidentId": "uuid",
  "recipientUserId": "uuid",
  "channel": "EMAIL",
  "reason": "ESCALATION_LEVEL_1"
}
```

## 9. Development Phases

## Phase 0 - Repository and Platform Foundation

Goal: build the skeleton.

Decisions locked:

- Monorepo (single git repo, multiple deployable services).
- Gradle multi-project build: each service in `services/*` is its own Gradle subproject; shared code in `libs/*`.
- Convert the current single-module `heimcall` skeleton into the multi-project layout (root settings.gradle includes all subprojects; common config via a convention plugin / shared build logic).

Deliverables:

- Gradle multi-project conversion (root + `services/*` + `libs/*` subprojects)
- Docker Compose for local dependencies
- Kubernetes namespace and base manifests
- API Gateway skeleton (Spring Cloud Gateway)
- Shared libraries for errors, tracing, events, and test utilities
- PostgreSQL (database-per-service), Kafka, Redis local setup
- OpenAPI generation baseline

Recommended local dependencies:

```text
PostgreSQL
Kafka + Schema Registry optional
Redis
Mailhog
Keycloak optional
Prometheus + Grafana optional
```

## Phase 1 - Identity, Teams, and Service Catalog

Goal: model who owns what.

Split into two sequential vertical slices (decided 2026-06-08) instead of one large sprint:
`identity-service` first (it unblocks real tenant + integration-key resolution, the main Phase 1
goal), then `service-catalog-service` (it references teams from identity, so it sits on top).
Both are separate deployable services with their own database, per the microservices-first decision.

Cross-cutting decisions for Phase 1:

- Auth: header-context stub for now (`X-Org-Id` / `X-User-Id` carry the tenant context). Full JWT +
  Spring Security is deferred to a later phase. Tenant-isolation rule still enforced via that context.
- Integration-key resolution: integration-service resolves the key synchronously over REST against
  identity-service at ingest time; an invalid key is rejected (401). Redis caching deferred.

### Phase 1a - identity-service

Deliverables:

- Organization CRUD
- User CRUD
- Team CRUD
- Team membership
- API key generation for integrations
- IntegrationKey issue + resolve (hashed at rest; plaintext shown once)
- Resolve endpoint consumed by integration-service to replace the dev placeholder org

Acceptance focus:

- Only organization members can access organization data (via header context).
- An invalid / unknown integration key is rejected at ingestion (401).
- A valid integration key resolves to its organization + integration id.

Deferred from Phase 1a (built & verified 2026-06-08; carry forward):

- `ApiKey` (user/programmatic API key, plan 4.1) — only `IntegrationKey` was implemented.
- Real JWT + Spring Security auth — still the header-context stub.
- Redis cache for integration-key resolution (currently a synchronous REST call per ingest).
- RBAC beyond "is a member" (role is stored but not enforced per-action).
- Integration-key revoke/rotate endpoints (revoke exists on the entity, not exposed).
- Automated tests (unit / integration) — verification was manual curl only.
- api-gateway identity route added but not exercised at runtime.

### Phase 1b - service-catalog-service

Deliverables:

- MonitoredService CRUD
- Assign service to team (ServiceOwnership)
- ServiceTag
- Assign service to escalation policy placeholder

Acceptance focus:

- A service must belong to one organization.
- A service can be owned by a team (team referenced from identity-service).

Deferred from Phase 1b (built & verified 2026-06-09; carry forward):

- Single owning team (`owner_team_id` column); a multi-team ownership table is deferred.
- escalation_policy assignment is a placeholder (id stored, not validated until escalation-service).
- Cross-service tenant checks (membership, team-in-org) go through identity's internal API with no
  caching; Redis caching deferred along with the resolution cache in Phase 1a.
- MonitoredService is not yet wired to incident routing (routing-key -> service mapping is later).
- Automated tests — verification was manual curl only.

## Phase 2 - Integration Ingestion and Alert Normalization

Goal: accept external signals.

Deliverables:

- Generic webhook endpoint
- Integration key validation
- Routing key support
- Raw inbound event storage
- Normalized AlertReceivedEvent publication
- Provider message type mapping

Supported message types:

```text
CRITICAL
WARNING
INFO
ACKNOWLEDGEMENT
RECOVERY
```

Acceptance focus:

- Invalid integration key is rejected.
- Valid payload becomes normalized event.
- Recovery event does not create a new incident if no open incident exists.

## Phase 3 - Incident Lifecycle and Deduplication

Goal: create the core incident engine.

Deliverables:

- Alert table
- Incident table
- Deduplication by organization + source + external entity id / dedup key
- Incident trigger, acknowledge, resolve, cancel endpoints
- Timeline event creation
- IncidentTriggeredEvent, IncidentAcknowledgedEvent, IncidentResolvedEvent

Acceptance focus:

- Repeated CRITICAL event with same dedup key updates existing open incident.
- ACK stops escalation eligibility.
- RESOLVED incident is not mutated by another CRITICAL event; a new incident may be opened depending on reopen policy.

Sprint 1 (2026-06-05) delivered the inbound-signal-driven core (dedup, recovery, timeline) with the
count living on the incident. Sprint 8 (2026-06-09) finished the phase per the glossary domain model:

- `Event -> Alert -> Incident` flow made explicit. **Alert** is now a first-class dedup aggregate
  (`alert` table): one OPEN alert per `(org, dedupKey)`, repeats bump `occurrence_count`. An alert MAY
  exist without an incident (INFO / non-actionable). An **`alert_occurrence`** table is the immutable
  per-signal log (one row per inbound event hitting the alert). The incident no longer owns a counter
  (`alert_count` dropped); the incident-level open-dedup unique index stays as a backstop.
- **Lifecycle REST commands** (operator actions, distinct from the signal path):
  `POST /v1/incidents/{id}/{acknowledge,resolve,cancel}` - member-gated (`X-User-Id` via identity),
  idempotent (no-op when already in target state), illegal transition -> 409, timeline event each,
  publishes the matching domain event. New `incident.canceled.v1` event; escalation-service cancels
  pending tasks on it (mirrors ACK/resolve). Manual commands also sync the linked alert's status.

Deferred from Phase 3 (carry forward):

- `reassign` endpoint + `IncidentAssignment` model (plan 4.4) not built.
- Incident not yet fully leaned: it still carries `dedup_key`/`source`/`external_entity_id` for display
  + the backstop index; a thinner incident is a later cleanup.
- INFO now records a (no-incident) alert instead of being fully ignored.
- Automated tests - verification was manual curl + live Kafka/Postgres only.

## Phase 3.5 - Reliability and Idempotency

Goal: make the alert -> incident path safe under Kafka failures and redelivery. Inserted after Phase 3 because the core consumer is where these failure modes first bite. Should be done before adding more consumers (escalation, notification), not deferred to the end.

Background: the Sprint 1 slice is deliberately at-most-once on the producer and not idempotent on the consumer. Known gaps to close here:

- Producer is fire-and-forget: integration-service returns 202 before confirming the Kafka write, so a broker outage silently drops the event.
- Consumer is not idempotent: Kafka redelivers on rebalance/retry, and reprocessing the same event increments alertCount again (count inflation). This is a correctness bug, not just resilience.
- No retry policy or dead-letter topic: a poison-pill message can block the partition.
- Raw inbound payloads are not stored, so a publish failure leaves no trace and no replay path.

Deliverables:

- Producer reliability: acks=all, send callback, fail the HTTP request (5xx) when the publish is not confirmed. Optionally a transactional outbox so DB write and event publish are consistent.
- Consumer idempotency: a processed_event(event_id) table (or Redis key with TTL); skip events already handled.
- Retry + dead-letter: bounded retry, then route to <topic>.DLT with the failure reason.
- RawInboundEvent storage in integration-service for audit and replay.
- Poison-pill handling: ErrorHandlingDeserializer so a malformed payload goes to DLT instead of crashing the listener.

Acceptance focus:

- Redelivering the same AlertReceivedEvent does not change alertCount or create a second timeline event.
- A broker outage during ingestion is surfaced to the caller (no silent loss).
- A malformed message lands in the DLT and the consumer keeps processing the next message.

## Phase 4 - Scheduling

Goal: calculate current on-call responders.

Deliverables:

- Schedule CRUD
- Rotation CRUD
- Participants
- Weekly rotation support
- Daily rotation support
- Current on-call endpoint
- Override support
- Timezone-aware calculations

Acceptance focus:

- Weekly rotation returns the correct participant.
- Override takes priority over base rotation.
- Timezone is respected.

Built as `schedule-service` (port 8085). Rotation is calendar-based: anchored at start_date +
handoff_time in the schedule's timezone; DAILY/WEEKLY periods counted with ChronoUnit on
ZonedDateTime (DST-aware). On-call resolution: active override wins, else highest-priority started
rotation. Pure math in `OnCallCalculator` with a JUnit test (first automated test in the repo).

Deferred from Phase 4 (built & verified 2026-06-09; carry forward):

- Rotation length is DAILY or WEEKLY only; custom N-day/length rotations deferred.
- DST transition-instant edge behavior not tested (Istanbul, which has no DST, was used for tests).
- On-call cache (Redis) not added; each query recomputes from the DB.
- Participant/override user membership is checked via identity's internal API per call (no cache).

## Phase 5 - Escalation Engine

Goal: notify the right people in order.

Deliverables:

- Escalation policy CRUD
- Escalation rule CRUD
- Rule targets: USER, SCHEDULE, TEAM
- EscalationTask table
- Escalation worker
- Kafka-driven incident trigger handling
- Pending task cancellation on ACK/RESOLVE
- Repeat support optional

Acceptance focus:

- Level 1 notification is requested immediately.
- If no ACK before wait duration, Level 2 notification is requested.
- If incident is ACKNOWLEDGED before task execution, task is canceled.

## Phase 6 - Notification Delivery

Goal: send messages reliably.

Deliverables:

- Email channel
- Telegram channel
- Webhook channel
- Delivery attempt log
- Retry policy
- Provider timeout handling
- Dead-letter handling
- Notification preferences basic model

Acceptance focus:

- Delivery success is recorded.
- Failed delivery retries with bounded attempts.
- Failed messages are visible operationally.

Built as `notification-service` (port 8087). Consumes `notification.requested.v1`, persists the request
(PK = request eventId, doubles as the idempotency ledger), fans out to the recipient's enabled
`contact_method`s, and creates one `notification_delivery` per method. A `@Scheduled` worker fires due
PENDING deliveries: success -> DELIVERED + `notification.delivered.v1`; failure -> bounded retry with
backoff (attempts * retry-backoff) up to max-attempts, then FAILED + `notification.failed.v1`. Channels:
EMAIL (`JavaMailSender` -> SMTP/mailhog) and WEBHOOK (HTTP POST, bounded timeout). Consumer is
ErrorHandlingDeserializer + retry + DLT (mirrors Phase 3.5). Delivery state tracked separately from
incident state. Contact methods owned here (plan 4.7), member-gated CRUD; delivery visibility endpoint.

Deferred from Phase 6 (built & verified 2026-06-09; carry forward):

- Telegram channel not built (EMAIL + WEBHOOK only).
- Notification preferences are just per-contact-method `enabled`; no cooldown / per-incident throttle /
  quiet hours, no Redis notification-cooldown cache.
- Delivery worker assumes a single instance; no distributed lock (plan 6.4 `lock:escalation-worker`-style).
- Contact-method `destination` is not format-validated (email/url) and has no verification step.
- `notification.delivered/failed.v1` have no consumer yet (timeline/ops reporting is Phase 7/8).
- Publish is at-least-once (no transactional outbox), consistent with the other services.
- Automated tests - verification was manual (curl + console-producer + mailhog) only.

## Phase 7 - UI and Realtime Collaboration

Goal: make the product usable.

Deliverables:

- Incident list
- Incident detail
- Timeline
- ACK and resolve buttons
- Service management
- Schedule management
- Escalation policy editor
- WebSocket/SSE incident updates

Decided 2026-06-09: real JWT auth (full), SSE realtime, React+Vite+TS UI in a new `web/` folder,
gateway-routed. Sliced into tickets, each verified + reviewed before the next.

Ticket 1 (done, 2026-06-09): notification feedback loop. incident-service now consumes
`notification.delivered.v1`/`notification.failed.v1` and appends `NOTIFIED`/`NOTIFY_FAILED` timeline
events (idempotent on event id, tenant-checked). Closes the trigger->notify->timeline loop so the UI
incident detail can show notification outcomes.

Ticket 2 - Auth backend + `libs/common-security`:
- New `libs/common-security`: HS256 JWT (jjwt), shared secret via `heimcall.jwt.secret`. `JwtSupport`
  (issue/verify), `JwtAuthenticationFilter` (validates `Authorization: Bearer`, sets principal, and
  injects the verified userId as the `X-User-Id` header so existing controllers keep working but the
  header is now derived from a validated signature, not client-trusted). Spring Boot auto-configuration
  (`SecurityFilterChain`, stateless): permitAll `/actuator/**`, `POST /v1/auth/{login,register,refresh}`,
  `/v1/internal/**`, `/v1/integration-keys/resolve`, `/v1/integrations/**`; anyRequest authenticated.
- identity-service: `app_user.password_hash` (Flyway V2, BCrypt). `POST /v1/auth/register`,
  `POST /v1/auth/login` -> `{accessToken, refreshToken, user}`, `POST /v1/auth/refresh`, `GET /v1/auth/me`
  (user + memberships). Access token 60m, refresh 30d. identity is the first consumer of common-security
  (its own user-facing endpoints become JWT-protected; `/v1/auth/*` + internal/resolve stay open).

Ticket 3 - Propagate common-security to the other 5 services (catalog, schedule, integration,
incident, escalation). Each adds the dependency + `heimcall.jwt.secret`; the filter derives `X-User-Id`
from the JWT, so the ~13 `@RequestHeader("X-User-Id")` controllers are untouched. api-gateway adds CORS +
forwards `Authorization`. `/v1/internal/**` and the integration ingest endpoint stay open.

Ticket 4 (done, 2026-06-11): SSE incident stream `GET /v1/incidents/stream` (per-org `SseEmitter` registry
fed by an AFTER_COMMIT `@TransactionalEventListener` off `IncidentDomainEvents`, + 20s heartbeat).
Single-instance (multi-instance -> Redis pub/sub, deferred); synchronous `send` on the commit thread
(slow-client blocking, deferred to Phase 8). SSE auth via access-token query param, honored only on the
stream path (EventSource cannot set headers). Verified end-to-end.

Ticket 5 (done, 2026-06-11): React UI MVP (`web/`), Vite + React 18 + TS, hand-written typed fetch client.
Login/register, access token in memory + refresh token in `localStorage` (httpOnly cookie deferred to a
backend sub-ticket), `/me`-driven org selector + minimal create-org. Incident list (status filter), detail
(timeline + alerts + lazy occurrences), status-aware ACK/resolve/cancel, SSE live updates (EventSource +
access_token query param, full-list refetch on connect/event). Shipped in 6 vertical slices, each verified
against the running fleet. Note: OpenAPI-generated client was not used (services publish no OpenAPI spec) —
the typed client is hand-written, as the ticket text specified.

## Phase 8 - Observability and Production Readiness

Goal: make the system operable.

Deliverables:

- Structured logging
- Correlation ids
- OpenTelemetry traces
- Prometheus metrics
- Grafana dashboards
- Kubernetes probes
- HPA
- Kafka lag dashboards
- Redis and PostgreSQL dashboards
- Runbooks

Important metrics:

```text
incident_triggered_total
incident_acknowledged_total
incident_resolved_total
incident_time_to_ack_seconds
incident_time_to_resolve_seconds
notification_delivery_success_total
notification_delivery_failure_total
escalation_task_executed_total
kafka_consumer_lag
```

### Ticket breakdown

- **T1 (DONE)** - `common-observability`: structured JSON logging (logstash logback) + correlation-id
  propagation across HTTP and Kafka (servlet filter + producer/record interceptors), fleet-wide. Gateway
  reactive WebFilter deferred.
- **T2 (DONE)** - Prometheus metrics + actuator probes.
  - `micrometer-registry-prometheus` added to `common-observability` (api); actuator already on all 8 services.
  - `prometheus` + `health` (with `liveness`/`readiness` probe groups) exposed per service yml.
  - Domain meters: incident-service `incident_triggered/acknowledged/resolved_total` counters +
    `incident_time_to_ack/resolve_seconds` timers (trigger time = incident `created_at`); notification-service
    `notification_delivery_success/failure_total`; escalation-service `escalation_task_executed_total`.
  - JVM/HTTP metrics come from Micrometer auto-instrumentation (no code).
- **T3 (DONE)** - native Kafka client metrics (consumer lag). `kafka_consumer_lag` does **not** come free:
  Boot binds its `MicrometerConsumerListener` only to the consumer factory it auto-creates, and it can't even
  autowire a service's `ConsumerFactory<String,Object>` (invariant generics) so the main listener runs on a
  hidden non-bean fallback factory. Fixed in `common-observability` by attaching Micrometer consumer/producer
  listeners through the `ConcurrentKafkaListenerContainerFactory` beans after singletons exist. Verified
  `kafka_consumer_fetch_manager_records_lag_max` exported for every consumer incl. the primary one.
- **T4a (DONE)** - OpenTelemetry distributed traces fleet-wide. `micrometer-tracing-bridge-otel` +
  `opentelemetry-exporter-otlp` in `common-observability`; Boot auto-configures the tracer, OTLP/HTTP
  exporter, and HTTP server+client spans. Kafka spans need a nudge (same custom-factory gap as T3): a
  `KafkaTracing` `BeanPostProcessor` flips `observationEnabled(true)` on the services' own `KafkaTemplate`
  and `ConcurrentKafkaListenerContainerFactory` beans, since Boot's observation-enabled yml flags only reach
  the template/factory it auto-creates. Fleet-wide defaults (sampling, OTLP endpoint) via an
  `EnvironmentPostProcessor` so no service yml changes. `traceId`/`spanId` added to the JSON logback encoder.
  Spans export to a local Jaeger all-in-one (compose). Verified a single trace spanning integration ->
  incident across the Kafka hop (context on record headers).
- **T4b (DONE)** - sync REST hops join the distributed trace. Every internal `RestClient` (the six
  `-> identity` clients, incident `-> catalog`, escalation `-> schedule`, catalog `-> escalation`) and the
  notification `WebhookSender` built from a raw `RestClient.builder()`, which skips all `RestClientCustomizer`
  beans — including Boot's `RestClientObservationConfiguration` customizer — so calls emitted no client span
  and propagated no `traceparent`; callees never joined the trace. Fixed by injecting Boot's auto-configured
  `RestClient.Builder` bean per client. No yml/config change. Verified a single Jaeger trace spanning
  integration-service -> identity-service (`POST /v1/integration-keys/resolve`) plus the integration ->
  incident Kafka hop.
- **T4c-1 (DONE)** - Prometheus + Grafana stack in compose. Prometheus (`:9090`) scrapes all 8 services'
  `/actuator/prometheus` (one `heimcall` job, static targets `localhost:8080`-`8087`, `service` label).
  Grafana (`:3000`, admin/admin) auto-provisions a Prometheus datasource + two dashboards: JVM/HTTP and
  Heimcall domain metrics (incident counters/timers, notification success/fail, kafka consumer lag). Both
  containers `network_mode: host` because firewalld drops docker-bridge->host packets, so a bridged scraper
  can't reach the host-run services (Linux-only; fine for local dev). Verified incident-service scraped UP +
  domain metric queried through Grafana.
- **T4c-2 (DONE)** - PostgreSQL + Redis dashboards. `postgres-exporter` (`:9187`, `pg_stat_database` spans
  every db) + `redis-exporter` (`:9121`) as compose containers; host-net Prometheus scrapes them at
  `localhost:9187`/`localhost:9121` (`postgres` + `redis` jobs). Two Grafana dashboards (PostgreSQL:
  connections, commits/rollbacks, cache-hit, tuples, deadlocks; Redis: clients, memory, ops/s, hit ratio,
  evicted/expired). Adding targets needs `docker compose restart prometheus`. Verified both UP + queried.
- **T4c+ (DONE)** - Kubernetes deploy + probes + HPA + runbooks. Dockerfile per service (multi-stage,
  `eclipse-temurin:21-jre`, Spring layered-jar extraction, non-root); root `build.gradle` disables the
  redundant `-plain.jar` for boot services so the COPY glob is unambiguous. Helm chart `deploy/helm/heimcall`
  renders Deployment+Service+HPA per service from one range-over-services template each: liveness/readiness
  probes -> `/actuator/health/{liveness,readiness}`, startupProbe (~150s) shields slow Flyway boot; shared
  Secret (jwt + per-db creds) + env wiring (kafka/otlp/jwt/cross-service base-urls; gateway also gets route
  URIs + UI origin + LoadBalancer). HPA on api-gateway (2-5) + incident-service (2-6) on CPU 70%. Gateway
  route URIs made env-overridable (`${CATALOG_URI:...}`) so they resolve cluster Service DNS. Chart deploys
  only the 8 services; Postgres/Kafka/Redis/Jaeger are BYO via `infra.*`. Runbooks in `docs/05-runbooks.md`
  (8 playbooks tied to the shipped metrics/dashboards). Verified **end-to-end on a real `kind` cluster**:
  `helm install` -> all 8 services `Running 1/1` (probes pass), gateway+incident 2 replicas (HPA min), HPAs
  read real CPU after a metrics-server install; full flow register -> org -> key -> ingest -> (Kafka) ->
  incident TRIGGERED -> ACK -> RESOLVE (timeline `TRIGGER/NO_POLICY/ACK/RESOLVE`). In-cluster deps from
  `deploy/kind/`. Three real-deploy fixes (render hid them): stale `-plain.jar` glob, docker-29 multi-arch
  `kind load` digest error (node pulled directly), and `cp-kafka` KRaft opaque crash -> `apache/kafka:3.8.1`.

## Phase 9 - Transactional Outbox

Goal: make domain-event publication atomic with the DB write. Closes the last correctness gap left after
Phase 8: every producing service either publishes on an `AFTER_COMMIT` listener (commit succeeds, then a
crash or broker outage drops the event — **lost event**) or calls `KafkaTemplate.send` inside the
`@Transactional` method (a later rollback leaves a **ghost event**, a post-commit send loss is a **lost
event**). Consumers are already idempotent (`processed_event` / `notification_request` PK ledgers), so
at-least-once delivery from a relay is safe; the missing half is never-lose / never-ghost on the producer.

Pattern: in the same DB transaction as the domain change, INSERT an `outbox` row instead of publishing
directly. A relay polls unpublished rows, publishes to Kafka with confirm, marks them `PUBLISHED`. Tx
rolls back -> no row -> no ghost. Tx commits -> row exists -> the event is published eventually.

Decisions locked (2026-06-16):
- Published rows are **kept** (`status=PUBLISHED`) for audit/replay; a scheduled prune deletes rows older
  than a retention window. (Mirrors integration-service's `raw_inbound_event` audit trail.)
- Sliced per ticket; **T1 first** (shared lib + incident-service), verified + reviewed before T2/T3 are
  specced.

Shared lib `libs/common-outbox` (ships via one dependency + auto-config, like `common-security` /
`common-observability`):
- `OutboxRecord` entity + repository mapped to table `outbox` (id, aggregate_type, aggregate_id, topic,
  msg_key, payload bytes, headers, status PENDING|PUBLISHED, created_at, published_at, attempts,
  last_error).
- `OutboxAppender` - called inside the service's `@Transactional` method; serializes the event to bytes +
  captures headers (Kafka type-id, `X-Correlation-Id`, `traceparent`) so correlation/trace survive the
  relay. Replaces the direct `KafkaTemplate.send`.
- `OutboxRelay` - `@Scheduled` poller. `SELECT ... ORDER BY id FOR UPDATE SKIP LOCKED` (lock-safe per
  §3.2, multi-instance ready), publishes sequentially (preserves per-aggregate order), confirms, marks
  `PUBLISHED`; bounded retry, leaves `PENDING` + records `last_error` on failure.
- `OutboxPrune` - `@Scheduled` delete of `PUBLISHED` rows older than the retention window.
- The relay re-emits the raw value bytes + original headers, so existing type-header-based consumers
  deserialize unchanged.
- The `outbox` DDL ships as a documented snippet each service adds as its own next Flyway migration (libs
  cannot own per-service migrations; database-per-service).

### Ticket breakdown

- **T1** - `libs/common-outbox` + wire **incident-service**. Drop the `KafkaTemplate.send` in
  `IncidentEventPublisher`'s `AFTER_COMMIT` listener; append the 4 `incident.{triggered,acknowledged,
  resolved,canceled}.v1` events to the outbox inside the lifecycle transaction instead. Flyway
  `V5__outbox.sql`.
  - Acceptance: trigger -> `outbox` row written in the same tx -> relay publishes -> escalation-service
    consumes. Kill the relay between commit and publish, restart -> the event still lands (no loss). A
    rolled-back lifecycle command writes no outbox row (no ghost). Correlation id + trace span survive the
    relay hop.
- **T2** (spec after T1) - **escalation-service** (`notification.requested.v1`) +
  **notification-service** (`notification.delivered/failed.v1`) onto the outbox.
- **T3 (DONE)** - **integration-service** onto the outbox. The ingest publish was *synchronous* (block on
  `acks=all`, broker outage -> 503) rather than an `AFTER_COMMIT`/in-tx send, so it had no silent loss
  already — but it was the odd one out and coupled ingestion to broker health. Now `AlertNormalizer` is
  `@Transactional`: the `raw_inbound_event` audit row and the normalized `alert.received.v1` event are
  written in one tx (raw + `outbox.append`), and the relay publishes async. **Contract change (approved):**
  a broker outage no longer 503s the caller — a **202 now means "durably accepted", not "published to
  Kafka"** (relay drains the row when the broker is back). `raw_inbound_event` stays as pure inbound audit
  (always `RECEIVED`; publish status moved to the outbox row). The sync `KafkaTemplate` send +
  `EventPublishException` are gone. Following PagerDuty's Events API v2 (`dedup_key` in the 202 body), the
  202 now returns `{status, eventId, dedupKey}` — `dedupKey` is the alert correlation handle for follow-up
  ACK/RECOVERY. Flyway `V2__outbox.sql`. Verified on real Kafka/Postgres: ingest -> 202 with eventId+dedupKey
  -> `outbox` PENDING -> relay PUBLISHED (attempts=1, `__TypeId__`/correlation/`traceparent` intact) ->
  `alert.received.v1` carries the byte-correct message; `raw_inbound_event` RECEIVED.

## 10. Suggested Repository Structure

```text
incident-platform/
  services/
    api-gateway/
    identity-service/
    integration-service/
    incident-service/
    schedule-service/
    escalation-service/
    notification-service/
  libs/
    common-domain/
    common-events/
    common-security/
    common-observability/
    test-support/
  deploy/
    docker-compose/
    kubernetes/
    helm/
  docs/
    architecture/
    api/
    adr/
    domain/
```

## 11. API Design Conventions

Use versioned APIs:

```text
/v1/incidents
/v1/services
/v1/schedules
/v1/escalation-policies
/v1/integrations
```

Use command-style subresources for lifecycle transitions:

```http
POST /v1/incidents/{incidentId}/acknowledge
POST /v1/incidents/{incidentId}/resolve
POST /v1/incidents/{incidentId}/cancel
POST /v1/incidents/{incidentId}/reassign
```

Use idempotency keys for external and lifecycle commands:

```http
Idempotency-Key: client-generated-key
```

## 12. Kafka Topic Design

Naming convention:

```text
<context>.<event-name>.v<version>
```

Examples:

```text
alert.received.v1
incident.triggered.v1
incident.acknowledged.v1
incident.resolved.v1
escalation.task-due.v1
notification.requested.v1
notification.delivered.v1
notification.failed.v1
```

Consumer group examples:

```text
incident-service.alert-received-consumer
escalation-service.incident-triggered-consumer
notification-service.notification-requested-consumer
timeline-service.domain-event-consumer
```

## 13. Redis Usage Plan

Keys:

```text
idempotency:{organizationId}:{key}
rate-limit:{organizationId}:{apiKey}
oncall-cache:{scheduleId}:{instantBucket}
notification-cooldown:{incidentId}:{userId}:{channel}
lock:escalation-worker:{taskShard}
```

TTL rules:

```text
Idempotency keys: 24 hours
Rate limit keys: 1 minute windows
On-call cache: 1-5 minutes
Notification cooldown: configurable, e.g. 1 minute
Distributed locks: short TTL, e.g. 30 seconds
```

## 14. Kubernetes Deployment Plan

Namespace structure:

```text
incident-dev
incident-staging
incident-prod
```

Each service should define:

- Deployment
- Service
- ConfigMap
- Secret references
- Readiness probe
- Liveness probe
- Resource requests/limits
- HorizontalPodAutoscaler

Readiness examples:

- API service must connect to database.
- Kafka-consuming service must start successfully but should not block readiness forever on temporary broker issues.
- Notification service readiness should not depend on all third-party providers being reachable.

## 15. Security Plan

- JWT for user APIs.
- API keys for integration endpoints.
- Hashed integration keys in database.
- RBAC by organization/team role.
- Secret management via Kubernetes Secrets for dev, external secret manager for production.
- Audit all privileged changes.
- Rate limit integration endpoints.
- Validate and size-limit inbound payloads.

## 16. Testing Strategy

### Unit tests

- Domain state transitions
- Deduplication rules
- Rotation calculations
- Escalation task decisions
- Notification retry decisions

### Integration tests

Use Testcontainers for:

- PostgreSQL
- Kafka
- Redis

### Contract tests

- Public REST APIs
- Kafka event schemas
- Webhook payload compatibility

### End-to-end tests

- Trigger to notification flow
- ACK cancels escalation
- Recovery resolves incident
- Duplicate alert increments count

## 17. Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Microservices add operational complexity early (chosen trade-off) | Strong bounded-context discipline, shared libs for cross-cutting concerns, Docker Compose + Testcontainers for local parity, contract tests on every service boundary |
| Kafka event duplication | Idempotent consumers and event ids |
| Escalation double execution | DB state check + Redis lock + task status transition |
| Notification provider outages | Retry, circuit breaker, fallback channel |
| Schedule calculation bugs | Heavy unit testing with timezone cases |
| Deduplication mistakes | Strong unique indexes and explicit dedup key policy |
| Tenant data leaks | Organization id in every aggregate and query |

## 18. Definition of Done

A feature is done when:

- Domain rule is documented.
- API contract is defined.
- Database migration exists.
- Unit tests cover domain behavior.
- Integration tests cover database/event interaction.
- Timeline event is created where applicable.
- Observability metrics/logs are added.
- OpenAPI docs are updated.
- Kubernetes config is updated if needed.
- Acceptance tests pass.

## 19. Recommended First Sprint

Sprint 1 goal: create the skeleton and trigger a fake incident end-to-end without real notification.

Tasks:

1. Create repository structure.
2. Create api-gateway, integration-service, incident-service.
3. Run PostgreSQL, Kafka, Redis using Docker Compose.
4. Implement generic webhook endpoint.
5. Publish `alert.received.v1`.
6. Consume event in incident-service.
7. Create alert and incident.
8. Implement deduplication by dedup key.
9. Add timeline table.
10. Add basic incident list endpoint.

Sprint 1 success demo:

```text
curl POST /v1/integrations/{integrationKey}/events/backend-critical
  -> incident appears in database
  -> duplicate payload increments alert count
  -> timeline shows trigger and duplicate events
```

## 20. Source Notes

This plan is informed by public behavior and terminology from PagerDuty, Opsgenie, and Splunk On-Call / VictorOps documentation, especially around services, escalation policies, schedules, alert notification flow, deduplication, routing keys, entity IDs, and incident lifecycle actions.
