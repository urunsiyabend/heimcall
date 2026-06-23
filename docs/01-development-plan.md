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

## Phase 10 - Routing Reliability (no silent paging black hole)

Goal: close audit finding #10 — an incident must never silently fail to page anyone because of a routing
problem. Today `IncidentService.triggerOrDeduplicate` resolves routing/policy from service-catalog
best-effort and `CatalogClient.resolve` swallows **every** failure into `Optional.empty()`, so three
distinct situations collapse to the same outcome (incident created, `policyId=null`, escalation
short-circuits, nobody paged):

- catalog **404** — no service carries that routingKey (genuine no-match);
- catalog **200 with `escalationPolicyId=null`** — a service matches but has no policy assigned;
- catalog **down / 5xx / timeout** — infra failure, not a routing decision.

The third is the dangerous one: a transient catalog outage masquerades as a routing decision and silently
de-pages a real incident.

Industry precedent (researched 2026-06-17): the routing tree is **total** and "nobody paged" is only ever
an explicit, visible, configured terminal — never a silent fallthrough and never the result of a
routing-system failure. Prometheus Alertmanager **requires** a top-level catch-all route matching every
alert. PagerDuty Event Orchestration sends unmatched events to a catch-all whose default is a **suppressed
alert** (visible in the Alerts Table, non-paging) — explicitly "prevents silent failures" — and is
configurable to route to a fallback service. Opsgenie cascades to a default routing rule, usually a default
escalation policy, with "route to no one" as an explicit option.

Decision locked (2026-06-17): adopt PagerDuty's full model — **org-default escalation policy if configured,
else suppressed-UNROUTED**. The catch-all decision lives in **service-catalog** (single source of routing
truth, Alertmanager-style total routing tree), so incident-service stays dumb: it sees either a policy
(ROUTED) or a definitive no-match (UNROUTED), and treats a catalog failure as retryable — never as UNROUTED.

### Ticket breakdown

- **T1 (DONE)** - **incident-service**: distinguish transient catalog failure from a definitive no-match.
  `CatalogClient.resolve` returns `Optional.empty()` **only** on a real 404 (`HttpClientErrorException.NotFound`);
  on 5xx / IO / timeout / any other error it throws `RoutingUnavailableException` instead of swallowing.
  `IncidentService.handle` lets it propagate — `handle` is already `@Transactional`, so the
  alert/occurrence/incident/timeline writes roll back together (no orphan incident), and the existing
  `DefaultErrorHandler` retries then dead-letters to `alert.received.v1.DLT`. Retry budget kept at
  `FixedBackOff(1000ms, 2)` **deliberately**: during an outage each alert blocks the (single) partition
  ~8s before DLT (3 attempts × 2s connect timeout + 2×1s backoff) — bounded + observable; lengthening the
  blocking backoff only worsens the partition stall. DLT + a DLT-depth alert is the terminal for a longer
  outage. Verified on kind (real Kafka/PG): 404 → incident created, no DLT/no retry; catalog scaled to 0 →
  `RoutingUnavailableException` → DLT (carrying `kafka_dlt-exception-cause-fqcn` + `X-Correlation-Id` +
  `traceparent`), **no orphan incident**, lag clears; catalog back → consumer healthy, no-match path resumes.
  - **Two latent bugs surfaced by runtime verification (compile-clean):**
    1. `CatalogClient`'s `RestClient` had **no timeout** → an endpoint-less ClusterIP does not RST, so the
       consumer thread hung in `resolve` indefinitely and stalled the partition (no throw, no DLT). Fixed
       with `ClientHttpRequestFactorySettings` connect=2s / read=3s (overridable via
       `catalog.connect-timeout-ms` / `catalog.read-timeout-ms`).
    2. The incident DLT producer's `DelegatingByTypeSerializer` used **exact-match** over `{byte[], Object}`,
       so a record whose value had already deserialized to an event object (every **application** exception,
       not just poison-pills) threw `SerializationException` on DLT publish → the recoverer failed → infinite
       retry loop (dead-lettering was silently broken for all non-deserialization failures). Fixed with
       `assignable=true` over an ordered (`LinkedHashMap`, byte[] first) map: poison-pill byte[] → raw,
       deserialized event → JSON. **Shipped in the same incident-service slice as T1 (its DLT guarantee
       depends on it).**
  - Recovery analysis (thundering herd): NOT a concern with today's topology — `alert.received.v1` is
    single-partition + listener concurrency 1, so on catalog recovery the backlog drains **sequentially**
    (a seri burst, not a concurrent stampede), and only incident-service calls catalog routing. The real
    recovery risk is a **cold catalog** (cold JVM/pool just past readiness) being hit by the draining
    backlog → slow responses may exceed the 3s read timeout → spurious DLT of alerts that arrived during the
    outage. The deferred routing-cache (below) closes this too. **Caveat:** if `alert.received.v1` later
    gets multiple partitions + concurrency >1 for throughput, a real thundering herd against catalog
    appears — revisit then.
- **T2 (DONE)** - **service-catalog**: org-default catch-all policy, making routing resolution total.
  New `org_routing_default` table (Flyway `V3`, one row per org; absence = no default) +
  `OrgRoutingDefaultController` (`PUT/GET/DELETE /v1/organizations/{orgId}/routing-default`, member-gated,
  the default policy validated against escalation-service like the per-service policy → unknown/foreign 409).
  `InternalController.resolve` is now **total**: specific service with a policy → that policy; else (no
  service, or matched service with no policy) → the org default if configured; else 404 — so a 200 now
  **never** carries a null `escalationPolicyId` (the old "matched service, null policy → 200-with-null" case
  folds into default-or-404). incident-service unchanged: it already treats 404 as `Optional.empty()`, and
  the no-default 404 is what T3 turns into a visible UNROUTED outcome. Also added the gateway route for the
  new subpath (`Path=/v1/organizations/*/services/**,/v1/organizations/*/routing-default` on the catalog
  route, ahead of identity's `/v1/organizations/**`). Verified end-to-end on kind: no default + unmapped key
  → incident, null policy; org default set → resolve returns default → incident stamped + escalation engine
  scheduled & fired a task on the default policy → notification.requested; bogus default → 409; clear → 204 /
  GET 404 / no-match path restored.
  - Acceptance: no specific match but org default set → resolve returns the default policy (incident
    escalates via it); default not set and no match → 404. ✓
- **T3 (DONE)** - **incident-service**: deliberate, observable UNROUTED outcome. After T2 made catalog
  resolution total, `routing.isEmpty()` is now *exactly* a definitive no-match (a catalog outage throws
  `RoutingUnavailableException` before reaching here, never an empty), so the old policy-null `NO_POLICY`
  branch becomes the UNROUTED branch. On a no-match the incident is created flagged `unrouted=true`
  (Flyway `V6`, `markUnrouted()`): a distinct `UNROUTED` timeline event (replacing `NO_POLICY`), an
  `incident_unrouted_total` counter (incremented AFTER_COMMIT off the `Triggered` domain event, which now
  carries an `unrouted` flag), and `unrouted` exposed on `IncidentResponse` + surfaced as a UI badge.
  The `Triggered` event is still published (SSE/Kafka stay consistent) with `policyId=null`, so
  escalation-service short-circuits (its existing `escalationPolicyId == null` guard) — no escalation
  fires. "Nobody paged" is now a visible, counted decision, not an accident.
  - Acceptance: 404 + no org default → incident is UNROUTED, the counter increments, the incident is
    visible/queryable, and no escalation is scheduled. ✓ Verified on kind: unrouted ingest → `unrouted=true`,
    UNROUTED timeline, `incident_unrouted_total 1.0`, zero escalation tasks / zero deliveries; routed
    regression (org-default set) → `unrouted=false`, policy stamped, escalation fired → EMAIL delivery.
- **T4 (SPEC)** - **incident-service**: routing **availability** cache (last-known-good), so a catalog
  outage longer than the retry budget still pages from last-known routing instead of dead-lettering
  (Alertmanager's "routing config is local" lesson). This is an **availability/correctness** cache, distinct
  from the latency caching deliberately dropped after the Phase-10 load measurement (resolve hop ~2-3ms, not
  a bottleneck). Closes two holes left by T1-T3: (a) alerts arriving **during** an outage escalate from cache
  instead of going to DLT; (b) removes the cold-catalog spurious-DLT risk on recovery (cache hit → catalog
  not hit). With the cache, the catch-all/UNROUTED path (T2/T3) fires only on a genuine, catalog-confirmed
  no-match, never on an outage.

  Design decisions locked (2026-06-19):
  - **Store: Postgres** (`routing_cache` in the incident db), not Redis. No new infra, survives pod restart
    (the in-heap cold-start gap is exactly when an outage may coincide with a deploy), and routing resolve is
    off the latency hot path (it's a Kafka consumer, not a sync API). Write-through runs inside `handle`'s tx
    (same incident-db connection — no cross-resource write).
  - **Fallback is NOT hidden inside `CatalogClient.resolve`.** A new `RoutingAvailabilityResolver` (owned by
    incident-service) wraps `CatalogClient`: the client stays low-level (live HTTP + 404-vs-outage), the
    resolver adds the cache + the decision. `IncidentService` calls the resolver.
  - The resolver returns a `RoutingDecision { serviceId, policyId, ownerTeamId, unrouted, fromCache }`:
    - catalog **200** (live routed) → write-through upsert cache → `unrouted=false, fromCache=false`;
    - catalog **404** (definitive no-match) → **tombstone/delete** the positive cache row → `unrouted=true,
      fromCache=false, policyId=null` (the existing T3 UNROUTED path);
    - **outage** (`RoutingUnavailableException`) + cache **hit** → `unrouted=false, fromCache=true`, policy
      from cache (incident escalates on last-known policy);
    - **outage** + cache **miss** → rethrow `RoutingUnavailableException` → retry/DLT (T1 behavior unchanged;
      a never-seen key during an outage is the genuine unknown).
  - **Only ROUTED (200-with-policy) is ever cached.** UNROUTED (404) is never cached, so it is never paged
    from cache. A catalog-confirmed 404 **tombstones** any positive row (both on the live path and in
    reconciliation), so a dead route can never survive to page on the next outage. (Negative caching — serving
    UNROUTED-from-cache for a 404-confirmed key during an outage — is explicitly **out of scope** here.)
  - **No TTL.** Availability-first, long-lived last-known-good; freshness comes from write-through on every
    up resolve and from invalidation on 404, not from expiry.
  - **Observability of the degraded page:** `routed_from_cache` flag on the incident (Flyway `V7`), a distinct
    `ROUTED_FROM_CACHE` timeline event, an `incident_routed_from_cache_total` counter, and `routed_from_cache`
    on `IncidentResponse` + a UI badge (mirrors `unrouted`). A page from stale routing is visible, not silent.
  - **Reconciliation job** (`@Scheduled`, ~15 min, paced + backoff) — **scoped to cache-routed incidents only,
    NOT a full-cache sweep.** Scans `incident WHERE routed_from_cache=true AND reconciled_at IS NULL`, groups
    by **distinct `(org, routingKey)`** (load = #distinct degraded keys, not #cache rows), live-resolves each
    against catalog after recovery, and compares vs the policy the incident actually used (already on
    `incident.escalation_policy_id` — no version store needed). Marks each incident `reconcile_result`:
    `CURRENT_MATCH` (200 same policy), `CURRENT_DRIFT` (200 diff policy → `routing_cache_drift_total` metric),
    `CURRENT_NOT_FOUND` (404 → tombstone the cache row); catalog still down → leave `reconciled_at` NULL +
    abort the cycle (retried next run). **Audit-only**: it never re-pages and never mutates the incident's
    route. The `CURRENT_` prefix means "catalog differs **now**", not "the cache was wrong at outage time"
    (no catalog as-of history → that claim can't be made).
  - Schema (Flyway `V7`, incident db):
    ```sql
    routing_cache (
      organization_id uuid, routing_key text,
      service_id uuid, escalation_policy_id uuid not null, owner_team_id uuid,
      last_refreshed_at timestamptz not null,
      primary key (organization_id, routing_key)
    );
    -- incident: + routed_from_cache bool not null default false
    --           + reconciled_at timestamptz null
    --           + reconcile_result text null
    ```
  - Acceptance: catalog down + a previously-seen routingKey → incident is created, `routed_from_cache=true`,
    `ROUTED_FROM_CACHE` timeline + counter, and escalation **fires on the cached policy** (not DLT). Catalog
    down + a never-seen key → still DLT (no orphan). Catalog 404 → cache row tombstoned, incident UNROUTED
    (T3 path). After catalog recovery, the reconciliation job resolves the degraded incidents' distinct keys
    and stamps `CURRENT_MATCH` / `CURRENT_DRIFT` / `CURRENT_NOT_FOUND` (drift counted), touching only
    `routed_from_cache` incidents.

Sliced **T1 first** (highest leverage, localized, verifiable), reviewed before T2/T3. T4 follows T3.

## Phase 11 - Concurrency Safety (lock-safe scheduled workers)

Goal: close audit finding #1 — make the `@Scheduled` background workers safe to run on more than one
replica. Today `EscalationWorker.fireDueTasks` and `DeliveryWorker.fireDueDeliveries` run on **every**
replica; each polls due rows then calls `fireDueTask` / `fireDelivery`, whose only guard was a
read-then-check (`findById` + `status != PENDING`) with **no row lock**. Under READ_COMMITTED two replicas
(or the old+new pod during a rolling restart) can both read the same PENDING row, both pass the guard, and
both fire — a duplicate `notification.requested` → double page, or a duplicate email/webhook send. This
violates plan §3.2 ("distributed scheduled work must be lock-safe") and blocks ever scaling escalation /
notification (HPA). Even at `replicas=1` the rolling-restart overlap window makes it a real (narrow) risk.

Decision locked (2026-06-19): adopt the **`FOR UPDATE SKIP LOCKED` per-task claim** already proven in
`common-outbox`'s relay — no new dependency, no Redis, consistent with the codebase. Each fire claims its
row under a lock; a concurrent claimer sees either a locked row or a no-longer-PENDING row and skips. Kept
**per-task** (not whole-batch) so each fire stays in its own transaction — one bad task never rolls back the
batch, and a losing replica moves on instead of blocking.

### Ticket breakdown

- **T1 (DONE)** - escalation-service + notification-service workers made lock-safe. Added a native
  `findPendingForUpdate(id)` (`SELECT ... WHERE id=? AND status='PENDING' FOR UPDATE SKIP LOCKED`) to
  `EscalationTaskRepository` + `NotificationDeliveryRepository`; `EscalationService.fireDueTask` /
  `DeliveryService.fireDelivery` now claim through it (empty → skip) instead of `findById`. No schema change.
  - **Lock scope — known trade-off (documented, accepted):** the claim and the work share one
    `@Transactional` method, so the row lock is held until commit, i.e. **across the side-effect**:
    - escalation `fireDueTask`: side-effect under lock = SCHEDULE→schedule / TEAM→identity REST resolves
      (USER = none) + `outbox.append` (a **local** JDBC INSERT, not a Kafka send — the relay publishes
      async). Bounded by the clients' timeouts. Mild.
    - notification `fireDelivery`: side-effect under lock = the **real** SMTP / webhook send (webhook
      connect+read 5s each, `notification.webhook.timeout-ms`). So a DB connection + row lock are pinned for
      the send duration — the same "don't hold a connection across a network call" shape fixed in integration
      ingest. Accepted **for now** because: `SKIP LOCKED` locks only that one row (other replicas grab other
      deliveries → no cross-replica blocking, throughput unaffected), notification runs single-replica, and
      the send timeout is bounded (~5s). Correctness (exactly-once) is strict.
    - **Evolution when needed** (notification scales out / providers get slow): two-phase claim — Tx1
      `PENDING→SENDING` (commit, release lock), `send()` outside any tx, Tx2 `SENDING→DELIVERED/FAILED/retry`.
      Removes lock-during-send; costs a `SENDING` state + a reclaim/timeout sweep for crashes between Tx1/Tx2,
      and reintroduces an at-least-once re-send window on that recovery path. Deferred.
  - Acceptance: with ≥2 replicas, a batch of due tasks fires **exactly once** (no duplicate
    notification.requested / delivery); two concurrent claimers of one row → exactly one wins. ✓ Verified on
    kind: 200 delay-0 tasks under 2 escalation replicas → 200 tasks EXECUTED / 200 notification requests /
    200 deliveries (zero duplicates); a deterministic DB test (tx A holds the lock, tx B's concurrent
    `FOR UPDATE SKIP LOCKED` claim → 0 rows, row stays PENDING) proves the exactly-one-claimer semantics.

## Phase 12 - Lifecycle Event Ordering (no cross-topic reorder race)

Goal: close the audit finding that incident lifecycle events can be processed **out of order**, so an ACK
(or RESOLVE/CANCEL) that arrives before the TRIGGERED it cancels leaves escalation tasks uncancelled →
**a spurious page for an incident that was already acknowledged/resolved**.

Root cause: the four lifecycle events are on **four separate Kafka topics** (`incident.triggered.v1`,
`incident.acknowledged.v1`, `incident.resolved.v1`, `incident.canceled.v1`), each its own
`@KafkaListener` in escalation-service. Kafka orders messages only **within a partition of a single
topic** — across topics there is no ordering. The ACK handler (`onIncidentClosed`: a small cancel query)
is also faster than the TRIGGERED handler (policy lookup + N task inserts), so ACK-before-TRIGGERED is
plausible in normal operation, not only under rebalance. `onIncidentClosed` then finds no PENDING tasks,
cancels nothing; TRIGGERED arrives after and schedules tasks nobody will cancel; the worker fires them.
The `processed_event` ledger dedupes by id but does **not** order.

Decision locked (2026-06-19): adopt **Option A — collapse the four topics into one ordered stream**
`incident.lifecycle.v1`, partition-keyed by `incidentId`. Per-partition ordering then guarantees an
incident's events are processed in publish order. Only incident-service (producer) and escalation-service
(consumer) touch these topics, so the blast radius is small; the `<context>.<event-name>.v1` naming
convention is broken **deliberately** (approved) because lifecycle is a single ordered transition stream,
not four independent event types. The four typed event records are kept as the payloads; the consumer
dispatches by the `__TypeId__` header (escalation already runs `spring.json.use.type.headers=true`).

**Critical second half — producer-side ordering under a multi-instance relay.** incident-service runs
HPA min 2 (Phase 8), so two `common-outbox` relay instances run concurrently. The relay claims rows with
`FOR UPDATE SKIP LOCKED`: instance A locks the TRIGGERED row, instance B skips it and grabs the ACK row of
the **same incident**, and they publish concurrently → ACK can reach the partition first. Single topic +
key does **not** fix this alone. So the relay claim gets a **per-aggregate ordering guard**: only the
lowest-id PENDING row per `aggregate_id` is claimable —
```sql
... WHERE status='PENDING'
  AND NOT EXISTS (SELECT 1 FROM outbox o2
                  WHERE o2.aggregate_id = outbox.aggregate_id
                    AND o2.status='PENDING' AND o2.id < outbox.id)
  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
```
Instance B cannot claim ACK while TRIGGERED is still PENDING (locked by A); it becomes eligible only after
A marks it PUBLISHED. **Per-aggregate publish order is then guaranteed across relay instances**, while
different aggregates still publish in parallel (`SKIP LOCKED` unchanged). `aggregate_id` is already
`incidentId`. This is a `common-outbox` change → a strict improvement for all four producers (also fixes
e.g. `notification.requested` per-incident ordering).

### Ticket breakdown

- **T1** - single ordered lifecycle topic + relay ordering guard, with tests.
  - `common-events`: add `INCIDENT_LIFECYCLE = "incident.lifecycle.v1"`; **remove** the four per-event
    topic constants (contract break, approved). Keep the four event records as payloads.
  - incident-service `IncidentEventPublisher`: publish all four to `INCIDENT_LIFECYCLE` (key already
    `incidentId`).
  - escalation-service `IncidentEventListener`: class-level `@KafkaListener(topics = INCIDENT_LIFECYCLE)`
    + one `@KafkaHandler` per event type (RESOLVED + CANCELED → `onIncidentClosed`); DLT auto-derives to
    `incident.lifecycle.v1.DLT`.
  - `common-outbox` `OutboxRelay`: add the per-aggregate ordering guard to the claim query.
  - **Tests (thorough):**
    - `common-outbox` Testcontainers PG test (first use of `test-support`): the ordering-guard claim SQL —
      two PENDING rows for one aggregate (ids N < M) + one for another aggregate; tx A's claim returns N
      and the other aggregate's row but **not** M; M is unclaimable while N is PENDING; after N→PUBLISHED,
      M becomes claimable. Proves cross-instance per-aggregate ordering deterministically (mirrors the
      Phase 11 concurrent-claim DB proof).
    - escalation dispatch unit test: each `@KafkaHandler` routes its event type to the right
      `EscalationService` call (Mockito).
    - Runtime e2e on the local fleet: TRIGGERED then an immediate ACK for the same incident → escalation
      consumes TRIGGERED before ACK → tasks created then cancelled → **zero** `notification.requested`
      (no spurious page). Regression: a normal trigger with no ACK still pages.
  - Acceptance: an ACK published immediately after TRIGGERED never results in a page; lifecycle events for
    one incident are processed in publish order regardless of relay instance count; the guard test + the
    e2e both green.

## Phase 13 - Test Coverage (close the §16 testing-strategy gap)

Goal: close the standing testing debt. Through Phase 12 the repo has **three** tests
(`OnCallCalculatorTest`, `IncidentEventListenerTest`, `OutboxRelayOrderingTest`); §16's unit /
integration / contract strategy is otherwise empty. Every prior phase was verified by manual curl +
live fleet only, so behavior is exercised but not *regression-protected*: a refactor can silently break
dedup, a lifecycle guard, the routing decision table, or a retry rule and nothing fails until a manual
run. This phase builds an automated safety net over the existing behavior — no new product behavior.

### Infra decision (locked 2026-06-19): no Testcontainers

Testcontainers is **unusable on this box** and the dev daemon won't change: its bundled docker-java
`UnixSocketClientProviderStrategy` pings the socket at the hardcoded `RemoteApiVersion.VERSION_1_32`,
and Docker engine 29.x has `MinAPIVersion=1.40`, so every container start fails with
`client version 1.32 is too old`. Confirmed empirically (2026-06-19): `DOCKER_API_VERSION` is **not**
honored by that strategy, and bumping the testcontainers BOM 1.20.4 → 1.21.3 did not change the ping
floor. A working fix would need a custom `DockerClientProviderStrategy` — brittle, deferred. So the
phase **does not depend on Docker for tests**; the strategy routes around it:

- **Domain logic → pure JUnit + Mockito.** No infra, fully CI-portable, the bulk of the new tests.
  State machines, dedup rules, transition guards, the routing decision table, retry/backoff decisions,
  rotation math — all pure functions or thin services with mocked collaborators.
- **Kafka paths → `@EmbeddedKafka` (spring-kafka-test).** An in-JVM broker, **no Docker**. Covers
  consumer idempotency, DLT routing (poison-pill + application-exception), and listener dispatch.
- **DB-specific SQL → real compose PostgreSQL, `assumeTrue`-skip when absent.** The proven
  `OutboxRelayOrderingTest` precedent: tests needing real PG semantics (`FOR UPDATE SKIP LOCKED`
  claims, partial unique indexes, the per-aggregate ordering guard) run against the docker-compose PG
  in an isolated schema and skip (not fail) if no PG is reachable, so `./gradlew build` stays green on
  a bare checkout. `test-support`'s Testcontainers singletons stay in the tree but unused; revisit if
  the daemon floor ever changes.

Trade-off accepted: DB-specific tests are environment-gated (skip without compose) rather than hermetic,
and there is no containerized full-stack e2e test (the live-fleet manual run remains the e2e gate). The
EmbeddedKafka + Mockito + compose-PG mix covers the regression surface without the Docker dependency.

### Ticket breakdown

Sliced per service/concern; **T1 first** (highest leverage, zero infra), each verified + reviewed
before the next. T1 is specced concretely below; T2-T5 are outlined and specced in detail when reached.

- **T1 (SPEC)** - **incident-service domain unit tests** (the core engine). Pure JUnit + Mockito, no
  infra. Covers the behavior most central to the product and most exposed to silent regression:
  - **Event → Alert → Incident mapping** (glossary §2): CRITICAL / WARNING → open alert (or dedup onto
    the open one) + open incident on a new alert; a repeat → `occurrence_count` bump + DUPLICATE
    timeline, incident not double-opened; RECOVERY → close alert + resolve its incident; ACKNOWLEDGEMENT
    → acknowledge alert + its TRIGGERED incident; INFO → record a no-incident alert.
  - **Dedup invariant**: at most one OPEN alert per `(org, dedupKey)`; a second OPEN attempt dedups, not
    a new aggregate (the service-level decision; the partial unique index is the DB backstop, covered by
    a compose-PG test if it adds value).
  - **Lifecycle transition guards** (`acknowledge` / `resolve` / `cancel`): idempotent no-op when already
    in the target state; illegal transition → `409`; each action appends the right timeline event and
    publishes the matching domain event; linked alert transitions follow the incident.
  - **`RoutingAvailabilityResolver` decision table** (Phase 10 T4, highest-risk logic): catalog 200 →
    write-through cache + `unrouted=false, fromCache=false`; 404 → tombstone cache + `unrouted=true,
    policyId=null`; outage + cache hit → `fromCache=true`, policy from cache; outage + cache miss →
    rethrow `RoutingUnavailableException`. `CatalogClient` + cache repo mocked.
  - Acceptance: the four areas above are covered by passing unit tests; `./gradlew :services:incident-service:test`
    green with no running infra; a deliberate inversion of a guard / mapping makes a test fail (the net
    actually catches regressions).
- **T2 (DONE)** - **incident-service Kafka resilience** via `@EmbeddedKafka` (in-JVM broker, no Docker,
  no DB). `AlertReceivedResilienceTest` loads a **sliced context** — the real `KafkaConfig` (error
  handler, DLT recoverer, delegating serializer, type-header notification factory) + Boot's Kafka
  auto-config + both listeners, with `IncidentService` `@MockBean`'d so no JPA/datasource loads. Four
  tests prove the infra behavior unit tests can't reach:
  - **poison-pill** (un-deserializable payload) → routed to `alert.received.v1.DLT` as raw bytes (the
    `byte[]` delegate);
  - **application exception** (value already deserialized to an `AlertReceivedEvent`, `handle` throws
    `RoutingUnavailableException`) → after the bounded retries, dead-lettered via the `Object`→JSON
    delegate. This is the **regression guard for the Phase 10 T1 `assignable=true` DLT-serializer fix**;
  - **handled event** → not dead-lettered (and the engine is invoked);
  - **`notification.delivered.v1`** with a `__TypeId__` header → dispatched through the type-header
    container factory to the feedback listener (`recordDelivered`).
  - **Net verified by mutation**: flipping `DelegatingByTypeSerializer(..., true)` → `false` made
    **exactly** the application-exception DLT test fail (the poison-pill byte[] still routed), then
    reverted → green.
  - **Scope (deliberate):** the `processed_event` ledger idempotency is NOT integration-tested at the
    Kafka level — it needs a real DB (the slice has none), and the `handle()` `existsById`→no-op guard is
    already unit-tested in T1 plus exercised in the live-fleet e2e. A real-PG ledger redelivery test is a
    candidate compose-PG slice if it adds value later.
- **T3 (DONE)** - **escalation-service**. `EscalationServiceTest` (11, pure Mockito, real
  `SimpleMeterRegistry`): the **task materialization repeat math** — `repeat_count=1` (2 rounds) × 2
  levels → 4 tasks at `triggeredAt + round*roundSpan + delay` (offsets 0/300/300/600), never previously
  tested; the five scheduling guards (null policy, tasks-already-exist, policy-not-found, no-rules,
  idempotent-on-eventId); cancel-on-close (pending → CANCELED + `saveAll`) + idempotency;
  `fireDueTask` target resolution (USER → one `notification.requested` + EXECUTED; claim returns empty →
  skip, no outbox append; TEAM → one request per member). `EscalationTaskClaimTest` (1, compose-PG,
  `assumeTrue`-skip): the `FOR UPDATE SKIP LOCKED` exactly-one-claimer proof (A claims + locks → B's
  concurrent claim SKIP-LOCKED → empty → A commits EXECUTED → B retry still empty) — the **first
  automated proof of the Phase 11 T1 claim** (previously only a manual psql session). Lifecycle dispatch
  already covered by `IncidentEventListenerTest`.
  - **Net verified by mutation**: dropping the `round*roundSpan` offset made exactly the materialization
    test fail, then reverted → 17 green.
  - **Scope note:** the claim test **inlines** the claim SQL (the production `@Query` uses a `:id` named
    param that a raw-JDBC `PreparedStatement` can't share, unlike `OutboxRelay.CLAIM_SQL` which is a
    `JdbcTemplate` `?` string). So it guards the `SKIP LOCKED` exactly-one-claimer **semantics** on the
    `escalation_task` shape, not the literal production query string (a `@DataJpaTest` against real PG
    would, at much higher cost). Commented to mirror `findPendingForUpdate`.
- **T4 (DONE)** - **notification-service**. `DeliveryServiceTest` (7, pure Mockito, real
  `SimpleMeterRegistry`): the **retry/backoff decision** — success → DELIVERED + `notification.delivered.v1`
  + success counter; first failure → retry at `now + 1×backoff` (status stays PENDING, no terminal event);
  second failure → `2×backoff` (proves the linear `attempts × backoff` scaling); attempts exhausted
  (`attemptJustMade >= max-attempts`) → FAILED + `notification.failed.v1` + failure counter; plus the
  guards (claim empty → skip, no sender for channel → FAILED without wasting retries, missing request →
  FAILED). `NotificationServiceTest` (3): the fan-out — one PENDING delivery per **enabled** contact
  method (channel/destination asserted), no enabled → request recorded but zero deliveries, idempotent on
  the request event id. `NotificationDeliveryClaimTest` (1, compose-PG, `assumeTrue`-skip): the
  `FOR UPDATE SKIP LOCKED` exactly-one-claimer proof so the same email/webhook is never sent twice across
  replicas (mirrors `EscalationTaskClaimTest`; same inline-SQL scope note).
  - **Net verified by mutation**: flipping the terminal `attemptJustMade >= maxAttempts` to `>` made
    exactly the exhausted-attempts test fail (an extra retry instead of FAILED), then reverted → 11 green.
- **T5 (DONE)** - **integration-service + schedule edge**. `AlertNormalizerTest` (3, Mockito): the
  resolved tenant + `dedupKey = source:entityId` stamped onto the `AlertReceivedEvent` (org / integration /
  routingKey / messageType / severity / externalEntityId), the title fallback (`entityDisplayName ?? entityId`),
  and the resolve-before-persist ordering (an invalid key throws `InvalidIntegrationKeyException` and the
  writer is never touched — nothing stored). `AlertEventWriterTest` (1, Mockito, real Jackson): `persist`
  writes both the `raw_inbound_event` audit row and the `outbox` row, with `dedupKey` as both the outbox
  aggregate id and the message key (per-aggregate partition order); the `@Transactional` atomicity itself
  isn't unit-testable, so the two writes' arguments are asserted. `OnCallCalculatorTest` (+1):
  `dstSpringForwardKeepsLocalHandoffTime` — the **DST transition-instant edge the prior tests skipped**
  (Istanbul has no DST): Berlin springs forward 2026-03-29 02:00→03:00 (a 23h local day), and the 09:00
  **local** handoff still holds on that day (08:30 → period 1, 09:30 → period 2), proving the
  `ChronoUnit.DAYS`-on-`ZonedDateTime` count is DST-aware and doesn't drift by the lost hour.
  - **Net verified by mutation**: swapping `dedupKey` to `entityId:source` failed exactly the normalizer
    test, then reverted.
  - Note: required-field validation (`@NotNull`/`@NotBlank` on `WebhookRequest`) is Bean Validation
    enforced by Spring at the controller — a framework concern left to the e2e gate, not unit-tested here.

**Phase 13 complete (T1-T5).** The repo went from effectively 3 tests to **62**, covering §16's unit /
integration / contract strategy without Docker: domain logic via Mockito, Kafka resilience via
`@EmbeddedKafka`, DB-specific SQL (the `FOR UPDATE SKIP LOCKED` claims) via compose-PG `assumeTrue`-skip.
Every ticket was mutation-verified (a deliberate regression failed exactly the guarding test, then
reverted). Deferred (documented): no containerized full-stack e2e (the live-fleet manual run stays the e2e
gate); the claim tests guard the SKIP LOCKED semantics, not the literal production `@Query` string; the
`processed_event` ledger persistence is not integration-tested at the Kafka level.

## Phase 14 - Redis Activation (use Redis where it is correct)

Goal: put Redis to work. It has run in compose (+ a redis-exporter dashboard) since Phase 8 but is
**wired into zero services** — the §13 Redis plan is otherwise empty. This phase activates it for the two
use cases where Redis is the *right* tool, and deliberately skips the ones where it would regress or add
no measured value.

### Where Redis is correct vs. not (decided 2026-06-19)

Framed by engineering rule §3.2 "cache must never be the source of truth":

- ❌ **Distributed locks for scheduled workers** (§13 `lock:escalation-worker`) — already solved *better* by
  the Phase 11 `FOR UPDATE SKIP LOCKED` per-row claims (no lock-server dependency, no lease-expiry
  double-fire window). A Redis lock would be a regression. Not built.
- ❌ **Idempotency cache** (§13 `idempotency:*`) — the DB ledgers (`processed_event`,
  `notification_request` PK) are already authoritative and idempotent. Redis would be a redundant
  accelerator in front of a fast indexed lookup; marginal. Not built.
- ⚠️ **On-call cache** (§13 `oncall-cache:*`) — the on-call resolve is a DB-fast calendar computation; same
  "measured not worth it" lesson as the integration-key resolve cache (Phase 1a gap, dropped after the
  Phase-10 load measurement). Low value, **deferred** (revisit only if on-call resolve ever shows up hot).
- ✅ **Rate limiting** (§13 `rate-limit:*`, §15 "rate limit integration endpoints") — the integration
  ingest endpoint is currently unprotected; a single misbehaving integration can flood the pipeline.
  Redis-native (atomic counter + TTL window), and the gateway is the right enforcement point. **T1.**
- ✅ **Notification cooldown** (§13 `notification-cooldown:*`) — genuine Phase 6 product gap (no throttle /
  per-incident dedup of repeat pages). Redis-native (key + TTL). Delivery state stays in PG (source of
  truth); Redis only *suppresses a redundant page* within a window. **T2.**

### Ticket breakdown

Sliced per concern; **T1 first**, verified + reviewed before T2 is specced in detail.

- **T1 (SPEC)** - **rate limit the integration ingest endpoint** (api-gateway + Redis). Spring Cloud
  Gateway's built-in `RequestRateLimiter` filter backed by `RedisRateLimiter` (token-bucket Lua script,
  atomic in Redis) on the integration route only. **Key = the `{integrationKey}` path variable** (a
  `KeyResolver` bean reading it from the request path) — each integration is throttled independently, a
  natural tenant boundary, and no identity resolve is needed before the limit (the limiter runs before the
  downstream resolve). Over-limit → **429** with `X-RateLimit-*` headers (gateway default); within limit →
  forwarded unchanged. `replenishRate` / `burstCapacity` configurable via yml (conservative dev defaults).
  Gateway adds `spring-boot-starter-data-redis-reactive` + the gateway is already reactive (WebFlux), so
  the reactive Redis client fits. Redis connection via `spring.data.redis.*` (compose `redis:6379` / host
  `localhost:6379`).
  - Acceptance: a burst above `burstCapacity` on `POST /v1/integrations/{key}/events/{routingKey}` → some
    202s then 429s; two different integration keys are limited independently (one keyed-out does not throttle
    the other); Redis down → decide fail-open vs fail-closed explicitly (default **fail-open** for ingest
    availability, logged) and verify; counters visible in Redis (`rate-limit:*` keys with TTL). Verified on
    the live fleet against real Redis.
- **T2 (SPEC)** - **notification cooldown** (notification-service + Redis). Collapses repeat pages for the
  same `(incidentId, recipientUserId, channel)` within a window — escalation can request a notification at
  multiple levels/rounds for one incident+user, and without a cooldown each becomes a separate page. The
  check sits in `NotificationService.onNotificationRequested`'s **fan-out loop**, per enabled contact method
  (the channel is known there), *before* the `NotificationDelivery.pending(...)` save:
  - A new `CooldownService` wraps a `StringRedisTemplate`. `tryReserve(incidentId, userId, channel)` does
    `SET notification-cooldown:{incidentId}:{userId}:{channel} <ts> EX <window> NX`
    (`opsForValue().setIfAbsent(key, ts, Duration)`): returns **true** (reserved → proceed, create the
    delivery) or **false** (key already present → **suppress**, create no delivery).
  - **Suppressed** → increment a `notification_cooldown_suppressed_total` counter (mirrors the existing
    `notification.delivery.*` meters) + a warn log carrying incident/user/channel. Visibility is metric + log
    (a timeline event would need a cross-service publish back to incident-service — out of scope for T2;
    noted as a possible follow-up).
  - **Fail-open:** any Redis error in `tryReserve` is caught → returns true (proceed). Cooldown is a
    suppression optimization, never the source of truth (engineering rule §3.2); a Redis outage must not stop
    real pages.
  - Config: `notification.cooldown.enabled` (default true; false → `tryReserve` always true) +
    `notification.cooldown.window-seconds` (default 60). `spring.data.redis.host/port` added (servlet
    service → non-reactive `spring-boot-starter-data-redis` / lettuce).
  - **Idempotency interaction:** the existing request-id `existsById` guard runs *before* fan-out, so a
    redelivered request never re-reserves the cooldown (it short-circuits). The reserve happens once per
    request processing.
  - **Accepted trade-off (documented):** the reserve writes to Redis inside the fan-out `@Transactional`;
    Redis is not enlisted in the DB tx, so a (rare) rollback after a reserve leaves the cooldown key set
    until it expires (could suppress the next legit page within the window). The fan-out tx only does local
    DB saves (low rollback risk) and the key self-expires; not worth a compensating delete. Two-phase reserve
    is the deferred evolution if it ever bites.
  - Acceptance: two `notification.requested` for the same incident+user+channel within the window → first
    creates a delivery, second is suppressed (no second delivery, counter +1); after the window expires a
    third is delivered again; a different channel (or different incident/user) is never suppressed by another
    key; `cooldown.enabled=false` → never suppressed; Redis down → fail-open (delivery still created). Verify
    on the live fleet against real Redis.

## Phase 15 - Outbox Poison-Row Dead-Lettering (close the open audit finding)

Goal: a single unrelayable outbox row must never stall a service's entire event publishing. The
`common-outbox` relay claims PENDING rows oldest-first (per-aggregate ordering guard) and, on any send
failure, `break`s the round and leaves the row PENDING for the next poll. That is correct for a transient
broker outage but **fatal for a poison row** — a row that can never succeed (corrupt `headers` JSON →
`parseHeaders` throws; payload over `max.message.bytes`; unknown topic). Since the poison row is the
lowest-id PENDING, every poll re-claims it first, fails, and `break`s — so **every row behind it (even a
different aggregate) is never published**. One bad row = the service's outbox is permanently down. There
was no max-attempts / DLT (open audit finding since Phase 10).

**Reproduced first (characterization test):** drove the real `OutboxRelay.relay()` against compose-PG
with a corrupt-headers row as the lowest id and a good row (different aggregate) behind it. After 5 poll
cycles the poison row was still PENDING (attempts=5, never dead-lettered) and the good row was still
PENDING with attempts=0 — `kafka.send` was never called for *any* row. Total stall confirmed, not
theoretical. The test was then flipped to assert the fixed behavior.

- **T1 (DONE)** - **poison-row dead-lettering in the `common-outbox` relay**. Failure is classified, not
  blindly retried:
  - **Pre-send poison** (corrupt `headers` → `parseHeaders` throws): the row is flagged `DEAD` immediately
    and the loop `continue`s — it never blocks the rows behind it.
  - **Permanent broker rejection** (`RecordTooLargeException`, `SerializationException`,
    `InvalidTopicException`, `UnknownTopicOrPartitionException`; the cause chain is scanned): no retry can
    fix it → `DEAD` + `continue`.
  - **Transient failure** (broker outage / timeout): unchanged behavior — `attempts++` and `break` the
    round (the rest of the batch almost certainly fails the same way; spinning wastes the poll; the row
    stays PENDING and retries next poll). **Backstop:** once a transiently-failing row burns through
    `heimcall.outbox.max-attempts` (default **10**) it is dead-lettered anyway, so an *unforeseen*
    always-failing row (a permanent error we did not classify) can still never stall forever.
  - **DLT mechanics:** in-table `status='DEAD'` — no new table, no new Kafka DLT topic, no Flyway (the
    `status` column already exists; `DEAD` is just a new value). The relay's claim/ordering SQL already
    filters on `status='PENDING'`, so DEAD rows are invisible to it and the per-aggregate ordering guard
    (`NOT EXISTS lower-id PENDING`) stops counting a dead-lettered row → the aggregate's next row becomes
    eligible (dead-lettering *unblocks* the aggregate, at the documented cost of dropping that one event
    from the stream). A `outbox_dead_total` Micrometer counter (optional `MeterRegistry`, injected via
    `ObjectProvider` so the lib stays dependency-light) + a loud ERROR log give visibility; alert on it.
    A DEAD row is replayable by flipping it back to PENDING after the cause is fixed.
  - **Accepted trade-off (documented):** a *total* broker outage longer than
    `max-attempts × per-poll-time` can dead-letter the head-of-line row (it looks identical to an
    unforeseen poison row from the relay's side). With the 10s publish-timeout this is ~100s+ of full
    outage before the head row is sacrificed; the row is replayable and the counter/log fire. Tightening
    the false-positive window (e.g. only counting non-connectivity failures toward the backstop) is the
    deferred evolution if it ever bites.
  - Config: `heimcall.outbox.max-attempts` (default 10).
  - Acceptance (verified on compose-PG, real `OutboxRelay`): corrupt-headers row as lowest id → flagged
    `DEAD` (attempts=1, counter +1) and the good row behind it is PUBLISHED in the *same* round
    (`kafka.send` called only for the good row); a transiently-failing row stays PENDING and breaks the
    round (good row behind it correctly starved — outage semantics) for `maxAttempts-1` polls, then is
    dead-lettered on the `maxAttempts`-th and the good row flows; the Phase 12 per-aggregate ordering
    regression still passes. The characterization test was mutation-proven (it failed on the pre-fix
    relay, passes on the fixed one).

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
