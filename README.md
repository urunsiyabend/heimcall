# Heimcall

> Event-driven incident management & on-call platform - a mini PagerDuty / Opsgenie / Splunk On-Call, built microservices-first on Spring Cloud + Kafka.

Heimcall ingests alerts from external monitoring systems, deduplicates noisy signals, opens incidents, routes them to the owning service via a conditional rule engine, pages the current on-call responder, escalates if nobody acknowledges, and records a complete, append-only timeline for post-incident learning.

It is built as a **production-shaped distributed system, on purpose**: eight independently deployable services, a database per service, an event backbone, transactional outboxes, service-to-service OAuth2, default-deny network policies, and end-to-end observability - not because the problem demands it on day one, but because the goal is to exercise the hard parts of distributed systems correctly.

---

## Table of contents

- [Why this is interesting](#why-this-is-interesting)
- [Architecture at a glance](#architecture-at-a-glance)
- [Bounded contexts](#bounded-contexts)
- [The core flow: alert → incident → page](#the-core-flow-alert--incident--page)
- [Architecture decisions that matter](#architecture-decisions-that-matter)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Running locally](#running-locally)
- [Observability](#observability)
- [Engineering invariants](#engineering-invariants)
- [Project method](#project-method)

---

## Why this is interesting

This is not a CRUD app wearing a microservices costume. The design tackles the genuinely hard parts of an event-driven system and resolves each one explicitly:

- **No lost events, no ghost events** - every producer writes through a [transactional outbox](#1-transactional-outbox---no-dual-write). A broker outage leaves rows `PENDING` and drains on recovery; it never 503s the caller or fabricates an event on rollback.
- **Ordering across partitions and replicas** - incident lifecycle events share one ordered topic keyed by `incidentId`, and the outbox relay carries a **per-aggregate ordering guard** so even two relay replicas can't publish an incident's `TRIGGERED` after its `ACK`. ([details](#2-event-ordering-under-concurrency))
- **Routing survives a catalog outage** - the routing decision engine is a pure library evaluated *locally* inside incident-service against a **version-gated read-model projection**, hydrated from versioned ruleset snapshots. The service-catalog is off the hot path entirely. ([details](#3-routing-as-a-replicated-read-model))
- **Exactly-once-ish work dispatch** - every `@Scheduled` worker (escalation, notification, outbox relay) claims rows with `FOR UPDATE SKIP LOCKED`, so horizontal scaling never double-pages. ([details](#4-lock-safe-distributed-workers))
- **Real zero-trust between services** - internal endpoints require a short-lived RS256 **service token** (OAuth2 `client_credentials`, single-audience, scoped per endpoint), minted by a Spring Authorization Server and verified against the same JWKS as user tokens. Enforced in code *and* at the network layer with default-deny Cilium NetworkPolicies. ([details](#5-zero-trust-service-to-service-security))
- **Poison-pill resilience** - consumers are deserialization-safe, retry with bounded backoff, then dead-letter. A real production stall (a single null-org message redelivering forever on one partition) was diagnosed and fixed under load. ([details](#6-kafka-consumer-resilience))

Every behavior above was **verified at runtime** - on a live local fleet or a real Cilium/Hubble kind cluster - not merely compiled.

---

## Architecture at a glance

```
                    External monitors (Grafana, Alertmanager, CloudWatch, custom)
                                          │  POST /v1/integrations/{key}/events/{routingKey}
                                          ▼
                              ┌───────────────────────┐
                              │      api-gateway       │  Spring Cloud Gateway
                              │  JWT pass-through       │  Redis token-bucket rate limit
                              └───────────┬───────────┘  (per integration key)
                                          │
        ┌──────────────┬──────────────┬──┴───────────┬──────────────┬───────────────┐
        ▼              ▼              ▼               ▼              ▼               ▼
  ┌──────────┐  ┌────────────┐  ┌───────────┐  ┌───────────┐  ┌────────────┐  ┌──────────────┐
  │ identity │  │  catalog   │  │ schedule  │  │integration│  │  incident  │  │ escalation   │
  │  (auth,  │  │ (services, │  │ (on-call  │  │ (ingest,  │  │ (alerts,   │  │ (policies,   │
  │  tenants,│  │  routing   │  │ rotations)│  │ normalize)│  │ incidents, │  │ on-call fan- │
  │  tokens) │  │  rules)    │  │           │  │           │  │ timeline)  │  │ out, repeat) │
  └────┬─────┘  └─────┬──────┘  └─────┬─────┘  └─────┬─────┘  └─────┬──────┘  └──────┬───────┘
       │ DB           │ DB            │ DB           │ DB           │ DB             │ DB
       └──────────────┴──────────────┴──────────────┴──────────────┴────────────────┘
                            database-per-service (PostgreSQL)
                                          │
   ════════════════════════════════ Kafka event backbone ════════════════════════════════
        alert.received.v1 → incident.lifecycle.v1 → escalation.requested.v1
        → notification.requested.v1 → notification.delivered.v1 / .failed.v1
        routing.ruleset-published.v1  (catalog → incident read-model projection)
                                          │
                                          ▼
                              ┌───────────────────────┐
                              │  notification-service  │  email / Telegram / webhook
                              │  bounded retry + DLT    │  Redis page-cooldown
                              └───────────────────────┘
```

**Communication rules:**
- **Synchronous REST** only for read-time lookups that must be consistent (key resolution, membership checks, policy validation).
- **Asynchronous Kafka** for everything in the incident lifecycle - the moment a decision is made, it becomes an event.
- **Database per service.** No service reads another's tables. Cross-context data arrives as events or via that service's API; never the schema.

---

## Bounded contexts

The service map is a direct projection of the DDD bounded contexts - one context, one deployable, one database.

| Service | Port | Owns | Key responsibility |
|---|---|---|---|
| **api-gateway** | 8080 | routing, edge concerns | Spring Cloud Gateway; per-integration-key rate limiting; CORS; auth pass-through |
| **identity-service** | 8083 | orgs, users, teams, memberships, integration keys | Sole JWT signer (RS256 + JWKS); OAuth2 service-token issuer |
| **service-catalog-service** | 8084 | monitored services, ownership, **routing rules** | Authoring + preview of the routing decision table; publishes ruleset snapshots |
| **schedule-service** | 8085 | schedules, rotations, overrides | Timezone- & DST-aware current-on-call calculation |
| **integration-service** | 8081 | webhook ingestion | Normalizes any provider payload into `AlertReceivedEvent`; transactional outbox |
| **incident-service** | 8082 | alerts, incidents, timeline | Dedup aggregate; lifecycle state machine; **local routing evaluation**; ACK/resolve/cancel |
| **escalation-service** | 8086 | escalation policies, tasks | Materializes ordered/repeating escalation tasks; resolves targets; cancels on ACK |
| **notification-service** | 8087 | deliveries | Fan-out to contact methods; bounded retry; dead-letter; page cooldown |

Shared libraries (plain JARs, no `bootJar`) keep the cross-cutting concerns honest and identical across services:

| Library | Purpose |
|---|---|
| `common-domain` | Shared enums: `MessageType`, `Severity`, `IncidentStatus`, `AlertStatus` |
| `common-events` | Versioned event records + canonical topic constants |
| `common-security` | RS256 JWT verification, service-token authn, stateless `SecurityFilterChain` - one dependency |
| `common-outbox` | Transactional outbox: appender, relay (with ordering guard), pruner |
| `common-observability` | JSON logging, correlation-ID propagation (HTTP + Kafka), Micrometer + OTLP tracing |
| `routing-core` | **Pure** routing decision engine - no Spring, no JPA. Shared by catalog (authoring) and incident (evaluation) so both decide *identically* |

---

## The core flow: alert → incident → page

```
1. INGEST      integration-service authenticates the integration key (REST → identity),
               normalizes the payload, writes raw-audit + outbox rows in ONE transaction,
               returns 202 with a dedupKey.  → outbox relay publishes alert.received.v1

2. DEDUP       incident-service consumes the alert. At most one OPEN alert per (org, dedupKey)
               - a partial unique index enforces it. Repeats bump occurrence_count + timeline
               DUPLICATE; they never spawn a second incident.

3. ROUTE       a new actionable alert opens an Incident. Routing is evaluated LOCALLY against
               the replicated ruleset projection (routing-core, first-match-wins). The matched
               rule id + ruleset version are stamped; a ROUTED timeline line is written.

4. ESCALATE    incident.lifecycle.v1 (TRIGGERED) → escalation-service materializes ordered tasks
               per policy level (with repeat rounds), resolves each target (user / team / schedule
               → identity & schedule lookups), and on each due task emits notification.requested.v1.

5. PAGE        notification-service fans out to the responder's enabled contact methods, reserves
               a Redis cooldown to collapse repeat pages, delivers (email/webhook) with bounded
               retry, and emits notification.delivered.v1 / .failed.v1.

6. ACK/RESOLVE operator calls POST /v1/incidents/{id}/acknowledge|resolve|cancel. State transition
               is idempotent + member-gated; the linked alert follows the incident; a lifecycle
               event is published → escalation CANCELS all pending tasks. No more pages.
```

Every step is **idempotent** and **retry-safe**, and every meaningful action appends a timeline event - the non-negotiable engineering rules below.

---

## Architecture decisions that matter

### 1. Transactional outbox - no dual-write

Writing to the database *and* publishing to Kafka in one logical step is the classic dual-write hazard: either can succeed while the other fails. Heimcall never does both directly. Producers call `OutboxAppender`, which `INSERT`s into an `outbox` table **inside the caller's transaction**. A `@Scheduled` `OutboxRelay` polls, publishes with confirm, and marks `PUBLISHED`.

- Broker down? Rows sit `PENDING`, the ingest API still returns `202` (durably accepted), and the backlog drains on recovery - no 503, no caller coupling to broker health.
- Transaction rolls back? No event was ever written. No ghost.
- The relay publishes via a *non-bean* `byte[]` `KafkaTemplate` so the tracing post-processor can't rewrite the stored headers - correlation/trace context is preserved exactly as captured.

Wired into all four producing services (incident, escalation, notification, integration).

### 2. Event ordering under concurrency

Kafka orders only *within a partition*. Two separate problems, two fixes:

1. **Cross-event ordering.** The four incident lifecycle events used to live on four topics - escalation could see an `ACK` before the `TRIGGERED` it cancels, scheduling tasks nobody cleans up (a spurious page). Fixed by collapsing them onto **one topic `incident.lifecycle.v1`, partition-keyed by `incidentId`**, with a `@KafkaHandler`-per-type dispatcher.
2. **Cross-replica ordering.** incident-service runs HPA min 2, so two outbox relays could still publish one aggregate's rows out of order. The relay claim carries a **per-aggregate ordering guard** (`AND NOT EXISTS lower-id PENDING same aggregate_id`): a later row for an aggregate is unclaimable until the earlier one is published. Per-aggregate order holds across instances; different aggregates still parallelize.

Both are covered by concurrent-claim PostgreSQL locking tests.

### 3. Routing as a replicated read-model

Routing decides which escalation policy a brand-new incident belongs to - it is squarely on the critical path. A synchronous call to service-catalog on every incident would couple incident creation to catalog availability. Instead:

- The routing logic is a **pure engine** in `libs/routing-core`: a typed condition tree (`ALL`/`ANY`/`NOT` + field/operator/value leaves over system & metadata fields), 16 operators, RE2J regex (compiled at save, never per-event), DST-aware org-timezone time windows, first-match-wins with a pinned fallback. Its missing/null semantics are deliberately designed to dodge PagerDuty's "does-not-equal also matches missing" footgun.
- service-catalog **authors** rules (member-gated CRUD, reorder, plus a **dry-run preview with a full per-predicate trace**) and publishes a full **versioned ruleset snapshot** (`routing.ruleset-published.v1`) through *its own* transactional outbox.
- incident-service **consumes** snapshots into a **version-gated PG projection** and evaluates routing **locally** with the same `routing-core` engine. A catalog outage no longer affects routing - at worst the next version is delayed. Cold miss → one-time sync hydration; a scheduled repair job reconciles stale snapshots; explicit projection states (`READY`/`STALE`/`ABSENT_CONFIRMED`/`UNINITIALIZED`) keep routing on the last-known-good ruleset rather than ever dropping a page.

Catalog and incident share the engine library, so authoring-time preview and runtime evaluation can never diverge.

### 4. Lock-safe distributed workers

Every service that runs scheduled work runs ≥2 replicas, so any read-then-act worker risks double execution (double page, double send). All three claim work with `FOR UPDATE SKIP LOCKED`:

- escalation `fireDueTask`, notification `fireDelivery`, and the outbox relay each claim *per row* (not per batch), so each fire keeps its own transaction and replicas never block each other.
- Proven on kind: 200 zero-delay tasks under 2 escalation replicas → exactly 200 executions / 200 requests / 200 deliveries, zero duplicates. Deterministic DB tests (tx A holds the lock, tx B claims → 0 rows) lock the exactly-one-claimer semantics.

### 5. Zero-trust service-to-service security

- **User auth:** RS256 JWT. identity-service is the *sole signer* (RSA key + `kid`, publishes JWKS); every service verifies via JWKS with an RS256-only allowlist (`alg=none`/HS256 rejected) and derives `X-User-Id` only from a verified token - a client-supplied `X-User-Id` is stripped.
- **Service auth:** internal `/v1/internal/**` endpoints require a short-lived **service token** - OAuth2 `client_credentials` minted by a Spring Authorization Server, `token_use=service`, **single-audience** (`aud=<callee>`), scoped per endpoint via `@PreAuthorize("hasAuthority('SCOPE_…')")`. Same issuer and JWKS as user tokens, so verifiers need no second trust anchor. A user token reaching an internal endpoint has no `SCOPE_*` → 403; internal endpoints are machine-only by construction.
- **Network layer:** Helm ships **18 default-deny NetworkPolicies** - fleet default-deny ingress+egress, infra egress by port, and *per-service* ingress-from-actual-callers / egress-to-declared-callees generated from a `calls` graph. The notification webhook egress is **SSRF-guarded** (public 80/443 allowed; private/cluster/link-local CIDRs denied). Verified on a real **Cilium 1.19.5 + Hubble** cluster: a non-allowed pod pair shows `Policy denied DROPPED` while allowed pairs connect.

### 6. Kafka consumer resilience

Consumers use `ErrorHandlingDeserializer` (poison-pill safe) + `DefaultErrorHandler` with bounded retry, then `DeadLetterPublishingRecoverer` → `<topic>.DLT`. A real failure mode was found and fixed under load: a DLT producer's exact-match type serializer threw on a deserialized null-org record, so the DLT publish itself failed and a single-partition consumer redelivered the same poison message *forever* (`delivered=0`). Fix: byte[]-first delegating serializer with `assignable=true`. The poison message self-purged to `.DLT`, the backlog drained, and delivery recovered - verified live on the frozen consumer.

---

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.3.5, Spring Cloud 2023.0.3 |
| Build | Gradle 8.10.2 (wrapper), multi-project monorepo, shared config via root `subprojects {}` |
| Event backbone | Apache Kafka (KRaft) - topics named `<context>.<event-name>.v<version>` |
| Persistence | PostgreSQL, database-per-service, Flyway migrations |
| Cache / coordination | Redis (rate limiting, page cooldown) |
| Auth | RS256 JWT (JWKS) + OAuth2 client-credentials service tokens (Spring Authorization Server) |
| Orchestration | Kubernetes (Helm chart), validated on kind + Cilium |
| Observability | Micrometer + Prometheus, OTLP tracing, Grafana, KMinion (consumer-group lag) |
| Frontend | React + Vite + TypeScript |
| Email (dev) | Mailhog |

---

## Repository layout

```
libs/        common-domain, common-events, common-security, common-observability,
             common-outbox, routing-core, test-support      (plain libs, no bootJar)
services/    api-gateway, identity-service, service-catalog-service, schedule-service,
             integration-service, incident-service, escalation-service, notification-service
web/         React + Vite + TS UI  (dev server :5173, VITE_API_BASE → gateway :8080)
deploy/      docker-compose (local deps); helm/heimcall (k8s chart); kind/ (in-cluster infra)
docs/        specs + living current-state  (the source of truth - see "Project method")
```

**Kafka topics:** `alert.received.v1`, `incident.lifecycle.v1`, `escalation.requested.v1`, `notification.requested.v1`, `notification.delivered.v1`, `notification.failed.v1`, `routing.ruleset-published.v1`.

**PostgreSQL databases** (one per service, single instance): `incident`, `integration`, `identity`, `catalog`, `schedule`, `escalation`, `notification`.

---

## Running locally

Requires **JDK 21** and Docker.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# 1. Build everything
./gradlew build

# 2. Start local infra (Postgres, Kafka, Redis, Mailhog)
docker compose -f deploy/docker-compose/docker-compose.yml up -d

# 3. Run a service (repeat per service, or run the built bootJars for the full fleet)
./gradlew :services:incident-service:bootRun
#   full fleet without contending on the Gradle daemon:
#   java -jar services/<name>/build/libs/<name>-*.jar

# 4. UI
cd web && npm install && npm run dev      # http://localhost:5173
```

**Ports:** gateway 8080 · integration 8081 · incident 8082 · identity 8083 · catalog 8084 · schedule 8085 · escalation 8086 · notification 8087. PostgreSQL on host 5433 · Kafka UI 8090 · Mailhog SMTP 1025 / UI 8025.

**Sending a test alert:**

```http
POST /v1/integrations/{integrationKey}/events/{routingKey}
{
  "messageType": "CRITICAL",
  "entityId": "payment-api-5xx-rate",
  "entityDisplayName": "Payment API 5xx rate high",
  "stateMessage": "Error rate exceeded 5% for 5 minutes",
  "service": "payment-api",
  "severity": "CRITICAL",
  "source": "grafana",
  "metadata": { "env": "production" }
}
```

→ `202 { status, eventId, dedupKey }`. The `dedupKey` (`source:entityId`) is the correlation handle reused for follow-up ACK / RECOVERY events, following PagerDuty's Events API v2 convention.

---

## Observability

Built in, not bolted on (`common-observability`, one dependency per service):

- **Structured logging** - Logstash JSON logback; `traceId`/`spanId` in every line.
- **Correlation propagation** - `X-Correlation-Id` flows in/out over HTTP (servlet filter) *and* across Kafka (producer interceptor stamps it, record interceptor lifts it back into MDC on every listener).
- **Distributed tracing** - Micrometer Tracing → OTLP exporter; observation enabled on the services' own `KafkaTemplate`/listener factories.
- **Metrics** - Micrometer + Prometheus, including native Kafka client metrics and domain counters: `incident_unrouted_total`, `routing_rule_matched_total{ruleId}`, `notification.cooldown.suppressed`, outbox `published_total` / `publish_seconds` / pending gauge, per-stage and true end-to-end *alert→delivered* latency.
- **Consumer lag** - broker-side, via KMinion.
- **Dashboards** - Grafana pipeline throughput + latency.

Performance has been **measured, not assumed** - a load harness (k6 ramping-arrival-rate) drove the throughput work: pipelining the outbox relay's sends took it from ~100 → ~670 msg/s; partitioning `notification.requested.v1` + matching consumer concurrency took notification delivery ~4.5×.

---

## Engineering invariants

These are enforced, not aspirational:

- Every external payload is **normalized** before any domain processing.
- Every lifecycle command is **idempotent** (no-op when already in the target state).
- Every incident action creates a **timeline event** (append-only audit).
- Escalation workers **re-check incident state** before notifying.
- Notification delivery is **tracked separately** from incident state.
- Kafka consumers are **retry-safe**; exhausted failures are **dead-lettered**.
- Distributed scheduled work is **lock-safe** (`FOR UPDATE SKIP LOCKED`).
- **Tenant isolation by organization id** everywhere.
- **Cache is never the source of truth.**

---

## Project method

Heimcall is built spec-first. `docs/` leads the code and is the source of truth:

- `docs/00-current-state.md` - living "where are we now", updated every sprint.
- `docs/01-development-plan.md` - phased plan; new work is specced here first.
- `docs/02-prd.md` - product requirements · `docs/03-domain-glossary-ubiquitous-language.md` - ubiquitous language · `docs/04-acceptance-tests.md` - acceptance specs · `docs/05-runbooks.md` - operations.

The loop: **spec → (research industry/security practice where the domain warrants) → implement → verify at runtime → update the living state doc.** Architectural choices are recorded as decision records (question / decision / evidence / rejected alternatives), so the docs explain *why*, not just *what*.

---

<sub>Heimcall is a learning-grade platform built to exercise production distributed-systems patterns end to end. Product concepts are adapted from the public designs of PagerDuty, Opsgenie, and Splunk On-Call / VictorOps.</sub>
