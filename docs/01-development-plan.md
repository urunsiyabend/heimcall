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

## Phase 16 - Security Hardening (asymmetric issuer + scoped service identity + NetworkPolicy)

Goal: close the two highest-risk open security findings as one coherent trust model. Today (1) all services
verify user JWTs with the **same shared HS256 secret** (`HEIMCALL_JWT_SECRET`) — any service that can
*verify* a token can also *forge* one (symmetric-MAC blast radius; OWASP/RFC 8725 both call this out), and
(2) every `/v1/internal/**` endpoint plus `POST /v1/integration-keys/resolve` is `permitAll()` with
inter-service `RestClient` calls carrying **no credentials at all** — anything on the pod network reads
routing / on-call / membership data, and `key-resolve` is even reachable through the gateway from outside
(`identity` route `/v1/integration-keys/**`), unauthenticated.

These two findings are one finding: the fix for the symmetric-secret problem (a single **asymmetric**
issuer) is also the foundation that makes per-service, audience- and scope-bound **service tokens**
possible. So the phase is sequenced trust-spine first, then identity, then enforcement, then network.

**Design decisions (locked 2026-06-23):**
- **Single issuer, asymmetric.** `identity-service` holds one RSA private key and is the *only* signer of
  both user tokens and service tokens. Everyone else verifies with the public key (JWKS) and can never mint.
  This is the whole point — it removes the "any verifier can forge" property of the shared HS256 secret.
- **One `iss`, distinguish by claims** (RFC 9068 JWT access-token profile). Same `iss` for both token
  classes; verifiers separate them by `token_use` (`user_access` / `user_refresh` / `service`), target by
  `aud`, and operation by `scope`. User access token: `aud=heimcall-api`, `token_use=user_access`. Service
  token: `aud=<callee-service>`, `token_use=service`, `scope=<dotted-perm>`, plus `sub=<caller-service>`
  and `jti` (audit/trace; `jti` is **not** replay/revocation on its own — that is a deferred mechanism).
- **RS256-only verification.** Verifiers pin `alg=RS256` and reject `alg=none`, HS256, and any
  header-chosen algorithm — this is the alg-confusion defense (attacker feeding the public key as an HMAC
  secret), the single most important hardening in T1 (RFC 8725 BCP).
- **Hard cut, no dual-accept window.** Heimcall is pre-production (no live user base; 1h access / 30d
  refresh). A transition window where verifiers accept *both* HS256 and RS256 would keep the forge risk
  open for the duration. Instead, cut over in one change: HS256 acceptance is removed entirely, no verifier
  ever honors both. Cost: existing refresh tokens are invalidated → re-login. Acceptable pre-prod; it is the
  strictly safer path and avoids the migration ceremony a live system would need.
- **`X-User-Id` is never a trust boundary.** A user-context internal call (e.g. membership check) carries
  two identities — the service token (caller) *and* the user's access token — and the callee derives the
  user `sub` from the verified user JWT, not from a header.
- **Out of scope (next horizon, noted not built):** mTLS / SPIFFE-SPIRE workload identity, token
  revocation / introspection, sender-constrained tokens. NetworkPolicy (T4) is L3/L4 defense-in-depth, not
  an identity mechanism, and only enforced if the CNI honors it.

### Ticket breakdown

- **T1 (DONE)** - **RS256 + JWKS trust spine.** The foundation; T2 deliberately kept out. Delivered:
  - `common-security` was split out of the old single HS256 `JwtSupport` (deleted) into: `JwtKeys` (signer's
    RSA key holder — active + retired `kid`, produces the JWKS), `JwtIssuer` (RS256 sign with `kid` header,
    `iss`/`aud`/`token_use`), `JwtVerifier` (verify), `PublicKeyResolver` with `LocalKeyResolver` (signer,
    no self-HTTP) and `JwksKeyResolver` (HTTP, cached, refetch-on-unknown-`kid`, single-flight), plus
    `PemKeys` (PKCS#8/X.509 PEM, tolerates `\n` escapes) and `JwtClaims` (constants).
  - `identity-service` is the sole signer: loads the RSA private key (dev key committed at
    `resources/dev/jwt-dev-private-key.pem`; **prod: `HEIMCALL_JWT_PRIVATE_KEY` from a Secret/KMS**), signs
    user access + refresh tokens RS256 with the active `kid`, and exposes `GET /v1/.well-known/jwks.json`
    (active + retired public keys) + `GET /v1/.well-known/oauth-authorization-server` metadata.
  - **Algorithm allowlist pinned in code** (RFC 8725 §3.1): `JwtVerifier.Rs256KeyLocator` fixes the accepted
    alg to the constant `RS256` and rejects anything else *before* signature checking — `alg=none`, HS256,
    and even a cryptographically valid RS384/RS512 token are refused. The token `alg` header only selects a
    rejection, never the verification algorithm; the JWK `alg`/`use` members are advisory and never consulted.
    Also validates `iss`/`exp`/`nbf`/`aud` and `token_use` (a refresh token can never authenticate a resource
    endpoint).
  - The shared `HEIMCALL_JWT_SECRET` is **removed everywhere (hard cut)**: identity keeps only the private
    key; the six other services keep only `heimcall.jwt.jwks-uri`. Helm updated (`jwt-private-key` secret +
    `HEIMCALL_JWT_KEY_ID` on identity; `HEIMCALL_JWT_JWKS_URI` on every verifier).
  - Acceptance verified — unit (`JwtT1Test`, 10): issue/verify, `alg=none`, HS256-forge, validly-signed
    RS512 rejected by the allowlist (not a sig failure), unknown `kid`, expired, wrong-`aud`,
    refresh-as-access, rotation active→retired overlap, JWKS produce→consume round-trip. Runtime (identity +
    incident on real Kafka/PG): JWKS serves RS256/`kid`; login mints an RS256 token (`iss`/`aud`/`token_use`
    correct); `/me` 200; no-token/garbage/refresh-as-access → 401; refresh endpoint 200; **cross-service**
    incident verified the token via identity's JWKS over HTTP (valid → 403 authz, not 401), garbage → 401,
    HS256-forged with the real `kid` → 401.

- **T2 (SPEC)** - **Service identity / client-credentials issuance.** `identity-service` mints short-lived
  RS256 service tokens. Scope:
  - A token endpoint authenticated by a **per-service** credential (`client_id` + secret, sourced from a
    Kubernetes Secret, compared constant-time — **not** one shared token). Decide between a hand-rolled
    `POST /v1/internal/token` vs. a spec-shaped OAuth2 `client_credentials` / `client_secret_basic` endpoint
    so Spring Security's `OAuth2AuthorizedClientManager` can consume it on the client side (less custom
    code, more standard) — finalize in this ticket.
  - Issued service token: `iss` (same as user tokens), `sub=<caller-service>`, `aud=[<callee-service>]`,
    `scope=<space-delimited dotted perms>`, `token_use=service`, short `exp`, `jti`.
  - Define the **aud/scope matrix** — per internal endpoint, the required `aud` (the callee) and `scope`:
    ```text
    identity.membership.read         incident,escalation -> identity  (member check)
    identity.team-members.read       escalation          -> identity  (team roster)
    identity.integration-key.resolve integration         -> identity  (key resolve)
    catalog.routing.resolve          incident            -> catalog   (routing)
    schedule.on-call.read            escalation          -> schedule  (on-call)
    escalation.policy.read           catalog             -> escalation (policy exists, server-side validate)
    ```
    so `integration`'s token (aud=identity, scope=integration-key.resolve) cannot call routing, and a
    `catalog` token cannot resolve integration keys.
  - Acceptance: each caller obtains a service token scoped to exactly the callee+operation it needs; a token
    minted for one `aud` is rejected at another; an out-of-scope call is rejected; per-service credentials
    are independently rotatable; the dev secret default is weak-and-visible (override in prod).

- **T3 (DONE)** - **Enforce on internal endpoints + client wiring.** `permitAll` replaced with real authz.
  - **Decision: pure service-token, no dual-token.** The locked design carried a user-context variant
    (`Authorization: Bearer <user>` + `X-Internal-Authorization: Bearer <service>`). It was dropped because
    the actual internal surface doesn't need it: **no internal endpoint derives the acting user from a
    header** — every one takes the user/team as an explicit path/param (`/members/{userId}` etc.) answering a
    pure data lookup, and the call sites are **machine-context** (incident's routing resolve runs in the Kafka
    alert→incident handler, integration's key-resolve under key-authenticated ingest, escalation's engine on
    scheduled work) where no user JWT exists. So every internal call carries only
    `Authorization: Bearer <service-token>`. (Documented: a future header-derived-user endpoint would have to
    re-introduce the user JWT.) The "forged `X-User-Id`" acceptance holds by construction — internal endpoints
    never read that header and the filter strips it on the service-token path.
  - `common-security`: a service token addressed to this service is now accepted by `JwtAuthenticationFilter`
    (new `heimcall.jwt.service-name` = this callee's `aud`), which maps the token's `scope` claim to
    `SCOPE_*` authorities and sets the caller (`sub`) as principal — **no `X-User-Id` injected**.
    `/v1/internal/**` and `key-resolve` flipped from `permitAll` to `authenticated()`; the exact scope is
    pinned per endpoint with method security (`@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('SCOPE_…')")`).
    A user token authenticates but has no `SCOPE_*`, so it is rejected at method security (403) — internal
    endpoints are machine-only.
  - **Scope per endpoint:** `members/{userId}`→`identity.membership.read`, `teams/{teamId}`→
    **`identity.team.read`** (new scope; team-existence kept distinct from member-existence),
    `teams/{teamId}/members`→`identity.team-members.read`, key-resolve→`identity.integration-key.resolve`,
    catalog routing→`catalog.routing.resolve`, escalation policy→`escalation.policy.read`,
    schedule on-call→`schedule.on-call.read`.
  - **T2 matrix gaps closed** in `AuthorizationServerConfig.CLIENT_SCOPES`: `catalog` gained
    `identity.membership.read` + `identity.team.read`; `schedule` and `notification` (absent in T2 yet both
    call identity membership) added with `identity.membership.read`; `escalation` gained `identity.team.read`.
  - Client side: each caller pulls `spring-boot-starter-oauth2-client` and declares one
    `spring.security.oauth2.client.registration.<callee>` per callee (named after the callee, scopes targeting
    only it — honoring the single-audience invariant). A shared `ServiceTokenClients` factory +
    `ServiceTokenInterceptor` (new in common-security, on its own `@AutoConfigureAfter` the OAuth2-client
    autoconfig so `@ConditionalOnBean(ClientRegistrationRepository)` resolves) attaches the right token to each
    `*Client`. Cache + refresh-before-`exp` come from Spring's
    `AuthorizedClientServiceOAuth2AuthorizedClientManager` (machine-context, client_credentials).
  - Acceptance verified — unit (`JwtT3Test`: filter maps scope→authorities, wrong-`aud`→unauthenticated,
    caller-only service never accepts a service token, spoofed `X-User-Id` stripped) + `InternalEndpointAuthzTest`
    (identity, real `/oauth2/token` mint → gated `/v1/internal/.../members`: no-token→401, wrong-`aud`→401,
    wrong-scope→403, correct→204) + full suite green. Runtime (real PG/Kafka, bootJars): identity gate via curl
    (no-token→401, correct scope→handler, wrong-`aud`→401, wrong-scope→403, key-resolve no-token→401,
    key-resolve scoped→handler); callers boot clean with the new wiring; **interceptor proven end-to-end** —
    integration ingest → real identity call, and with a deliberately wrong client secret the stack trace shows
    `ServiceTokenInterceptor → AuthorizedClientManager.authorize → token mint → 401 invalid_client`, proving the
    interceptor genuinely mints + attaches a `client_credentials` token.
  - **Deferred to T4 (helm/k8s ticket):** the chart's T3 env wiring (per-caller `HEIMCALL_CLIENT_SECRET_<self>`
    + `HEIMCALL_TOKEN_URI`, callee `HEIMCALL_SERVICE_NAME`, `schedule`/`notification` service-client secrets)
    and the full-fleet **kind e2e** with every hop tokened — folded into T4 since both touch helm and are
    validated by T4's single kind e2e run. The gateway-never-routes-`/v1/internal` test lock moves with it
    (key-resolve is now scope-gated regardless of its gateway route).

- **T4 (DONE)** - **NetworkPolicy default-deny (helm) + T3 helm wiring.** Defense-in-depth, last. Treated as
  a connection inventory, not a manifest-writing exercise. **Locked decisions (2026-06-23):** policy-aware
  CNI mandatory (kindnet ignores NetworkPolicy) → verify on **Cilium + Hubble**; full **per-pair**
  least-privilege (each inter-service REST dependency is its own allow rule, not a blanket mesh-allow);
  infra egress allowed **by port** (BYO/external Postgres/Kafka/Redis/OTLP stay reachable); notification's
  outbound webhook egress is the **documented SSRF-protected exception** (no finite FQDN set for arbitrary
  customer webhooks → public internet on 80/443 with private/cluster/link-local CIDRs denied). Delivered:
  - `templates/networkpolicy.yaml` (gated `networkPolicy.enabled`): **18 policies** — fleet default-deny
    ingress+egress; DNS egress (kube-system:53); infra egress by port (5432/9092/6379/4318); per-service
    **ingress from its actual callers only** + **egress to its declared callees only**, generated from a
    `calls` graph in `values.yaml` (the token-mint hop = caller→identity, already in every caller's `calls`);
    gateway external ingress; configurable Prometheus ingress; notification SSRF webhook egress + SMTP 1025
    via `extraEgressPorts`. Stable pod label `app.kubernetes.io/part-of=heimcall` for fleet selection.
  - **Deferred T3 helm wiring carried here:** per-caller `HEIMCALL_TOKEN_URI` + `HEIMCALL_CLIENT_SECRET_<self>`
    (from the secret), callee `HEIMCALL_SERVICE_NAME`, and `schedule`/`notification` in
    `secrets.serviceClientSecrets`. Driven by per-service `clientName`/`serviceName` markers in values.
  - **Redis wired** (was absent from helm entirely): `redis` in `deploy/kind/infra.yaml`, `infra.redis*`,
    `REDIS_HOST/PORT` env for notification + api-gateway.
  - **Gateway invariant test** `InternalRouteIsolationTest`: `/v1/internal/**` + `/oauth2/token` 404 at the
    gateway (unrouted), a real route 5xx (routed) — internal surface is pod-to-pod only.
  - **kind/Cilium scaffolding**: `deploy/kind/cluster.yaml` (disableDefaultCNI) + `README-netpol.md`
    (Cilium 1.19.5 + Hubble install, archive image-load workaround for the containerd-snapshotter).
  - Acceptance verified — `helm lint`/`template` clean (37 resources, 18 NetworkPolicies); full suite green
    incl. the new gateway test. **Real-cluster e2e on Cilium+Hubble** (kind, full fleet, fresh RS256 images —
    found+fixed stale pre-Phase-16 HS256 images mid-run): full fleet `Running 1/1` under default-deny (no
    flow starved); product flow register→org→membership→key→ingest→**TRIGGERED**→ack→**RESOLVED** with the
    live token hops (integration→identity key-resolve, incident→catalog routing); **negative** —
    notification→identity:8083 connects (allowed), notification→incident:8082 **dropped** (timeout), Hubble
    logs `Policy denied DROPPED (TCP Flags: SYN)`. Documented: enforcement depends on the CNI; webhook-SSRF +
    SMTP rules present but not live-exercised (no webhook/contact-method in the flow); Prometheus ingress
    rule present but `monitoring` ns not deployed.

**Phase 16 complete** (T1-T4).

## Phase 17 - Routing Rule Engine (conditional, ordered routing)

Goal: replace today's flat `routingKey -> service -> escalation policy` map (service-catalog-service,
plus the Phase 10 T2 org-default catch-all) with an **ordered, conditional routing rule engine**. Real
alerts must route on more than a single key — severity, source, message type, payload metadata, and
time-of-day. The engine is a **deterministic decision table**, not a general-purpose business-rules
engine: an event comes in, rules are evaluated in order, the first match selects the target, and a
pinned fallback runs if nothing matches. Routing produces exactly one target (a service + escalation
policy); notification fan-out to multiple targets stays a separate, future concern.

### Design decisions (locked 2026-06-24, research-driven — see Research notes below)

- **Deterministic decision table, first-match-wins.** Rules are ordered by `position`; the first rule
  whose condition matches selects the target and evaluation stops. This is the industry norm for the
  *routing decision* (PagerDuty Router, Opsgenie routing rules, Datadog On-Call, Grafana routes). The
  "all matching rules apply / merge recipients" behavior (Datadog monitor notification rules, PagerDuty
  rule "continue") is a **notification fan-out** concern, explicitly NOT part of routing here.
- **Structured typed condition tree, not an expression language.** Conditions are a nestable
  `ALL`(AND) / `ANY`(OR) / `NOT` group tree with typed `field / operator / value` leaves, persisted as
  JSON. No free-text expression language in this phase — it is injection-free, UI-buildable, and
  validatable at save time. The evaluator sits behind a `RoutingPredicateEvaluator` interface so a
  `CelPredicateEvaluator` (Google CEL) can be added later as an advanced "expression mode" escape hatch
  if a real need appears. **CEL stays backstage** — direct tree interpretation makes explainability
  ("rule 3 didn't match because `metadata.env` existed but wasn't `prod`") far easier and avoids a
  second semantic layer (tree -> CEL coercion/null/regex differences).
- **The catch-all is NOT a rule — it is a separate `fallbackAction`.** In the UI it looks like a pinned
  last row, but in the data model it is a distinct field on the ruleset, not an entry in the ordered
  rule list. This makes it undeletable / non-reorderable, makes every published ruleset **total**
  (always produces an outcome for any event), and keeps "empty-condition normal rule" from being
  confused with the system fallback. `fallbackAction` defaults to the existing Phase 10 T2 org routing
  default if configured, else `UNROUTED` (Phase 10 T3 behavior — visible, counted, never silent).
- **Missing / null / type-mismatch semantics are the load-bearing decision, not the operator list.**
  Heimcall deliberately does NOT inherit PagerDuty's negative-operator gotcha (where "does not equal"
  also matches events missing the field). Rules:
  | Situation | Result |
  | --- | --- |
  | positive leaf (`EQUALS`, `CONTAINS_*`, `STARTS_WITH`, `MATCHES_REGEX`, `GT`/`LT`/...), field **missing** | `false` |
  | `NOT_EQUALS` / `NOT_CONTAINS_*` / `NOT_MATCHES_REGEX`, field **present** and condition not met | `true` |
  | the same negative leaf, field **missing** | `false` (does NOT match on absence — use `NOT_EXISTS` for that) |
  | `EXISTS` | `true` iff the field is present (even if value is null) |
  | `NOT_EXISTS` | `true` iff the field is absent |
  | value comparison where the field is **null** | `false` (null is distinct from missing; both are non-matching for value ops) |
  | type mismatch (e.g. `GT` on a non-numeric value) | `false`, and recorded in the decision trace |
  So a user writing a negative condition does NOT have to manually pair it with `exists`.
- **Typed field references, not a free JSONPath string** (prevents a JSONPath mini-language leaking into
  the product). Two field kinds over the normalized `AlertReceivedEvent`:
  - `SYSTEM` (`name` in: `routingKey`, `source`, `messageType`, `severity`, `externalEntityId`,
    `title`, `description`)
  - `METADATA` (`key` = an arbitrary key from the event `metadata` map)
- **Operator set** (string-typed unless noted): `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`,
  `CONTAINS_SUBSTRING`, `NOT_CONTAINS_SUBSTRING`, `STARTS_WITH`, `ENDS_WITH`, `EXISTS`, `NOT_EXISTS`,
  `MATCHES_REGEX`, `NOT_MATCHES_REGEX`, and numeric `GT` / `GTE` / `LT` / `LTE` (value coerced to a
  number; mismatch -> `false` + trace). `IN`/`NOT_IN` take a list value. `messageType` and `severity`
  match against the `common-domain` enums.
- **Regex uses RE2J** (`com.google.re2j`, linear-time, no catastrophic backtracking / ReDoS), NOT
  `java.util.regex`. Patterns are compiled and size/complexity-limited **at save time**, never compiled
  per-event on the hot path.
- **Time-of-day conditions**: a rule may carry an optional time restriction (day-of-week + local time
  window), evaluated in the **organization's timezone** at decision time (IANA zone, DST-aware,
  midnight-spanning windows allowed). Reuses the timezone discipline proven in `schedule-service`.
- **Authoring vs evaluation are separate concerns.** *Authoring* — rule CRUD, validation, preview,
  authoritative storage — is **always service-catalog's job**; incident-service never authors, validates,
  or owns rules. *Evaluation* — "run this event against the rules, pick the target" — is a pure
  computation whose *location* depends on the ticket. So the `routing-core` evaluator starts as a
  **module inside service-catalog-service** (T1: catalog is the only evaluator). It is **extracted to a
  shared lib `libs/routing-core` only in T2**, when incident-service must evaluate the replicated ruleset
  locally — and then ONLY the pure condition-model types + tree evaluator are shared (no Spring/JPA, no
  CRUD/validation/storage, which stay catalog-only). It is shared then purely so incident's local
  evaluation is byte-identical to catalog's preview (a second, drifting implementation would make
  preview disagree with production routing). No shared lib before that second consumer exists.
- **Consistency over availability for the routing decision.** During a catalog outage T1 fails safe to
  DLT (delayed-but-correct + durable via Kafka/outbox), never a guessed misroute — a misrouted real
  incident is exactly the silent paging black hole Phase 10 was built to kill. T2 dissolves the
  trade-off entirely by replicating the ruleset and evaluating locally (see T2).
- **Explainability**: every routing decision stamps `matched_rule_id` + `ruleset_version` on the
  incident and emits a `ROUTED` timeline detail; the human-readable timeline line stays short
  ("Routed to Payments via rule 'Production payment alerts' (ruleset v12)"); the full per-predicate
  trace is returned only by the dry-run preview endpoint, so the production timeline does not bloat.

### Ticket breakdown

- **T1 (DONE, 2026-06-25) - Engine + control plane in service-catalog (incident-service unchanged, sync, consistency-first).**
  - New `routing` module/package **inside service-catalog-service** (NOT a shared lib yet — catalog is the
    only consumer in T1): condition-model types (`ConditionNode` = `Group{op: ALL|ANY|NOT, children}`
    or `Leaf{field, operator, value}`; `FieldRef{kind: SYSTEM|METADATA, name/key}`; `Operator` enum;
    `RoutingAction{type: ROUTE|UNROUTED, serviceId, escalationPolicyId}`; `Ruleset{version, rules[],
    fallbackAction}`; `RoutingContext` = the matchable projection of `AlertReceivedEvent`), the
    `RoutingPredicateEvaluator` interface + a `TreeRoutingEvaluator` (first-match, the missing/null/type
    table above, RE2J for regex, org-timezone time restrictions), and a `RoutingDecision{serviceId,
    escalationPolicyId, matchedRuleId, rulesetVersion, unrouted, trace}` result with an optional
    per-predicate trace (off on the hot path, on for preview). Keep these types Spring/JPA-free from the
    start so the T2 extraction to `libs/routing-core` is a move, not a rewrite.
  - service-catalog persistence (Flyway `V4__routing_rules.sql`): `routing_ruleset` (PK `organization_id`,
    `version` bigint monotonic, `published_at`, `fallback_service_id` nullable, `fallback_policy_id`
    nullable — null fallback = `UNROUTED`); `routing_rule` (`id`, `organization_id`, `position` int,
    `name`, `enabled` bool, `condition_json` jsonb, `action_type`, `action_service_id`,
    `action_policy_id`, `created_at`). Any rule insert/update/delete/reorder **bumps**
    `routing_ruleset.version` for that org (so T2's snapshot events carry a monotonic version).
  - Rule CRUD (member-gated, on the gateway): `POST/GET/PUT/DELETE
    /v1/organizations/{orgId}/routing-rules`, plus `PUT .../routing-rules/order` (reorder) and
    `PUT/GET/DELETE .../routing-rules/fallback`. Referenced `serviceId`/`escalationPolicyId` validated
    (service in org; policy via the existing `EscalationClient`, unknown/foreign -> 409). Condition JSON
    validated at save (known field refs, operator/value type agreement, RE2J pattern compiles within
    limits) -> 400 on invalid. Shadowing warning (non-fatal): if an unconditional / always-true rule
    precedes others, flag the later ones as unreachable in the response.
  - Internal resolve becomes context-aware: replace `GET /v1/internal/.../routing?routingKey=` with
    `POST /v1/internal/organizations/{orgId}/routing/resolve` taking the full `RoutingContext` body,
    returning `RoutingDecision`. Still a service-token-gated internal endpoint (Phase 16 T3,
    `catalog.routing.resolve` scope). incident-service's `CatalogClient.resolve` is widened to send the
    context (built from the `AlertReceivedEvent` it already holds); everything downstream
    (`RoutingAvailabilityResolver`, `unrouted`, `routed_from_cache`) stays.
  - **Outage behavior (T1, consistency-first):** because routing is now a function of multiple event
    fields, the Phase 10 T4 `routing_cache` keyed by `routingKey` alone is **unsafe** (a different-field
    event would get a misrouted last-known route). So in T1 the cache fallback is **not** used for rule
    decisions: a catalog outage re-throws `RoutingUnavailableException` -> retry -> DLT (no orphan, no
    misroute). This is a deliberate, documented availability regression vs Phase 10 T4, restored
    correctly in T2. (The `routing_cache` table/code is left dormant, removed or repurposed in T2.)
  - Dry-run preview: `POST /v1/organizations/{orgId}/routing-rules/preview` with a sample event ->
    `RoutingDecision` **including the full trace** (which rule matched, and for each earlier rule why it
    did not), without creating an incident.
  - Migration of current behavior (in `V4` or a one-off): generate one `EQUALS routingKey -> {service,
    policy}` rule per `monitored_service` that has a `routing_key`; set `fallbackAction` from the
    existing `org_routing_default`. Existing `routing_key` column + `org_routing_default` stay
    (deprecated) so nothing breaks; the engine becomes the single resolution path.
  - incident-service: stamp `matched_rule_id` + `ruleset_version` on the incident (Flyway add columns),
    emit the `ROUTED` timeline detail, add a `routing_rule_matched_total{ruleId}` counter. UNROUTED /
    `routed_from_cache` behavior otherwise unchanged.
  - Acceptance: a CRITICAL-from-grafana and a WARNING with the **same** routingKey route to different
    policies; reordering rules changes the outcome (first match wins); no rule matches + fallback set ->
    fallback; none + no fallback -> UNROUTED (Phase 10 T3, unchanged); a time-restricted rule matches
    only inside its org-timezone window (incl. a DST and a midnight-spanning case); preview returns the
    matched rule + trace without an incident; a catalog outage -> DLT, no orphan, no misroute; the
    migrated flat mappings reproduce pre-Phase-17 routing.

- **T2 (DONE, 2026-06-25) - Local ruleset read-model in incident-service (catalog off the hot path; consistency AND
  availability).** Routing is `f(event, ruleset)`; the event is already local, so replicate the only
  remote input — the ruleset — and the catalog hot-path call disappears entirely.
  - service-catalog publishes the **full ruleset as a versioned snapshot** on every change:
    `routing.ruleset-published.v1`, key = `organization_id`, value = `{rulesetVersion, rules[],
    fallbackAction}` (a full snapshot, NOT a delta). Published via `common-outbox` (Phase 9) in the same
    transaction as the rule write -> never-lost / never-ghost. Snapshots make duplicate delivery
    harmless, out-of-order delivery resolvable, and a poisoned older version in the DLT a non-issue (a
    newer snapshot logically supersedes it — the DLT stops being a time bomb).
  - incident-service consumes it into a PG read-model (Flyway `V8`): `routing_ruleset_projection`
    (PK `organization_id`, `version` bigint, `payload_json` jsonb, `observed_at`, `state`). Upsert is
    **version-gated**: write only if `incoming_version > stored_version` (idempotent, out-of-order-safe).
    Persisting in incident-service's **own** PG (not catalog's — database-per-service holds) means a
    restart / scale-up / redeploy inherits populated state from the shared incident DB — **process-level
    cold start is gone**; only genuine first-population (empty system / new tenant / DB restore) needs
    hydration.
  - **Extract the T1 catalog `routing` module to a shared lib `libs/routing-core`** (pure condition-model
    types + `RoutingPredicateEvaluator`/`TreeRoutingEvaluator` only; CRUD/validation/storage stay in
    catalog). catalog and incident now both depend on it, so incident's local eval is byte-identical to
    catalog's preview. (Snapshot payload types may instead live in `common-events` since they cross the
    wire — decide at implementation; the point is one shared definition, no second evaluator.)
  - Hot-path resolution flips to **local evaluation** via `routing-core` against the projection — no
    per-event catalog call. A catalog outage no longer affects routing (only delays the *next* ruleset
    version). This generalizes the Phase 10 T4 `routing_cache` (a catalog-derived read-model already
    living in incident-service's PG) from "last route per key" to "the durable projection of the ruleset
    that produces the route" — and the Phase 10 T4 outage cache + the T1 DLT-on-outage behavior are
    both superseded and removed.
  - **Cold-miss lazy hydration**: if the projection for an org is absent on the hot path (UNINITIALIZED),
    do a one-time **synchronous pull** from catalog's resolve/snapshot API (never catalog DB, off the
    steady-state path), populate, then serve — self-healing without a readiness gate. If catalog is also
    down at that moment, fall to `UNROUTED` fallback (still visible/counted, never a misroute).
  - **Reconciliation / repair pull** (`@Scheduled`, low frequency, mirrors the Phase 10 T4
    `RoutingReconciliationJob`): catches missing snapshots, excessively stale projections, DB-restore
    gaps, and new tenants. Via catalog API, off the hot path; a snapshot/event race is resolved by the
    same version gate. (Catalog boot-publish is an optimization, NOT the correctness mechanism — this
    pull is.)
  - **Projection state is explicit and distinct** (so ops is not blind): `READY(version)` /
    `ABSENT_CONFIRMED` (org genuinely has no rules -> fallback) / `UNINITIALIZED` (not yet hydrated) /
    `STALE` (age beyond the freshness policy). These must NOT collapse into one `UNROUTED` metric.
  - **Freshness policy (explicit)**: define a max acceptable projection age; export an event-lag metric
    and `ruleset_version` + `observed_at`; behavior on excessive staleness = **keep routing on the
    last-known ruleset** (config is slow-changing; last-known is almost certainly still correct) **plus**
    raise a staleness alert — never fall back or drop solely due to staleness. Every routing decision
    records the `ruleset_version` it used.
  - Delivery guarantee: idempotent, **versioned at-least-once** (source outbox + version-gated upsert);
    do not chase exactly-once. incident-service advances the Kafka offset only after the DB upsert
    commits.
  - Acceptance: rule change in catalog -> snapshot event -> incident projection updated (version-gated;
    a replayed/older snapshot is a no-op); **catalog down -> routing still works** off the projection
    (only new versions delayed); restart/scale-up of incident-service inherits the projection (no
    cold-start gap, no re-pull); a cold/empty projection lazily hydrates from catalog then serves;
    reconciliation repairs a deliberately-missed snapshot; the projection state + freshness metrics are
    observable; every incident records the `ruleset_version` used.

- **T3 (deferred, spec later)** - control-plane niceties surfaced in research but out of T1/T2 scope:
  draft vs published rulesets (Save != change prod routing; Publish does), richer shadow/overlap
  analysis in the UI, and the optional CEL "advanced expression mode" behind the existing
  `RoutingPredicateEvaluator` seam.

### Research notes (researched 2026-06-24)

Method: four parallel web-research passes over official docs — PagerDuty (Event Orchestration / PCL /
legacy Rulesets), Opsgenie + Grafana OnCall/IRM, Datadog On-Call + Splunk On-Call/VictorOps +
incident.io, and JVM rule-engine / expression-language design patterns. Captured as a decision record:

- **Q: First-match-wins or all-matching-rules-apply?**
  Decision: **first-match-wins** for the terminal routing decision; fan-out is a separate future layer.
  Evidence: PagerDuty Router "routes to the Service based on the first rule that matches" + required
  `catch_all`; Opsgenie "the first matching routing rule is applied", one rule applies; Datadog On-Call
  rules "evaluated top to bottom", last rule a mandatory fallback; Grafana "first matching route".
  Rejected: merge-all-matching (Datadog *monitor notification rules* merge+dedupe recipients; PagerDuty
  rule "continue") — that is notification fan-out, not a routing decision.
- **Q: Catch-all as a normal rule, or a separate construct?**
  Decision: a separate `fallbackAction`, not an entry in the rule list (pinned in UI only).
  Evidence: PagerDuty `catch_all` is a distinct block, not a rule; Opsgenie default `match-all` rule is
  flagged `is_default`; Grafana `is_the_last_route`. A separate construct makes the ruleset total and
  keeps the DB invariant clean. Rejected: "empty-condition rule pinned last" (conflates system fallback
  with a normal rule).
- **Q: Expression language (CEL/SpEL/...) or a structured condition model?**
  Decision: **structured typed condition tree** now; CEL behind an interface as a later escape hatch.
  Evidence: the UI-authored norm is a structured field/operator/value tree persisted as JSON
  (json-rules-engine; react-querybuilder, which can even export to CEL); Martin Fowler's RulesEngine
  bliki cautions that rules-engine flow becomes "very hard to maintain" and recommends a narrow
  domain-specific engine. CEL (cel.dev, google/cel-java) is genuinely safe (non-Turing-complete,
  terminating, side-effect-free, host-data-only, statically type-checked) and the right *future*
  expression layer, but a tree interpreter gives easier explainability and avoids a second semantic
  layer. Rejected for untrusted input: **SpEL `StandardEvaluationContext`** (T()-operator RCE class;
  cf. CVE-2026-22738, SpEL injection via a user-controlled filter key in Spring AI `SimpleVectorStore`),
  **MVEL** and **Janino** (full Java/JRE access; rely on the deprecated SecurityManager).
- **Q: How to match payload fields — free path string or typed refs?**
  Decision: typed `SYSTEM` / `METADATA` field refs. Evidence/Rejected: PagerDuty PCL uses dotted paths
  (`event.custom_details.*`) which is effectively a path mini-language; we keep the grammar finite to
  stay UI-buildable and validatable.
- **Q: Negative-operator semantics on missing fields?**
  Decision: a negative leaf matches only when the field is **present** and the condition fails; absence
  needs `NOT_EXISTS`. Evidence: PagerDuty documents the opposite as a gotcha ("does not match" also
  matches events missing the field) and tells users to add an `exists` check — we design the gotcha out
  instead. Also split the vague `contains` into `CONTAINS_SUBSTRING` vs `CONTAINS_ELEMENT`.
- **Q: Regex engine?**
  Decision: **RE2J**, compiled at save time with limits. Evidence: PagerDuty/Opsgenie use RE2 (linear,
  no catastrophic backtracking); Opsgenie even imposes a regex timeout. Rejected: `java.util.regex`
  (backtracking -> ReDoS on user-authored patterns).
- **Q: Hot-path — call catalog per event, or evaluate against a local read-model?**
  Decision: **local read-model** (T2 end-state); per-event sync is a T1 interim scaffold only.
  Evidence/reasoning: routing config is read-mostly and slow-changing, so seconds of propagation
  staleness is negligible, whereas "page someone even when catalog is down" is the product's core
  promise — the asymmetry favors replicate-and-read-locally (a versioned, durable, reconciled read-model
  is a legitimate read-side, distinct from "cache as source of truth"). Reuses existing infra:
  `common-outbox` (versioned full-snapshot publish), idempotent version-gated consumer, a PG read-model
  (the Phase 10 T4 `routing_cache` is the same pattern in miniature), and a reconciliation pull.
- **Q: Cold start of the local read-model?**
  Decision: PG-persisted projection eliminates *process-level* cold start (restart/scale-up inherit
  state); residual genuine first-population handled by cold-miss lazy sync-pull + the reconciliation
  pull; distinct projection states (`READY`/`ABSENT_CONFIRMED`/`UNINITIALIZED`/`STALE`) keep ops from
  conflating "no rules" with "not yet loaded".
- **Q: Isn't a shared `routing-core` lib the "shared business logic across microservices" anti-pattern?**
  Decision: No — what is shared is a pure **policy-evaluation engine** + the **rules as versioned data**,
  not two services' domain logic. The anti-pattern is two bounded contexts sharing each other's business
  logic; here routing is one context's concept, the rules stay single-owned by catalog (shipped as data,
  not code), and incident only *executes* the engine locally on routing's behalf. The shared surface is a
  small, stable, I/O-free pure function `(event, ruleset) -> decision` — closer to a regex/CEL library
  than to domain logic. Evidence: this is the mainstream pattern for slow-changing policy evaluated on a
  hot path — **OPA** (central policies, local eval via lib/sidecar), **feature-flag SDKs** (LaunchDarkly /
  Unleash / OpenFeature: central flag rules, client-side eval from a synced ruleset), **Envoy/Istio xDS**
  (central routing config pushed to sidecars that evaluate locally). It is also *entailed*, not stylistic:
  a per-event decision needs both the event (only in incident) and the rules, so taking catalog off the
  hot path forces local eval, which forces a single shared evaluator to avoid catalog-preview-vs-incident
  drift. Guardrails that keep us on the right side: the lib stays **pure engine only** (CRUD / validation
  / storage never leave catalog), rules are **always data**, and the monorepo removes cross-repo version
  skew. Rejected alternatives: per-event sync call (T1 — no sharing but catalog on the hot path) and an
  eval sidecar (the OPA deployment shape — same local-eval semantics, heavier ops).

Sources: PagerDuty — support.pagerduty.com/main/docs/event-orchestration,
/event-orchestration-examples, /pd-cef, /rulesets; developer.pagerduty.com/docs/pcl-overview; the
official PagerDuty Terraform provider schemas. Opsgenie — docs.opsgenie.com/docs/team-routing-rule-api,
/alert-and-notification-policy-api; support.atlassian.com/opsgenie/docs/alert-notifications-flow,
/action-filters-in-opsgenie-integrations. Grafana —
grafana.com/docs/grafana-cloud/alerting-and-irm/irm/configure/escalation-routing/alert-routing,
grafana.com/docs/oncall/latest/oncall-api-reference/routes. Datadog —
docs.datadoghq.com/incident_response/on-call/routing_rules, /monitors/notify/notification_rules.
Splunk On-Call — help.splunk.com/.../splunk-on-call/alerts/routing-keys, /rules-engine. incident.io —
docs.incident.io/alerts/escalations-from-alerts, /attributes-and-priorities. Patterns/EL —
martinfowler.com/bliki/RulesEngine.html, cel.dev, github.com/google/cel-java,
github.com/CacheControl/json-rules-engine, react-querybuilder.js.org/docs/utils/export,
spring.io/security/cve-2026-22738.

## Phase 18 - Throughput & Consumer Resilience under Load (measured 2026-06-25)

Goal: the first real load test of the alert→incident→escalation→notification path exposed three
capacity ceilings and one latent correctness bug. Close them, and commit a repeatable load-test
harness (none existed before). This phase is **measurement-led**: every ticket cites a number from
the 2026-06-25 run and must move it.

### Measured baseline (local fleet, 8 bootJars, CPU governor=performance, 20 integration keys, k6 ramping-arrival-rate)

| Observation | Number | Where it binds |
| --- | --- | --- |
| Ingest accept ceiling | ~676 req/s; p95 4.27s at 3000 offered RPS | gateway→integration→sync identity resolve→DB outbox write→202 |
| Event-chain throughput | ~100 msg/s **per service** | `OutboxRelay` poll(1000ms) × batch(100) |
| notification delivery | **0 delivered**, consumer stuck at offset 12, 28,942 frozen | poison message + single partition + failed DLT |
| Partitions | every topic `PartitionCount=1` | no consumer parallelism anywhere |
| Rate limiter | 5 rps × 20 keys = 100 rps hard cap (run 1: 81% 429) | gateway Redis token bucket (by design; default low) |

### Design decisions (locked 2026-06-25, research-driven — see Research notes below)

- **T1 first lever is polling tuning, not CDC.** Lower `heimcall.outbox.poll-interval-ms` and raise
  `batch-size`; the relay already uses `FOR UPDATE SKIP LOCKED` so multiple instances parallelize
  safely. CDC/Debezium (push-based, no poll/batch tuning) stays a **documented future escape hatch**
  reserved for a real thousands-per-second SLA — it is not worth the operational cost at current scale.
  Guard against hammering the DB on empty polls (do not drop the interval to near-zero blindly).
- **T2 consumer poison handling is distinct from Phase 15.** Phase 15 dead-letters *producer* outbox
  rows; this is the *consumer* side. The notification consumer retries a validation failure
  (null `organization_id`) forever and its DLT publish also failed → permanent head-of-line block on a
  single partition. Fix: `ErrorHandlingDeserializer` + `DefaultErrorHandler.addNotRetryableExceptions(...)`
  classifying deserialization **and** domain-validation failures (missing required tenant/user) as
  non-retryable, routed via a `DeadLetterPublishingRecoverer` that republishes **raw bytes** to
  `*.DLT` (no DB touch, so the DLT path cannot fail on the same constraint that poisoned the message).
  Short blocking retry (1–2) for transient faults only. Purge the existing `deadbeef…` chaos-test
  message from `notification.requested.v1`.
- **T3 partition scaling is set at topic creation, keyed by orgId.** Per-org ordering is the only
  ordering Heimcall needs; cross-org has none. Adding partitions to an already-keyed topic permanently
  breaks ordering, so topics are **provisioned with N partitions up front** (not ALTERed live), key =
  orgId, and consumer concurrency raised to match. Single-broker dev keeps RF=1; sizing target ~1–3
  MB/s per partition is far above current load, so N is driven by desired consumer parallelism, not raw
  bytes.
- **T4 ingest accept latency** is the gateway→202 path, separate from chain throughput. Tune Hikari
  pool on integration-service and make the synchronous identity key-resolve cheap (Redis-cached
  resolution with short TTL; key→org/active is effectively static), then re-measure.
- **T0 commits the harness.** `loadtest/` with the k6 script + idempotent seed script (register→org→
  keys→service→policy→fallback→contact-method) so the run is reproducible and reviewable. Honors the
  CPU-governor=performance precondition.

### Ticket breakdown

- **T0 - Load-test harness.** `loadtest/k6/ingest.js` (multi-key ramping-arrival-rate) + `loadtest/seed.sh`
  + README (governor precondition, how to run, how to read lag). Acceptance: `seed.sh && k6 run` on a
  fresh local fleet reproduces the baseline table above.
- **T1 - Relay throughput. DONE (82a3167).** The plan's "tune polling first" lever was measured
  **insufficient**: lowering poll-interval (1000→200ms) and raising batch (100→200) moved the rate only
  ~90→110/s. The real ceiling is a **synchronous `relayTemplate.send(record).get(timeout)` per row** —
  each row blocks on its own broker ack (~8ms with acks=all on the local broker), so the batch drains
  serially no matter how big it is or how often we poll. Fix = **pipeline**: fire every send in the batch
  first (collect futures, no await), then await them together (the idempotent acks=all producer batches
  the in-flight records into a few round-trips), then **bulk-mark** the succeeded rows PUBLISHED in one
  UPDATE. Measured **~670/s saturated (~6x)**. Correctness preserved and proven: whole batch still in one
  tx (crash before commit → all rows PENDING → at-least-once, consumers dedupe on eventId); the NOT EXISTS
  guard claims one row per aggregate per batch so the rows are distinct aggregates (no ordering link →
  safe to send concurrently and mark independently); **broker blackout** (no send in the batch succeeds)
  leaves rows PENDING untouched — no mass dead-letter — while a **row-specific** failure (others in the
  batch succeeded) still counts toward the maxAttempts backstop. Unit tests rewritten for the new
  transient/blackout semantics; runtime drain verified DEAD=0, full drain, no loss. Defaults: poll-interval
  1000→200ms, batch 100→200, in `common-outbox` (applies to every producer service). Deferred: the
  adaptive drain-loop (drain fully per tick) — unnecessary at current scale once pipelined.
- **T2 - Consumer poison-pill resilience. DONE (ff75c48).** The original diagnosis was wrong: the
  consumer *already* had `ErrorHandlingDeserializer` + bounded retry + `DeadLetterPublishingRecoverer`
  (Phase 3.5). The real bug was the **DLT producer's serializer**: `DelegatingByTypeSerializer` built
  with exact-match (`assignable=false`) over an unordered `Map.of(byte[], Object)`. When the recoverer
  dead-lettered a *deserialized* event value (the null-org `deadbeef…` record, value =
  `NotificationRequestedEvent`), no exact `NotificationRequestedEvent.class` delegate matched →
  `SerializationException: No matching delegate for type` → the DLT publish itself threw → `Seek to
  current` → infinite redelivery on the single partition, `delivered=0`. incident-service had already
  hit and fixed this exact bug; the fix ports its construction: `LinkedHashMap` (byte[] first) +
  `assignable=true`. No non-retryable classification and no manual purge were needed — once the DLT path
  works, the poison **self-purges** to `.DLT` on the next attempt. Verified live on the frozen 2-day
  consumer: restarted with the fix → deadbeef landed in `.DLT` (headers `kafka_dlt-exception-cause-fqcn:
  DataIntegrityViolationException`), consumer advanced past offset 12, drained, `notification.delivered.v1`
  > 0, mailhog received mail, zero "No matching delegate".
- **T3 - Partition parallelism. PARTIAL — `notification.requested.v1` DONE (f025ca7).** Provisioned the
  topic with N partitions up front via a `NewTopic` bean (`heimcall.notification.requested-topic.partitions`,
  default 4) + `spring.kafka.listener.concurrency=4`. **Key is incidentId** (set by the escalation
  producer), not orgId: per-incident page ordering is the ordering that matters here, and incidentId is a
  finer key → better spread + no hot-partition risk for a large org. **Measured (controlled, real
  chain-produced backlog, only concurrency varied):** concurrency=1 ≈ 100–233 msg/s (one consumer owns all
  4 partitions, serial) vs concurrency=4 = **744 msg/s** (4 distinct consumers, one per partition) ≈
  **4.5x** while load is spread. **Key finding:** an existing single-partition backlog does **not**
  parallelize — those records are physically on p0, drained by one thread (the old p0 backlog reverted the
  rate to ~100/s once p1–p3 drained). Partitions speed up new/spread load, not data already committed to
  one partition. Provisioning is at creation, never live-ALTER (rehashes keys, breaks order) — fresh envs
  get N from the `NewTopic` bean; this dev env was ALTERed once (ordering caveat accepted for the
  experiment). **Now fully DONE:** `alert.received.v1` + `incident.lifecycle.v1` were partitioned (4p each)
  later in **Phase 20 T2** (`NewTopic` beans + consumer concurrency 4, keyed by dedupKey / incidentId).
- **T4 - Ingest accept path.** Hikari sizing + Redis-cached key resolution. Acceptance: accept p95 < 2s
  (PRD incident-creation SLA budget) at the throughput T1/T3 unlock; no 503 from identity timeouts.

### Research notes (researched 2026-06-25)

- **Outbox relay throughput** — *Decision:* tune polling (interval/batch, multi-instance SKIP LOCKED)
  before CDC. *Evidence:* polling favors simplicity and explicit control; CDC (Debezium) is push-based
  with no poll/batch tuning but added operational cost, recommended only at thousands/s or strict
  latency SLAs; constant polling hammers the DB even when empty. *Rejected:* Debezium now (premature
  operational complexity at current scale).
- **Consumer poison-pill** — *Decision:* `ErrorHandlingDeserializer` +
  `DefaultErrorHandler.addNotRetryableExceptions` + raw-bytes `DeadLetterPublishingRecoverer`.
  *Evidence:* `DefaultErrorHandler` retries everything except fatal deser/conversion by default, so a
  validation failure blocks the partition forever; blocking retries hold the partition
  (`FixedBackOff(5s,3)` = 15s lag); non-blocking `@RetryableTopic` is for long backoff windows.
  *Rejected:* long blocking retries on the main partition.
- **Partition sizing** — *Decision:* provision N partitions at creation, key=orgId. *Evidence:* max
  consumer parallelism = partition count (≤1 consumer per partition per group); adding partitions to a
  keyed topic permanently breaks ordering → over-provision at creation, never ALTER keyed topics live;
  ~1–3 MB/s per partition is a conservative baseline. *Rejected:* live repartition of keyed topics.

Sources: decodable.co/blog/revisiting-the-outbox-pattern,
confluent.io/blog/spring-kafka-can-your-kafka-consumers-handle-a-poison-pill,
docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html,
confluent.io/blog/how-choose-number-topics-partitions-kafka-cluster.

## Phase 19 - Observability for throughput & flow (NEXT, prioritized)

Goal: make the event pipeline measurable from Prometheus/Grafana instead of kafka CLI + psql. Phase 18
exposed that there is **no metric for relay publish rate, per-stage consume/produce rate, consumer-group
lag, or delivery rate** — every Phase 18 number was scraped by hand (`kafka-consumer-groups`,
`GetOffsetShell`, `psql`), which is slow (each `kafka-run-class` spins a JVM), jittery, and impossible to
graph. This phase is a prerequisite for continuing the measurement/optimization loop (T0 harness, T4
ingest path, the stage-2 `DeliveryWorker` anti-pattern, T3 for the remaining topics).

### What Phase 8 already shipped (baseline — do not rebuild)

Prometheus (`:9090`, host-net) scraping all 8 services + postgres/redis exporters; Grafana (`:3000`) with
four provisioned dashboards (JVM/HTTP, Heimcall-domain, PostgreSQL, Redis); domain counters
(`incident_*_total`, `escalation_task_executed_total`, `notification_delivery_success/failure_total`,
`incident_time_to_ack/resolve_seconds`); native Kafka client meters attached to the consumer/producer
**factories** (Phase 8 T3) so `kafka_consumer_fetch_manager_records_consumed_rate`,
`kafka_producer_record_send_rate`, and app-side `kafka_consumer_fetch_manager_records_lag_max` are already
exported for every stage; OTLP tracing to Jaeger.

### The gap (measured 2026-06-26 against the live dashboards)

Existing throughput panels: HTTP req/s, incident-lifecycle rate, notification-delivery rate, app-side
consumer lag. **Missing the entire event-pipeline throughput picture:** (a) **relay publish rate** — the
`OutboxRelay` `KafkaTemplate` is a deliberate non-bean (so the tracing BeanPostProcessor can't flip
observation and break trace linkage — see relay javadoc), so it is **completely un-instrumented**; (b)
**per-stage consume/produce rate** — the client meters exist but have **no panels**; (c) **broker-side
consumer lag** — the only lag metric today is app-side, which reports only *assigned* partitions and reads
**zero when the consumer is down or rebalancing** (precisely the Phase 18 freeze: 28,942 backlog would have
shown lag≈0 or vanished — invisible); (d) **DeliveryWorker drain rate / PENDING depth**. Every Phase 18
number was hand-scraped (`kafka-consumer-groups`, `GetOffsetShell`, `psql`) — slow (each `kafka-run-class`
spins a JVM), jittery, ungraphable.

### Design decisions (locked 2026-06-26, research-driven — see Research notes below)

- **T1 instruments the relay by hand, not via Spring's auto-timer.** Spring Kafka's `spring.kafka.template`
  timer binds only to the auto-created template; the relay's template is a non-bean by design, so it gets
  explicit Micrometer (the relay already holds the `MeterRegistry` for `outbox_dead_total`). Counter
  `outbox_published_total` (rate-normalize with `rate()` at query time, never pre-divide), publish-latency
  `Timer` over the batch-await, `outbox_pending` gauge (cheap `SELECT count` per scrape). Consistent tag
  keys per metric name (`topic`) — Prometheus rejects same-name/different-tags.
- **T2 adds a broker-side lag exporter as the truth source.** App-side `records-lag-max` is kept (cheap,
  per-instance) but cannot see a dead consumer's backlog; a broker-side exporter reads committed group
  offsets independent of consumer liveness, so lag stays visible through a freeze/rebalance. Dedicated
  exporter container (kafka-lag-exporter / KMinion), scraped by the existing host-net Prometheus, lag per
  `<group, topic, partition>`.
- **T3 is a first-class throughput dashboard (RED-shaped).** Rate/Errors/Duration, **one row per hop in
  data-flow order** (ingest → incident → escalation → relay → notify → deliver); duration as p50/p90/p99;
  alert on RED symptoms, not USE causes. Most per-stage rate panels are panels-only (client meters already
  exported); only the relay rate needed code (T1).
- **DeliveryWorker serial-poll anti-pattern stays a Phase-18-style follow-up** — Phase 19 only makes its
  depth/rate *visible* (PENDING-delivery gauge + drain rate), it does not re-architect the worker.

### Ticket breakdown

- **T1 - Relay throughput + depth metrics (code). DONE.** `OutboxRelay`: `outbox_published_total` counter
  (tag `topic`), publish-latency `Timer` (`outbox_publish_seconds`, wraps the batch await), `outbox_pending`
  gauge (PENDING-row `count(*)` per scrape). All null-safe; the non-bean template is untouched (trace
  linkage preserved). Verified live: cloned-row publish → counter=1, latency mean ~9.6ms (matches Phase 18's
  ~8ms acks=all), gauge 1→0; Prometheus scrapes all three with the `service` label.
- **T2 - Consumer-group lag exporter. DONE.** KMinion (`redpandadata/kminion:v2.2.12`) as a compose
  container reaching the broker via the DOCKER listener (`kafka:29092`); host-net Prometheus scrapes its
  published port (`localhost:9308`, job `kafka-lag`). Emits `kminion_kafka_consumer_group_topic_lag` per
  `<group, topic>` from committed broker offsets. Chose KMinion over seglo/kafka-lag-exporter (archived).
  Acceptance **proven**: stopped incident-service, injected a 120-row backlog to `alert.received.v1` → with
  the consumer **down**, KMinion lag = 120 while app-side `records_lag_max` had **0 series** (vanished — the
  Phase 18 blind spot); restart drained both to 0. Board lag panel split into broker-side (KMinion) +
  app-side complement.
- **T3 - Throughput dashboard (`heimcall-throughput.json`). DONE.** RED rows in flow order: glance stats
  (ingest/relay/consume/deliver msg/s), **1 Ingest** (accept rate by status + latency), **2 Relay** (publish
  rate per service/topic + PENDING/DEAD depth + publish latency — all T1), **3 Kafka** (consumed rate per
  service/topic via `rate(kafka_consumer_fetch_manager_records_consumed_total[1m])` — the `*_total` counter,
  since Micrometer doesn't register the `*_rate` gauge), **4 Escalation & Notify** (task rate, delivery
  success/fail = DeliveryWorker drain), **5 Backlog** (broker-side KMinion lag + app-side complement, T2).
  Note: the relay producer is a non-bean so there are no `kafka_producer_*` meters — produce rate IS
  `outbox_published_total`. Auto-provisioned (Phase 8 T4c folder, 10s reload), uid `cfq2qmqfw62v4b`.
  Verified: Grafana loaded it, all panels resolve real series, rate/latency move under an 80-row burst
  (1.44 msg/s, 9.6ms). Acceptance: a load run is readable off this one board — no CLI/psql.
- **T4 - Per-stage latency (p50/90/99). DONE.** Fleet-wide HTTP histogram buckets via the observability
  `EnvironmentPostProcessor` (`management.metrics.distribution.percentiles-histogram.http.server.requests`)
  so ingest accept latency reads true p50/90/99 (not just a mean). New notification-stage Timer
  `notification_delivery_latency_seconds` (`request.receivedAt → delivered`, histogram) — surfaces the
  DeliveryWorker poll-wait + send cost (the stage-2 serial-poll anti-pattern). Board ingest + stage-latency
  panels switched to `histogram_quantile`. Verified: 138 HTTP bucket series on integration; stage timer
  records on delivery.
- **T5 - True end-to-end alert→delivered latency. DONE (added this session on request).** One aggregated
  Prometheus quantile for the *whole* pipeline. Scope stayed small because the alert origin already flows:
  `AlertReceivedEvent.occurredAt` → incident `Triggered.at` → `IncidentTriggeredEvent.occurredAt` →
  (escalation persists it as) `EscalationIncident.triggeredAt`. So T5 = **one new event field**
  (`NotificationRequestedEvent.alertOccurredAt`, set from `EscalationIncident.triggeredAt` in
  `fireDueTask`) + **one new column** (`notification_request.alert_occurred_at`, Flyway V3, nullable) +
  a `notification_e2e_latency_seconds` histogram recorded at delivery (`deliveredAt − alertOccurredAt`).
  Nullable end-to-end → a pre-T5 in-flight message carries no origin and the e2e timer skips it (proven:
  old request row `has_origin=f`, new one `t`). **Verified live:** injected a fresh CRITICAL alert through
  the whole chain on rebuilt jars → e2e count=1, **p50 ≈ 20s** (escalation level-1 delay + 5s delivery poll
  dominate — expected, that latency is the escalation cadence, not pipeline overhead); board e2e p50/90/99
  panel resolves. Note: `occurredAt` is alert-supplied (the documented origin); the board labels it
  alert→delivered. The "delivered count" stat uses `round(increase(...))` + a cumulative `total` series
  (raw `increase()` extrapolates to fractional at range edges).
- **Close-out.** Lag exporter (KMinion) is a compose-local infra concern, exactly like the Phase 8 T4c
  postgres/redis exporters and the Prometheus/Grafana stack — none live in `deploy/helm` (the chart deploys
  only the 8 services; observability infra is BYO, pods carry `prometheus.io/scrape`). So no helm change; in
  k8s, bring your own lag exporter. Deferred (Phase-18-style follow-up, made *visible* not *fixed* here): the
  DeliveryWorker serial 5s-poll drain — its latency now shows on the board (stage + e2e), re-architecture is
  a separate perf ticket.

### Research notes (researched 2026-06-26)

- **Custom producer instrumentation** — *Question:* how to meter the relay's publish rate/latency when its
  template is a non-bean. *Decision:* explicit Micrometer `Counter` + `Timer` (+ `Gauge` for depth);
  rate-normalize counters at query time with `rate()`, keep tag keys consistent per metric name. *Evidence:*
  Spring Kafka's auto `spring.kafka.template` timer binds only to the template it auto-creates (custom
  producers need manual instrumentation); Micrometer docs recommend rate-normalizing counters at query time
  and warn that registering one meter name with differing tag-key sets breaks Prometheus. *Rejected:*
  exposing the relay template as a bean to get the auto-timer (would let the tracing BPP inject a fresh
  `traceparent` and sever the original trace — the whole reason it is a non-bean).
- **Consumer lag source** — *Question:* app-side Micrometer lag vs a broker-side exporter. *Decision:* add
  a broker-side exporter as the truth source, keep app-side as a complement. *Evidence:* app-side
  `records-lag-max` only covers partitions currently *assigned* to a live consumer and reports zero / drops
  out when the consumer is down or during rebalance; broker-side tools (kafka-lag-exporter, Burrow, KMinion)
  read committed group offsets independently so lag is visible even when the consumer is dead — directly the
  Phase 18 frozen-consumer blind spot. *Rejected:* app-side-only (would have hidden the exact incident this
  phase exists to surface).
- **Dashboard method** — *Question:* how to structure the pipeline board. *Decision:* RED (Rate/Errors/
  Duration), one row per stage in data-flow order, duration as p50/p90/p99, alert on RED symptoms.
  *Evidence:* RED reports user-facing symptoms (USE reports machine causes); Grafana's guidance puts rate+
  errors left, duration right, one row per service ordered by data flow, and recommends percentile latency.
  *Rejected:* USE-only machine dashboards for the pipeline view (cause-side, not symptom-side).

Sources: docs.micrometer.io/micrometer/reference/concepts/timers.html,
docs.spring.io/spring-kafka/reference/kafka/micrometer.html,
github.com/seglo/kafka-lag-exporter, baeldung.com/java-kafka-consumer-lag,
grafana.com/blog/the-red-method-how-to-instrument-your-services/.

## Phase 20 - Notification Delivery Throughput (parallel delivery worker) (measured 2026-06-26)

Goal: with Phase 19 making the pipeline measurable, the 2026-06-26 load run pinpoints the **single
narrowest stage**: notification delivery at **~87 msg/s** while every upstream stage sustains ~670/s.
Re-architect the `DeliveryWorker` from a serial single-thread poll into a **concurrent sender**, without
weakening the at-least-once delivery guarantee. Measurement-led: every ticket cites a number from the run.

### Measured baseline (local fleet, 8 bootJars, CPU governor=performance, 20 keys, k6 ramp→3000 RPS, Phase 19 metrics off Prometheus)

| Stage | Metric source | Measured | Verdict |
| --- | --- | --- | --- |
| A ingest accept | k6 `alerts_accepted` | **~100/s** (93.7% of 3000 RPS → 429) | gateway token bucket (20 keys × replenish 5/s) — configured cap, not capacity |
| A integration relay | `rate(outbox_published_total{topic="alert.received.v1"})` | ~667/s | fine |
| B incident consume | KMinion lag `alert.received.v1` | kept 100/s, lag spiked 3700→0 | fine @100/s; **1 partition → no parallelism** |
| B/C incident + escalation relay | `rate(outbox_published_total)` | **666.7/s** (escalation, measured) | fine; backlog under load was burst-driven (round-1+round-2 task waves), drains at 640/s |
| D **notification delivery** | `rate(notification_delivery_success_total)` | **86.6/s** saturated | ⛔ **bottleneck** |
| e2e latency | `notification_e2e_latency_seconds` | avg ~80s under load; histogram **pinned at 30s max bucket** (tail invisible) | latency dominated by delivery queueing |

Root cause (`DeliveryWorker.java`): `@Scheduled(fixedDelay 5s)` iterating due rows in a **serial `for`
loop**, each `fireDelivery` doing the SMTP/webhook send **inside the tx + `FOR UPDATE SKIP LOCKED` row
lock**. The `notification.requested.v1` consumer runs concurrency=4 (Phase 18 T3) so delivery *rows* are
created in parallel — but the **sender is serial**, so that parallelism dies at the send. Amplification
makes it worse: 1 incident → **2** `notification.requested` (repeat_count=1 = 2 rounds) → 2 deliveries, so
even the throttled 100 incidents/s demands ~200 deliveries/s against an 87/s ceiling (~2.3× overload).

### Design decisions (locked 2026-06-26, research-driven — see Research notes below)

- **T1 = two-phase claim + concurrent senders. The concurrency is the throughput lever; two-phase is the
  safety prerequisite — not optional, and not the win on its own.** Two-phase alone, still single-threaded,
  is still ~87/s. Claim flips `PENDING → SENDING` (committing fast, releasing the row lock), the slow send
  runs **outside** the tx, and a final state update commits the result. Only because the send is lock-free
  can a pool of senders run in parallel without serializing on row locks / exhausting the DB pool.
- **Concurrency model = virtual-thread executor + semaphore cap** (Java 21, sends are IO-bound). Cap via
  `notification.delivery.concurrency` (default ~16; real prod cap is the email/SMS provider rate limit, not
  our threads). Hikari is fine because two-phase holds a DB connection only at claim + ack (ms), never
  across the send. VT pinning is a non-issue here precisely because no JDBC is held across the blocking IO.
  Platform fixed-pool is the documented fallback if JavaMail's internal `synchronized` ever pins materially.
- **at-least-once is preserved by lease + reaper — concurrency changes the *duplicate* rate, not the *loss*
  guarantee** (the guarantee is per-row, independent of in-flight count). Invariants that must hold:
  (1) `SENDING` is **never** written without `lease_expires_at` in the same commit (a leaseless SENDING row
  = orphan = loss); (2) claim = `SKIP LOCKED` + atomic flip, committed before send; (3) reaper / claim
  predicate reclaims expired SENDING (`status='PENDING' OR (status='SENDING' AND lease_expires_at < now)`);
  (4) final ack gated on `lease_token` (fencing) so a revived zombie worker cannot overwrite a row the new
  owner re-claimed; (5) shutdown leaves in-flight rows `SENDING` (never `FAILED`) so the reaper recovers them.
- **Claim-on-demand, not batch-claim-then-queue.** Each free permit claims exactly one row and sends it, so
  the lease only has to cover one real send. Batch-claiming N rows to `SENDING` and parking them in a bounded
  queue would let the lease expire *while queued* → needless reclaim → self-inflicted duplicate.
- **Lease ≥ p99 send + webhook timeout (5s) + GC/scheduler slack** — *not* `timeout + 1s`. A too-short lease
  expiring while the first worker is still mid-send lets a second worker fire in parallel = guaranteed
  duplicate. (Heartbeat/lease-renewal is the deferred evolution if send latency gets highly variable.)
- **Duplicate outbound notification is the accepted at-least-once cost.** Send a best-effort `X-Delivery-Id`
  header (helps receivers that dedupe) but do **not** architect around receiver idempotency: Heimcall pushes
  to *uncontrolled customer webhooks* + SMTP, which mostly won't honor an `Idempotency-Key`. Redis cooldown
  (Phase 14) already coalesces repeat pages per `incident+user+channel`, bounding user-visible dup pages.
- **Reject the "eventual delivery / DLQ-as-graveyard" framing for paging.** A page delivered 10 min late is
  useless; bounded attempts → `FAILED` → escalate to the next target/level is the *correct* incident-mgmt
  behavior, and Heimcall already does it (max-attempts + escalation repeat rounds/levels). Keep it.
- **No "delivery row in the same tx as the incident."** That is a single-DB monolith pattern; Heimcall is
  database-per-service. The cross-service durability already exists (incident outbox → Kafka → escalation
  outbox → Kafka → notification consumer) and the inbox dedup already exists (`notification_request` PK =
  request eventId). Nothing to add there.

### Ticket breakdown

- **T1 (DONE) - Parallel delivery worker (primary).** Schema (Flyway `V4`): added `lease_token`,
  `lease_expires_at` to `notification_delivery` (+ `SENDING` status) + partial index on `next_attempt_at`
  `WHERE status IN ('PENDING','SENDING')`. The serial `@Scheduled` `DeliveryWorker` (send-in-tx, ~87/s) was
  replaced by a two-phase design across three beans:
  - `NotificationDeliveryRepository.claimDue` — `SELECT … WHERE next_attempt_at <= :now AND (status='PENDING'
    OR (status='SENDING' AND lease_expires_at < :now)) ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT
    :limit`. The expired-`SENDING` arm is the reaper (no separate scheduler); `findByIdForUpdate` re-locks by
    id for the result write.
  - `DeliveryTx` (transactional) — `claimDue` flips rows → `SENDING` + fresh `lease_token`/`lease_expires_at`
    and commits (releasing the row lock); `finalizeDelivered`/`finalizeRetry`/`finalizeFailure` re-lock by id
    and apply the result **only if `lease_token` still matches** (fencing) — a zombie worker whose lease was
    reaped returns `false` and cannot clobber the new owner. Its own bean so the un-transactional orchestration
    reaches it through the Spring proxy.
  - `DeliveryService.sendClaimed` (un-transactional) — SMTP/webhook send **outside** any tx (no lock, no DB
    connection held), then records via `DeliveryTx`; metrics increment only on a won fencing check.
  - `DeliveryDispatcher` — dedicated loop thread + `Executors.newVirtualThreadPerTaskExecutor()` capped by
    `Semaphore(notification.delivery.concurrency)`, **claim-on-demand** (claims only as many rows as free
    permits — no batch-then-queue lease-expiry bug). `@PreDestroy` leaves in-flight rows `SENDING` (lease
    recovers them). Config: `concurrency` (16), `lease-ms` (60000), `idle-sleep-ms` (500). `WebhookSender`
    sends a best-effort `X-Delivery-Id`/`Idempotency-Key` header (stable across retries) for receiver dedup.
  - **Verified (2026-07-01):** unit `DeliveryServiceTest` (7) + `NotificationServiceTest` (4) + PG
    `NotificationDeliveryClaimTest` (2: exactly-one-claimer via SKIP LOCKED, expired-lease reclaim). **Load
    (isolated delivery stage — seeded 8000 PENDING webhook deliveries + local sink):** peak **1246/s**,
    sustained ~1000/s, 8000 drained in ~9s, 0 failed — **~14× the 87/s serial baseline** (at `powersave`
    governor, so conservative). **Chaos:** `kill -9` mid-drain caught 11 rows `SENDING`; on restart all
    reclaimed via lease expiry → **8000/8000 DELIVERED, 0 FAILED, 0 lost** — at-least-once holds across a hard
    crash. Note: the delivery stage was loaded directly, not through the full k6 pipeline, because the gateway
    rate-limit caps the pipeline at ~100/s (T4) — it cannot exercise delivery at 600/s. e2e-latency-off-30s is
    T3 (the histogram bucket ceiling is a separate, still-open item).
- **T2 (DONE) - Partition the remaining single-partition topics.** `alert.received.v1` +
  `incident.lifecycle.v1` were still `PartitionCount=1` (Phase 18 T3 only did `notification.requested.v1`) →
  incident/escalation consumers could not scale horizontally. Added a `NewTopic` bean per topic (mirroring
  Phase 18 T3): incident-service `alertReceivedTopic` (`heimcall.alert-received-topic.partitions:4`, keyed by
  dedupKey), escalation-service `incidentLifecycleTopic` (`heimcall.incident-lifecycle-topic.partitions:4`,
  keyed by incidentId — preserves the Phase 12 lifecycle ordering within a partition). Raised each consumer's
  `spring.kafka.listener.concurrency` to 4 (`INCIDENT_ALERT_CONSUMER_CONCURRENCY` /
  `ESCALATION_INCIDENT_CONSUMER_CONCURRENCY`); on incident-service this applies only to the default factory
  (alert-received listener) — the notification-feedback + ruleset-snapshot listeners keep their custom
  single-thread factories. Never live-ALTER a keyed topic (rehashes keys, breaks order) — fresh envs get N
  from the bean; an existing 1-partition dev topic must be recreated. **Verified (2026-07-01, real
  Kafka+PG):** deleted both 1-partition dev topics, rebuilt bootJars, booted both services → recreated at
  `PartitionCount: 4`; each consumer group ran **4 distinct threads across partitions 0–3** (assignment
  confirmed via `kafka-consumer-groups --describe`). Acceptance met (consume scales past the single-thread cap).
- **T3 (DONE) - Extend latency histogram buckets.** `notification.delivery.latency` +
  `notification.e2e.latency` used Micrometer's `publishPercentileHistogram()` default 1ms..30s range, so
  under load (e2e avg ~80s) p95/p99 pinned at the 30s ceiling. Raised `maximumExpectedValue` +
  added explicit SLO boundaries: delivery (stage) min 5ms / **max 2m** / SLOs 1,5,10,30,60,120s; e2e (full
  chain) min 10ms / **max 5m** / SLOs 5,15,30,60,120,300s. **Verified (2026-07-01):** scraped
  `/actuator/prometheus` — `notification_delivery_latency_seconds_bucket` runs to `le="120.0"`,
  `notification_e2e_latency_seconds_bucket` to `le="300.0"` (SLO boundaries present). Tail no longer pinned.
- **T4 (DONE) - Gateway rate-limit review.** The per-key token bucket (20 keys × replenish 5/s ≈ 100/s) is
  the deliberate first ceiling, not a capacity limit. Documented in `api-gateway/application.yml`: the
  ceiling formula, the load-run override knobs (`RL_REPLENISH_RATE` / `RL_BURST_CAPACITY` — no code change),
  and the **per-key vs per-org keying decision** — org is not known at the gateway without a key→org resolve
  round-trip (async in the reactive filter → added latency + the coupling the ingest path avoids), so the
  integration key is the natural tenant-proxy at the edge; per-org fairness is a downstream concern where org
  is known (integration-service post-resolve). *Rejected:* per-org keying at the gateway (needs the lookup);
  raising the default (kept conservative — overridable per load run). **Verified (2026-07-01):** gateway
  booted with `RL_REPLENISH_RATE=1000` → 40-req burst = 0×429 (default 5/10 429s after ~10, per Phase 14 T1).
  Acceptance met (documented; tunable without code change).

**Phase 20 complete (T1–T4).**

### Research notes (researched 2026-06-26)

- **Two-phase delivery + lease vs send-in-tx** — *Decision:* claim `→SENDING` with a lease, send outside the
  tx, ack gated on a fencing `lease_token`; reaper reclaims expired SENDING. *Evidence:* this is the
  in-DB form of a work-queue **visibility timeout** — SQS makes a claimed-but-unacked message visible again
  after the timeout so a crashed worker's message is never lost (at the cost of possible redelivery);
  Temporal documents the same crash/timeout window (external Activity succeeds but the worker dies before
  recording the result → it re-runs, so the callee must dedupe). The fencing token is the standard guard
  against a revived zombie worker mutating state it no longer owns. *Rejected:* keeping send-in-tx (holds
  the row lock + DB connection across a ~5s SMTP → cannot parallelize, the measured 87/s ceiling).
- **at-least-once vs exactly-once for SMTP/webhook** — *Decision:* target at-least-once + make duplicates
  cheap (best-effort `X-Delivery-Id`, Redis cooldown coalescing); do not chase exactly-once. *Evidence:*
  SMTP/arbitrary webhooks cannot enlist in the local DB tx, so the send-succeeds-then-crash-before-ack
  window is unavoidable; payment APIs (Stripe/Square) solve their side with an `Idempotency-Key` the
  *caller* sends and the *provider* persists — but Heimcall is the *sender* to *uncontrolled* receivers, so
  that lever is best-effort only here. *Rejected:* depending on receiver idempotency for correctness;
  same-tx delivery insert (cross-service, impossible under DB-per-service — and already solved by the
  outbox+inbox chain).
- **Concurrency mechanism** — *Decision:* virtual-thread executor + semaphore cap. *Evidence:* sends are
  IO-bound (SMTP/HTTP wait), the ideal VT workload on Java 21; two-phase removes the JDBC-held-across-IO case
  that causes carrier-thread pinning. *Rejected:* fixed platform-thread pool as default (kept as fallback if
  JavaMail `synchronized` pins materially); raising *consumer* concurrency further (it creates rows, not the
  send bottleneck).
- **Retry policy for paging** — *Decision:* bounded attempts → FAILED → escalate (existing behavior). 
  *Evidence:* incident paging values timeliness over eventual delivery — a late page is useless, and the
  escalation engine already provides the "try the next target" path. *Rejected:* infinite retry / DLQ-as-
  graveyard (a batch-job mindset; wrong for time-critical paging).

Sources: docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html,
docs.temporal.io/activity-definition, docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html,
docs.stripe.com/api/idempotent_requests, docs.oracle.com/en/java/javase/21/core/virtual-threads.html.

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
