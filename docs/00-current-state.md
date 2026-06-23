# Heimcall - Current State

Living document. Update at the end of every sprint. Reflects what is actually built and running, not what is planned. Plan lives in `01-development-plan.md`; this file is the source of truth for "where are we now".

Last updated: 2026-06-23 (Sprint 32 - Phase 16 T4 + **Phase 16 complete**: **NetworkPolicy default-deny (helm) + deferred T3 helm wiring.** `templates/networkpolicy.yaml` ships **18 policies** (gated `networkPolicy.enabled`): fleet default-deny ingress+egress; DNS egress; infra egress **by port** (5432/9092/6379/4318, so BYO/external infra works); per-service **ingress from its actual REST callers only** + **egress to its declared callees only**, generated from a `calls` graph in `values.yaml` (the caller→identity `/oauth2/token` mint hop rides the existing identity edge); gateway external ingress; configurable Prometheus ingress; notification **SSRF-guarded** webhook egress (public 80/443, private/cluster/link-local CIDRs denied — the documented exception for arbitrary customer webhooks) + SMTP 1025. Stable pod label `app.kubernetes.io/part-of=heimcall`. **Locked:** policy-aware CNI mandatory → verified on **Cilium 1.19.5 + Hubble** (kindnet silently ignores policies); full **per-pair** least-privilege. **Deferred T3 wiring carried in:** per-caller `HEIMCALL_TOKEN_URI` + `HEIMCALL_CLIENT_SECRET_<self>`, callee `HEIMCALL_SERVICE_NAME`, schedule/notification `serviceClientSecrets` — driven by per-service `clientName`/`serviceName` markers. **Redis wired** (was absent from helm): `redis` in kind `infra.yaml`, `infra.redis*`, `REDIS_HOST/PORT` for notification + gateway. **Gateway invariant test** `InternalRouteIsolationTest` (`/v1/internal/**` + `/oauth2/token` → 404 unrouted; real route → 5xx routed). Verified: `helm lint`/`template` clean (37 resources, 18 NetworkPolicies) + full suite green; **real-cluster e2e on Cilium+Hubble** (kind, full fleet — found+fixed stale pre-Phase-16 HS256 images mid-run, rebuilt fresh RS256): fleet `Running 1/1` under default-deny (no flow starved), product flow register→…→ingest→**TRIGGERED**→ack→**RESOLVED** with live token hops, **negative** notification→incident:8082 **dropped** (Hubble `Policy denied DROPPED`) while notification→identity:8083 connects. Documented: enforcement is CNI-dependent; webhook-SSRF/SMTP + Prometheus rules present but not live-exercised. **Phase 16 complete (T1-T4).**)

Earlier (Sprint 31 - Phase 16 T3: **enforce service tokens on internal endpoints + client wiring.** `/v1/internal/**` and `POST /v1/integration-keys/resolve` flipped from `permitAll` to `authenticated()` + per-endpoint `@PreAuthorize("hasAuthority('SCOPE_…')")` (method security enabled in `common-security`). `JwtAuthenticationFilter` now also accepts a **service token addressed to this service** (new `heimcall.jwt.service-name` = the callee's `aud`): it maps the token's `scope` claim to `SCOPE_*` authorities, sets the caller (`sub`) as principal, and injects **no `X-User-Id`** (a service token is never a user). A user token authenticates but has no `SCOPE_*` → 403, so internal endpoints are machine-only. **Decision: pure service-token, no dual-token** — the locked user-context variant (`X-Internal-Authorization` + user JWT) was dropped because no internal endpoint reads the acting user from a header (all take explicit path params) and the call sites are machine-context (Kafka handler / key-auth ingest / scheduled engine) with no user JWT; the forged-`X-User-Id` concern holds by construction. Scope-per-endpoint: members→`identity.membership.read`, teams→**`identity.team.read`** (new), team-members→`identity.team-members.read`, key-resolve→`identity.integration-key.resolve`, routing→`catalog.routing.resolve`, policy→`escalation.policy.read`, on-call→`schedule.on-call.read`. **T2 matrix gaps closed** (`CLIENT_SCOPES`): catalog +`identity.membership.read`/`identity.team.read`; schedule + notification added (`identity.membership.read`); escalation +`identity.team.read`. Client side: each caller adds `spring-boot-starter-oauth2-client` + one `registration.<callee>` per callee; new `ServiceTokenClients`/`ServiceTokenInterceptor` (own `@AutoConfigureAfter` the oauth2-client autoconfig) attach the callee-scoped token to every `*Client` via Spring's `AuthorizedClientServiceOAuth2AuthorizedClientManager` (cached, refresh-before-`exp`). Verified: unit (`JwtT3Test`) + `InternalEndpointAuthzTest` (real `/oauth2/token` mint → gated endpoint: no-token 401 / wrong-`aud` 401 / wrong-scope 403 / correct 204) + full suite green; runtime (real PG/Kafka, bootJars): identity gate curl matrix + caller boot + **interceptor proven end-to-end** (integration ingest → real identity call; wrong-secret stack trace shows `ServiceTokenInterceptor → AuthorizedClientManager.authorize → mint → 401 invalid_client`). **Deferred to T4 (helm/k8s):** chart T3 env wiring (per-caller `HEIMCALL_CLIENT_SECRET_<self>` + `HEIMCALL_TOKEN_URI`, callee `HEIMCALL_SERVICE_NAME`, schedule/notification service-client secrets) + full-fleet kind e2e with every hop tokened + gateway-never-routes-`/v1/internal` test lock — folded into T4 since both touch helm. **Phase 16 T1-T3 done; T4 (NetworkPolicy + T3 helm wiring) remains.**)

Earlier (Sprint 30 - Phase 16 T2: service-token issuance via Spring Authorization Server. identity-service hosts the standard OAuth2 `client_credentials` token endpoint (`POST /oauth2/token`, `client_secret_basic`) minting short-lived RS256 **service tokens** (`token_use=service`, `sub=<caller>`, `aud=<callee>`, `scope`, `jti`, 5m). Signs with the **same `JwtKeys` RSA key + `kid`** as user tokens (SAS `JWKSource` bridged from T1), so service tokens verify against the existing JWKS with no verifier change — one issuer, one trust anchor. Universal `iss` moved to an absolute URL `https://identity.heimcall.internal` (SAS requires a URL issuer; set explicitly in SAS settings + shared by user tokens). **One canonical JWKS**: SAS serves it at `/v1/.well-known/jwks.json` (the standalone T1 `JwksController` removed); verifiers' `jwks-uri` unchanged. Caller→scope matrix is an authoritative **code constant** (`AuthorizationServerConfig.CLIENT_SCOPES`); only per-caller secrets are config. Single-audience invariant: every scope in one token targets one callee. Secrets fail-closed — weak/visible dev values in the `dev` profile (default for local runs), any other profile must inject `HEIMCALL_CLIENT_SECRET_*` or boot dies; identity stores only the BCrypt hash. `JwtVerifier.verifyService(token, aud)` added (T3 seam). Verified: unit (`JwtT2Test`) + sliced SAS boot (`ServiceTokenEndpointTest`, no Docker) + runtime (real PG): mint/verify, bad-secret 401, out-of-scope `invalid_scope`, cross-audience 400, same `iss`/`kid` as user tokens, canonical JWKS single endpoint, prod-profile fail-closed. **Issuance only — `/v1/internal/**` enforcement + client wiring is T3 (still `permitAll`).** Deferred to T3/T4: real overlap rotation (versioned clients; no consumer until T3), transport mTLS.)

## 1. Snapshot

| Area | State |
| --- | --- |
| Architecture | Microservices-first monorepo, Gradle multi-project |
| Build | `./gradlew build` green on Java 21 |
| Runtime verified | Sprint 9 Phase-7 tickets 1-3.1: full loop under real JWT through the gateway (register/login -> token -> CRUD across all services -> ingest -> incident TRIGGER + NOTIFIED), JWT enforced (401 no-token, refresh-as-access rejected, client X-User-Id spoof stripped), incident queries tenant-scoped (cross-org 403) |
| Sprint 32 | Phase 16 T4 + **Phase 16 complete** (NetworkPolicy + T3 helm wiring). `templates/networkpolicy.yaml`: 18 policies — fleet default-deny ingress+egress, DNS egress, infra egress by port (5432/9092/6379/4318), **per-service ingress-from-callers + egress-to-callees** from a `calls` graph, gateway external ingress, Prometheus ingress, notification **SSRF-guarded** webhook egress (public minus private/cluster CIDRs) + SMTP 1025. Deferred T3 wiring carried in (`HEIMCALL_TOKEN_URI`/`HEIMCALL_CLIENT_SECRET_<self>`/`HEIMCALL_SERVICE_NAME`, schedule+notification secrets). **Redis wired** into helm (kind infra + `REDIS_HOST/PORT` for notification/gateway) — was previously absent. `InternalRouteIsolationTest` locks gateway-never-routes-`/v1/internal`. Verified on a real **Cilium 1.19.5 + Hubble** kind cluster (kindnet ignores policies): full fleet Running under default-deny, product flow register→…→TRIGGERED→RESOLVED with live token hops, and a non-allowed pod-pair (notification→incident:8082) **dropped** with Hubble `Policy denied DROPPED`; allowed pair (notification→identity:8083) connects. Stale pre-Phase-16 HS256 images found+rebuilt fresh (RS256) mid-verify. `helm lint`/`template` clean (37 resources, 18 NetworkPolicies); full suite green. Enforcement is CNI-dependent (documented). |
| Sprint 31 | Phase 16 T3 (security hardening — enforce). Internal APIs (`/v1/internal/**` + key-resolve) now require a **service token** addressed to the callee (`heimcall.jwt.service-name`) with the endpoint's exact scope, via `@PreAuthorize`/`SCOPE_*` (method security in `common-security`); `JwtAuthenticationFilter` maps the token's `scope`→authorities and injects no `X-User-Id`. **Pure service-token** (dropped the locked dual-token: no internal endpoint reads a header-user; call sites are machine-context). New scope `identity.team.read`; T2 `CLIENT_SCOPES` gaps closed (catalog/schedule/notification/escalation). Callers wired with `spring-boot-starter-oauth2-client` + per-callee registrations + `ServiceTokenClients`/`ServiceTokenInterceptor` (client_credentials via `AuthorizedClientServiceOAuth2AuthorizedClientManager`). Verified: `JwtT3Test` + `InternalEndpointAuthzTest` (mint→gated: 401/401/403/204) + full suite; runtime curl gate matrix + interceptor proven end-to-end (wrong-secret → `ServiceTokenInterceptor → mint → 401 invalid_client`). Helm env wiring + kind full-fleet e2e + gateway route-lock test **deferred to T4** (both touch helm). |
| Last sprint | Sprint 27 - Phase 14 T2 + **Phase 14 complete** (Redis activation). **notification cooldown.** `NotificationService`'s fan-out loop now calls `CooldownService.tryReserve(incidentId, userId, channel)` before each `NotificationDelivery.pending(...)` save — `SET notification-cooldown:{incidentId}:{userId}:{channel} <ts> EX <window> NX` via `StringRedisTemplate.opsForValue().setIfAbsent(key, ts, Duration)`. Reserved → create the delivery; key already present → **suppress** (no delivery, `notification.cooldown.suppressed` counter +1, warn log). Collapses repeat pages when escalation requests a notification at multiple levels/rounds for one incident+user. **Fail-open**: any Redis error in `tryReserve` → returns true → the page proceeds (cooldown is never the source of truth, §3.2). Config: `notification.cooldown.enabled` (default true) + `window-seconds` (default 60); `spring.data.redis.host/port` added (servlet service → non-reactive lettuce). Idempotency: the request-id `existsById` guard runs before fan-out, so a redelivered request never re-reserves. **Accepted trade-off (documented):** the reserve writes to Redis inside the fan-out `@Transactional`, not enlisted in the DB tx, so a rare rollback after a reserve leaves the key set until expiry (could suppress the next page in-window); fan-out does only local DB saves (low rollback risk) + the key self-expires, so no compensating delete. Verified: `:notification-service:test` 12 pass / 1 skipped (claim test, no PG) incl. new `cooldownSuppressesTheChannelAndCountsIt`; service boots Redis-wired (readiness UP, zero Redis errors); real-Redis SET NX EX proven (first OK, repeat nil, TTL set). Full Kafka→delivery e2e left to the manual fleet gate (needs a `__TypeId__`-header producer). **Phase 14 done (T1 gateway rate limit + T2 cooldown).** Redis now wired into 2 services; locks/idempotency-cache/on-call-cache deliberately not on Redis (regress/redundant/low-value). |
| Sprint 26 | Phase 14 T1 (Redis activation). **Redis put to work for the first time** (it had run in compose since Phase 8 but was wired into zero services). The integration ingest route at the api-gateway now carries a Spring Cloud Gateway `RequestRateLimiter` filter backed by `RedisRateLimiter` (token bucket, atomic Lua), **keyed per `{integrationKey}`** (a `KeyResolver` reading the 3rd path segment of `/v1/integrations/{key}/events/{rk}`) so each integration is throttled independently. Over-limit → **429**; conservative dev defaults `replenishRate=5` / `burstCapacity=10` (env-overridable `RL_REPLENISH_RATE` / `RL_BURST_CAPACITY`); gateway added `spring-boot-starter-data-redis-reactive` + `spring.data.redis` (localhost:6379, env-overridable). Verified live (redis + gateway): burst > capacity → 10 forwarded then 10× 429; two keys throttled independently; `request_rate_limiter.{key}.tokens/.timestamp` in Redis with short TTL; **Redis down → fail-open** (RedisRateLimiter default — forwarded, no 429, ingest stays available) with a recovery resume. **Caveat (documented):** during a Redis outage the reactive lettuce client retries per request, so fail-open adds latency until Redis is back — acceptable for an availability-first ingest path; a short command timeout is the tightening lever if it ever matters. Closes §15 "rate limit integration endpoints". Frame: locks (Phase 11 SKIP LOCKED) + idempotency cache (DB ledgers) deliberately NOT moved to Redis (would regress / redundant); on-call cache deferred (measured low-value). T2 (notification cooldown) next. |
| Sprint 25 | Phase 13 T5 + **Phase 13 complete** (test coverage). **integration-service + schedule edge.** `AlertNormalizerTest` (3, Mockito): tenant resolve + `dedupKey = source:entityId` stamped onto the event (org/integration/routingKey/messageType/severity/externalEntityId), title fallback (`entityDisplayName ?? entityId`), resolve-before-persist (invalid key → `InvalidIntegrationKeyException`, writer never touched). `AlertEventWriterTest` (1, Mockito, real Jackson): `persist` writes the `raw_inbound_event` audit row + the `outbox` row with `dedupKey` as both aggregate id and message key. `OnCallCalculatorTest` (+1): the **DST transition-instant edge** the prior tests skipped (Berlin spring-forward 2026-03-29, 23h day) — the 09:00 **local** handoff still holds (08:30→period 1, 09:30→period 2), proving the `ChronoUnit.DAYS`-on-`ZonedDateTime` count is DST-aware. Mutation: `dedupKey` → `entityId:source` failed exactly the normalizer test, reverted. **Phase 13 done (T1-T5): repo went from ~3 tests to 62**, §16's unit/integration/contract strategy filled without Docker (Mockito + `@EmbeddedKafka` + compose-PG), every ticket mutation-verified. Deferred (documented): no containerized full-stack e2e (live-fleet manual run stays the e2e gate); claim tests guard the SKIP LOCKED semantics not the literal `@Query` string; `processed_event` ledger persistence not Kafka-level integration-tested. |
| Sprint 24 | Phase 13 T4 (test coverage). **notification-service.** `DeliveryServiceTest` (7, pure Mockito, real `SimpleMeterRegistry`): the **retry/backoff decision** — success → DELIVERED + `notification.delivered.v1` + success counter; first failure → retry at `now + 1×backoff` (PENDING, no terminal event); second failure → `2×backoff` (linear `attempts × backoff` scaling); exhausted (`attemptJustMade >= max`) → FAILED + `notification.failed.v1` + failure counter; guards (claim-empty → skip, no-sender → FAILED without wasting retries, missing-request → FAILED). `NotificationServiceTest` (3): fan-out → one PENDING delivery per **enabled** contact method (channel/destination asserted), no-enabled → request recorded + zero deliveries, idempotent on request event id. `NotificationDeliveryClaimTest` (1, compose-PG, `assumeTrue`-skip): `FOR UPDATE SKIP LOCKED` exactly-one-claimer so the same email/webhook is never sent twice across replicas. Net verified by mutation: terminal `>=`→`>` failed exactly the exhausted-attempts test, reverted → 11 green. |
| Sprint 23 | Phase 13 T3 (test coverage). **escalation-service.** `EscalationServiceTest` (11, pure Mockito, real `SimpleMeterRegistry`): the **task materialization repeat math** — `repeat_count=1` (2 rounds) × 2 levels → 4 tasks at `triggeredAt + round*roundSpan + delay` (offsets 0/300/300/600), never previously tested; the five scheduling guards (null policy / tasks-exist / policy-not-found / no-rules / idempotent); cancel-on-close (pending→CANCELED + `saveAll`) + idempotency; `fireDueTask` resolution (USER → one `notification.requested` + EXECUTED; claim-empty → skip; TEAM → one per member). `EscalationTaskClaimTest` (1, compose-PG, `assumeTrue`-skip): the **first automated proof of the Phase 11 T1 `FOR UPDATE SKIP LOCKED` claim** (A claims+locks → B's concurrent claim SKIP-LOCKED → empty → A commits EXECUTED → B retry still empty; previously only a manual psql session). Net verified by mutation: dropping the `round*roundSpan` offset failed exactly the materialization test, reverted → 17 green. Scope note: the claim test inlines the claim SQL (the production `@Query` `:id` named param can't be shared with a raw-JDBC `?` string), so it guards the SKIP LOCKED **semantics** on the `escalation_task` shape, not the literal production query string. |
| Sprint 22 | Phase 13 T2 (test coverage). incident-service **Kafka resilience** over an in-JVM `@EmbeddedKafka` broker (**no Docker, no DB**): `AlertReceivedResilienceTest` loads a sliced context — the real `KafkaConfig` (error handler, DLT recoverer, delegating serializer, type-header notification factory) + Boot Kafka auto-config + both listeners, with `IncidentService` `@MockBean`'d so no JPA/datasource. Four tests prove the infra behavior unit tests can't reach: poison-pill → `alert.received.v1.DLT` (byte[] delegate); **application exception** (deserialized event, `handle` throws) → DLT after retries via the Object→JSON delegate (the **regression guard for the Phase 10 T1 `assignable=true` DLT-serializer fix**); handled event → not dead-lettered; `notification.delivered.v1` + `__TypeId__` header → dispatched through the type-header factory to `recordDelivered`. Net verified by mutation: `DelegatingByTypeSerializer(..., true)`→`false` made exactly the application-exception test fail (poison-pill still routed), reverted → green. Added `spring-kafka-test` to incident-service test scope. Deliberate scope note: the `processed_event` ledger idempotency is not integration-tested at the Kafka level (needs a real DB; the `handle()` guard is T1-unit-covered + e2e-exercised). |
| Sprint 21 | Phase 13 T1 (test coverage). Closes the standing testing debt for the core engine: incident-service had **zero** tests while owning the most regression-exposed logic. Added **18 pure-Mockito unit tests, no infra**, over three areas: `IncidentServiceTest` (8) — the `Event → Alert → Incident` mapping (CRITICAL/WARNING→open, RECOVERY→close+resolve, ACKNOWLEDGEMENT→ack, INFO→no-incident), dedup (repeat collapses onto the open alert, no second incident), redeliver-is-no-op idempotency, and the UNROUTED / ROUTED_FROM_CACHE routing branches; `IncidentCommandServiceTest` (6) — the operator ACK/resolve/cancel transition guards (idempotent no-op in target state, illegal transition→409, not-found, member-auth, alert-sync); `RoutingAvailabilityResolverTest` (4) — the Phase 10 T4 decision table (200→write-through+routed, 404→tombstone+unrouted, outage+cache-hit→fromCache, outage+miss→rethrow). **Infra decision locked (no Testcontainers):** confirmed empirically that Testcontainers can't start a container on this box — its bundled docker-java `UnixSocketClientProviderStrategy` pings at the hardcoded `RemoteApiVersion.VERSION_1_32` and Docker engine 29.x has `MinAPIVersion=1.40` (`client version 1.32 is too old`); `DOCKER_API_VERSION` is not honored and a BOM bump 1.20.4→1.21.3 didn't move the floor. So the phase routes around Docker: domain logic → Mockito (the bulk, CI-portable), Kafka paths → `@EmbeddedKafka` (in-JVM, T2+), DB-specific SQL → compose-PG `assumeTrue`-skip (the `OutboxRelayOrderingTest` precedent). Net verified by a mutation check: disabling `markUnrouted()` made exactly the `critical_noMatch` test fail, then reverted → green. T2-T5 (incident Kafka resilience, escalation, notification, integration) outlined in the plan. |
| Sprint 20 | Phase 12 T1 (lifecycle event ordering). The four incident lifecycle events were on **four separate Kafka topics**; Kafka orders only within a partition, so escalation could process an ACK/RESOLVE before the TRIGGERED it cancels → tasks scheduled that nobody cancels → **spurious page for an already-handled incident**. Fixed by collapsing them into **one ordered topic `incident.lifecycle.v1`, partition-keyed by `incidentId`** (contract break, approved): `IncidentEventPublisher` publishes all four there; escalation's `IncidentEventListener` became a class-level `@KafkaListener` + `@KafkaHandler`-per-type dispatch (type from `__TypeId__`). Second half — incident-service runs **HPA min 2**, so two `common-outbox` relay instances could still publish an incident's TRIGGERED and ACK out of order: added a **per-aggregate ordering guard** to the relay claim (`AND NOT EXISTS lower-id PENDING same aggregate_id`), so a later same-aggregate row is unclaimable until the earlier one is PUBLISHED — per-aggregate publish order holds across instances, different aggregates still parallel. **First real automated tests beyond `OnCallCalculatorTest`:** a PG locking test proving the guard (two concurrent claimers) + a dispatch unit test. Verified e2e on the local fleet: trigger + immediate ACK (delay-60 rule) → task CANCELED, zero `notification.requested`, timeline `TRIGGER,ACK` (no spurious page); regression (trigger, no ACK) → escalation fires + publishes `notification.requested` (paging intact); all lifecycle events now on `incident.lifecycle.v1`. |
| Sprint 19 | Phase 10 T4 (routing availability cache). `incident-service` now pages from a **last-known-good routing cache** when service-catalog is unavailable, instead of dead-lettering a real incident. Before: a catalog outage longer than the retry budget threw `RoutingUnavailableException` → tx rollback → retry → DLT (no page). Now a new `RoutingAvailabilityResolver` wraps `CatalogClient`: a live 200 **writes through** to a `routing_cache` table (Flyway `V7`, PG, no TTL); a 404 **tombstones** the row + UNROUTED (T3 path); a catalog **outage** falls back to the cached route (`routed_from_cache=true`, `ROUTED_FROM_CACHE` timeline, `incident_routed_from_cache_total`, UI badge) so escalation still fires — or, if no cached route (never-seen key), re-throws → DLT (no orphan, unchanged). Only ROUTED (200-with-policy) is cached. An audit-only `@Scheduled` **reconciliation job** (scoped to `routed_from_cache` incidents, grouped by distinct routingKey, capped, aborts while catalog down) re-resolves after recovery and stamps `reconcile_result` `CURRENT_MATCH` / `CURRENT_DRIFT` (→ `routing_cache_drift_total`) / `CURRENT_NOT_FOUND` (→ tombstone) — never re-pages, never mutates the route. Verified on local fleet (catalog killed/restarted): warm→ROUTED+cache write; outage+seen key→from-cache page (escalation EXECUTED); outage+never-seen→DLT, no orphan; recovery→reconcile MATCH (+ cold-catalog abort/retry backoff), DRIFT (+ counter), NOT_FOUND (+ tombstone). |
| Sprint 18 | Phase 11 T1 (concurrency safety). escalation + notification `@Scheduled` workers made **lock-safe across replicas**. `fireDueTask` / `fireDelivery` previously read-then-checked (`findById` + `status != PENDING`) with no row lock → two replicas (or old+new pod on a rolling restart) could both fire one task → duplicate `notification.requested` (double page) / duplicate send. Fixed with a `FOR UPDATE SKIP LOCKED` per-task claim (`findPendingForUpdate`, native query) on `EscalationTaskRepository` + `NotificationDeliveryRepository`, mirroring the `common-outbox` relay — no schema change, no Redis. Per-task (not whole-batch) so each fire keeps its own tx. **Known trade-off (accepted + documented):** claim + work share one `@Transactional`, so the lock is held across the side-effect — mild for escalation (only target-resolve REST + a *local* outbox INSERT, no Kafka send under lock), but in notification it spans the real SMTP/webhook send (bounded ~5s). `SKIP LOCKED` locks only that row, so no cross-replica blocking; two-phase claim is the deferred evolution if notification scales out. Verified on kind: 200 delay-0 tasks under 2 escalation replicas → 200 EXECUTED / 200 requests / 200 deliveries (zero dup); deterministic DB test (tx A holds lock, tx B concurrent claim → 0 rows) proves exactly-one-claimer. |
| Sprint 17 | Phase 10 T3 (routing reliability). `incident-service` turns a definitive routing no-match into a deliberate, observable **UNROUTED** outcome instead of a silent `NO_POLICY` afterthought. After T2 made catalog resolution total, `routing.isEmpty()` is *exactly* a no-match (an outage throws `RoutingUnavailableException` before this point), so on a no-match the incident is created flagged `unrouted=true` (Flyway `V6`): a distinct `UNROUTED` timeline event (replaces `NO_POLICY`), an `incident_unrouted_total` counter (incremented AFTER_COMMIT off the `Triggered` event, which now carries an `unrouted` flag), and `unrouted` on `IncidentResponse` + a UI badge. The `Triggered` event still publishes with `policyId=null`, so escalation short-circuits — no escalation fires, but "nobody paged" is now a counted, queryable decision. Verified on kind: unrouted ingest → `unrouted=true` + UNROUTED timeline + `incident_unrouted_total 1.0` + zero escalation tasks/deliveries; routed regression (org-default set) → `unrouted=false`, policy stamped, escalation fired → EMAIL delivery. |
| Sprint 16 | Phase 10 T2 (routing reliability). `service-catalog` org-default catch-all escalation policy makes routing resolution **total**. New `org_routing_default` table (Flyway V3, one row per org) + `OrgRoutingDefaultController` (`PUT/GET/DELETE /v1/organizations/{orgId}/routing-default`, member-gated, default policy validated against escalation-service → 409 on unknown/foreign). `InternalController.resolve` now resolves: specific service with a policy → that policy; else (no service, or matched service with no policy) → org default if set; else 404 — so a 200 never carries a null `escalationPolicyId` anymore. incident-service unchanged (already treats 404 as `Optional.empty()`; the no-default 404 is what T3 turns into a visible UNROUTED outcome). Gateway route added for the new subpath. Verified end-to-end on kind: no default + unmapped key → incident with null policy; default set → resolve returns default → incident stamped + escalation engine fired a task on the default policy → notification.requested; bogus default → 409; clear → 204/404/no-match restored. |
| Sprint 15 | Phase 10 T1 (routing reliability). `incident-service` `CatalogClient.resolve` now distinguishes a definitive no-match (catalog 404 → `Optional.empty()`) from an infra failure (5xx/IO/timeout → throws `RoutingUnavailableException`), so a transient catalog outage no longer silently de-pages a real incident — the `@Transactional` `handle` rolls back (no orphan) and the event retries → DLT. Two latent bugs fixed in the same slice: (a) `CatalogClient` had no HTTP timeout → consumer thread hung on an endpoint-less ClusterIP, stalling the partition (now connect 2s/read 3s); (b) the incident DLT `DelegatingByTypeSerializer` was exact-match → DLT publish threw `SerializationException` for any deserialized-object value → infinite retry loop (dead-lettering was broken for ALL application exceptions, only poison-pills worked; now `assignable=true` + ordered map). Also shipped earlier this sprint: a load-measurement-driven fix moving integration-service's identity key-resolve OUTSIDE the ingest tx (don't hold a DB connection across a network call). All verified on kind with real Kafka/PG. |
| Tests | `OnCallCalculatorTest` (rotation math); `OutboxRelayOrderingTest` (common-outbox per-aggregate ordering guard, real PG, 2 concurrent claimers); `IncidentEventListenerTest` (escalation lifecycle dispatch); **Phase 13 T1** incident-service unit suite — `IncidentServiceTest` (Event→Alert→Incident mapping/dedup/idempotency/routing branches), `IncidentCommandServiceTest` (lifecycle transition guards), `RoutingAvailabilityResolverTest` (routing decision table); 18 tests, pure Mockito, no infra. **Phase 13 T2** `AlertReceivedResilienceTest` — Kafka resilience over `@EmbeddedKafka` (in-JVM broker, no Docker/DB): DLT routing (poison-pill + application-exception) + type-header feedback dispatch; 4 tests. **Phase 13 T3** escalation-service — `EscalationServiceTest` (11, Mockito: materialization repeat math, scheduling guards, cancel-on-close, fireDueTask resolution), `EscalationTaskClaimTest` (1, compose-PG: FOR UPDATE SKIP LOCKED exactly-one-claimer). **Phase 13 T4** notification-service — `DeliveryServiceTest` (7, Mockito: retry/backoff + terminal FAILED + guards), `NotificationServiceTest` (3: enabled-contact fan-out), `NotificationDeliveryClaimTest` (1, compose-PG: no-double-send claim). **Phase 13 T5** integration-service `AlertNormalizerTest` (3) + `AlertEventWriterTest` (1) + schedule `OnCallCalculatorTest` DST spring-forward edge (+1). **Phase 13 complete — 62 tests total.** Note: **Testcontainers is unusable on this box** (bundled docker-java pings at API 1.32, Docker engine 29.x `MinAPIVersion=1.40` → `client version 1.32 is too old`; `DOCKER_API_VERSION` not honored, BOM 1.20.4→1.21.3 didn't help). Strategy (Phase 13): Mockito for domain logic, `@EmbeddedKafka` for Kafka paths, compose-PG `assumeTrue`-skip for DB-specific SQL (schema-isolated). |
| Auth | Real JWT (**RS256**, `libs/common-security`): identity is the sole signer (RSA key + `kid`, publishes JWKS); every service verifies Bearer via JWKS (RS256-only allowlist, `alg=none`/HS256 rejected) and derives `X-User-Id` from the verified user token. **Service tokens** (client_credentials, `token_use=service`, `aud=<callee>`, scoped) authenticate internal `/v1/internal/**` + key-resolve calls — same issuer/JWKS, mapped to `SCOPE_*`. Shared HS256 secret removed (hard cut, Phase 16 T1). |

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
  common-events      event records (Alert*, Incident triggered/acknowledged/resolved/canceled, Notification*), Topics constants. Incident lifecycle events share one ordered topic `incident.lifecycle.v1` (Phase 12; the 4 per-event topic constants removed) — type carried in `__TypeId__`.
  common-security    HS256 JWT auto-config: JwtSupport (issue/verify), JwtAuthenticationFilter (Bearer -> principal + derives X-User-Id), stateless SecurityFilterChain. Added by every service via one dependency.
  common-observability  (Phase 8 T1-T4a) auto-config: logstash JSON logback, servlet CorrelationIdFilter (X-Correlation-Id in/out via MDC), Kafka CorrelationProducerInterceptor (stamps id on outbound records) + CorrelationRecordInterceptor (lifts id back into MDC on every listener); micrometer-registry-prometheus + native Kafka client metrics; (T4a) micrometer-tracing-bridge-otel + OTLP exporter, KafkaTracing BPP enabling observation on the services' own KafkaTemplate/listener factory beans, traceId/spanId in the JSON logs, TracingDefaultsEnvironmentPostProcessor (sampling + OTLP endpoint defaults). Added by every service via one dependency.
  common-outbox     (Phase 9 T1) transactional outbox auto-config: OutboxAppender (JdbcTemplate INSERT into `outbox`, joins the caller's tx), OutboxRelay (@Scheduled FOR UPDATE SKIP LOCKED poll -> confirmed publish via a non-bean byte[] KafkaTemplate so the KafkaTracing BPP can't clobber the stored headers -> mark PUBLISHED), OutboxPrune (delete PUBLISHED past retention). Phase 12: the relay claim has a **per-aggregate ordering guard** (`NOT EXISTS lower-id PENDING same aggregate_id`) so per-aggregate publish order holds even across multiple relay instances. Forwards `__TypeId__` + `X-Correlation-Id` + `traceparent`. Wired into all four producing services:
    incident (T1), escalation + notification (T2), integration (T3).
  test-support       Testcontainers singletons (PG + Kafka) - not yet used
services/
  api-gateway        Spring Cloud Gateway, routes -> catalog 8084, schedule 8085, escalation 8086, identity 8083 (incl. /v1/auth/**), integration 8081, incident 8082; CORS for the UI origin; forwards Authorization (validation is per-service); **Redis token-bucket rate limit on the integration ingest route, keyed per integrationKey (Phase 14 T1)**
  identity-service   auth (register/login/refresh/me, JWT) + org/user/team/membership CRUD + integration-key issue/resolve + internal lookups, incl. team-member list (port 8083)
  service-catalog-service  monitored services CRUD + team ownership + tags + routing-key + validated escalation-policy + routing lookup (port 8084)
  schedule-service   on-call schedules, daily/weekly rotations, overrides, timezone-aware on-call resolution + internal on-call (port 8085)
  integration-service  webhook ingestion -> resolves key via identity -> stores raw -> publishes alert.received.v1 (acks=all)
  incident-service   consumes alert.received.v1 -> Event->Alert->Incident (alert dedup aggregate + occurrence log) -> routing/policy stamp -> timeline; lifecycle REST commands ACK/resolve/cancel; publishes incident.* (incl. canceled); DLT on failure
  escalation-service consumes incident.triggered -> runs policy (level tasks, worker, repeat) -> resolves targets -> notification.requested; cancels on ACK/RESOLVE (port 8086)
  notification-service consumes notification.requested -> fans out to recipient contact methods -> delivers (email/webhook) with bounded retry -> notification.delivered/failed (port 8087)
deploy/
  docker-compose     postgres(5433, dbs: incident + integration + identity + catalog + schedule + escalation + notification), kafka(9092 KRaft), redis(6379, now wired into the gateway for rate limiting — Phase 14 T1), mailhog(1025/8025)
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
  **Phase 16 T3:** all three (and `key-resolve` above) require a service token with `aud=identity` and the
  endpoint's scope (`identity.membership.read` / `identity.team.read` / `identity.team-members.read` /
  `identity.integration-key.resolve`); no longer `permitAll`.

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
- Org-default catch-all policy (Phase 10 T2): `PUT/GET/DELETE /v1/organizations/{orgId}/routing-default`
  - member-gated; `PUT {escalationPolicyId}` validated against escalation-service (unknown/foreign -> 409),
  one row per org (`org_routing_default`, Flyway V3); DELETE idempotent (204). Makes routing **total**.
- Internal routing lookup (service-to-service): `GET /v1/internal/organizations/{orgId}/routing?routingKey=`
  -> `{serviceId, escalationPolicyId, ownerTeamId}`. **Total** resolution (Phase 10 T2): specific service with
  a policy → that policy; else (no service, or matched service with no policy) → org default if configured;
  else 404. A 200 never carries a null `escalationPolicyId`. Used by incident-service.

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
- `POST /v1/integrations/{integrationKey}/events/{routingKey}` -> **202** `{status, eventId, dedupKey}`.
  Following PagerDuty's Events API v2, `dedupKey` (= `source + ":" + entityId`) is returned as the alert
  correlation handle the caller reuses for follow-up ACK/RECOVERY; `eventId` is the per-request /
  idempotency handle. A 202 means **durably accepted** (the relay will publish it), not "published to Kafka".
- Resolves the integration key via identity-service (`IdentityClient`, sync REST). Invalid key -> **401**;
  identity unreachable -> **503** (cannot validate, so nothing is stored). Dev placeholder org is gone.
- Validates payload (`messageType`, `entityId`, `source` required)
- Normalizes to `AlertReceivedEvent` with the resolved `organizationId` + `integrationId`,
  `dedupKey = source + ":" + entityId`
- **Transactional outbox (Phase 9 T3)**: `AlertNormalizer` is `@Transactional` — the raw audit row
  (`raw_inbound_event`, Flyway V1; now pure inbound audit, always `RECEIVED`) and the normalized event
  (`outbox.append`, Flyway V2) are written in one tx; `common-outbox`'s relay publishes `alert.received.v1`
  (key = dedupKey) to Kafka with confirm. No ghost on rollback, no loss after commit. The old synchronous
  `acks=all` `KafkaTemplate.send` + `EventPublishException`/503-on-publish-failure are gone: a broker outage
  now just leaves the row PENDING (drained on recovery) instead of 503ing the caller.

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
- Routing/ownership stamping (Phase 5 + Phase 10): on a new trigger, routing is resolved through
  `RoutingAvailabilityResolver` (wraps `CatalogClient`) to `{serviceId, escalationPolicyId}` and stamped
  (Flyway V3). After Phase 10 T2 catalog resolution is **total**, so a live resolve is one of two things: a
  policy (ROUTED) or a definitive no-match (`Optional.empty()`). A definitive no-match (T3) creates the
  incident flagged `unrouted=true` (Flyway V6, `incident.unrouted`): a distinct `UNROUTED` timeline event,
  the `incident_unrouted_total` counter, and `unrouted` on the query response (+ UI badge). No policy is
  stamped, so `incident.triggered.v1` carries `policyId=null` and escalation short-circuits — "nobody paged"
  is a visible, counted decision, never a silent fallthrough.
- Routing **availability** cache (Phase 10 T4): the resolver maintains a `routing_cache` table (Flyway V7,
  PG, **no TTL**, write-through on every live 200, tombstone on a 404). A catalog **outage** is NOT a
  no-match: the resolver falls back to the cached last-known-good route (`RoutingDecision.fromCache`) so the
  incident is created `routed_from_cache=true` (Flyway V7), gets a `ROUTED_FROM_CACHE` timeline event + the
  `incident_routed_from_cache_total` counter + a UI badge, and **escalation still fires on the cached policy**
  instead of the alert being dead-lettered. Only if there is **no** cached route (a never-seen routingKey
  during an outage) does the resolver re-throw `RoutingUnavailableException` → retry/DLT + tx rollback, no
  orphan (the T1 behavior). An audit-only `@Scheduled` `RoutingReconciliationJob` (scoped to
  `routed_from_cache` incidents with `reconciled_at IS NULL`, grouped by distinct `(org, routingKey)`, capped
  per cycle, aborts the cycle while catalog is still down) re-resolves after recovery and stamps
  `incident.reconcile_result` = `CURRENT_MATCH` / `CURRENT_DRIFT` (→ `routing_cache_drift_total` + refresh
  cache) / `CURRENT_NOT_FOUND` (→ tombstone the cache row). It never re-pages and never mutates the incident's
  route; `CURRENT_*` means "catalog differs now", not "the cached route was wrong at outage time".
- Domain events (Phase 9 T1, transactional outbox; Phase 12 single topic): `IncidentEventPublisher` is a
  synchronous `@EventListener` that appends the four lifecycle events to the `outbox` table inside the
  lifecycle transaction (via `common-outbox`'s `OutboxAppender`), all to the one ordered topic
  `incident.lifecycle.v1` keyed by `incidentId`; `common-outbox`'s relay publishes them in per-aggregate
  order. A rolled-back change writes no row (no ghost); a committed one is never lost
  (the prior `AFTER_COMMIT` `KafkaTemplate.send` could drop on a crash/broker-outage). At-least-once;
  consumers idempotent. The old `events*` producer beans in `KafkaConfig` were removed.
- Persistence (Flyway V1-V7): `incident` (`alert_count` dropped in V4; still carries `routing_key`,
  `service_id`, `escalation_policy_id`, `dedup_key`/`source`/`external_entity_id`, + `unrouted` boolean V6,
  + `routed_from_cache` boolean / `reconciled_at` / `reconcile_result` V7), `incident_timeline_event`,
  `alert`, `alert_occurrence`, `outbox` (V5: id BIGINT identity, topic/key/payload/headers/status, PENDING->PUBLISHED),
  `routing_cache` (V7: PK `(organization_id, routing_key)`, last-known-good route, no TTL)
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
- Live stream (Phase 7 ticket 4): `GET /v1/incidents/stream?organizationId=` (`text/event-stream`,
  member-gated). Per-org in-heap `SseEmitter` registry fed AFTER_COMMIT off `IncidentDomainEvents`; one
  `incident` event `{incidentId,status,at}` per TRIGGER/ACK/RESOLVE/CANCEL + a 20s `:ping` heartbeat.
  Auth via `access_token` query param (EventSource limitation), honored only on this path.

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
  - **Lock-safe across replicas (Phase 11 T1):** `fireDueTask` claims the row via `findPendingForUpdate`
    (`... WHERE id=? AND status='PENDING' FOR UPDATE SKIP LOCKED`); a concurrent worker/replica sees a locked
    or no-longer-PENDING row and skips, so a task fires **exactly once** even with multiple replicas. Lock is
    held until the tx commits (across the bounded target-resolve REST + the local `outbox.append` INSERT).
- Consumes the single ordered `incident.lifecycle.v1` stream (Phase 12): a class-level `@KafkaListener` +
  `@KafkaHandler`-per-type dispatch (TRIGGERED → schedule; ACKNOWLEDGED/RESOLVED/CANCELED → cancel
  still-PENDING tasks). One `incidentId`-keyed partition stream → ACK can no longer overtake the TRIGGERED
  it cancels.
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
  up to `max-attempts` (default 3), then FAILED + `notification.failed.v1`.
  - **Lock-safe across replicas (Phase 11 T1):** `fireDelivery` claims the row via `findPendingForUpdate`
    (`FOR UPDATE SKIP LOCKED`) so a delivery is sent **exactly once** even with multiple replicas. Trade-off
    (accepted + documented): the claim and the send share one `@Transactional`, so the row lock + DB
    connection are held across the **real** SMTP/webhook send (bounded ~5s by `notification.webhook.timeout-ms`).
    `SKIP LOCKED` locks only that one row → other replicas process other deliveries, no cross-replica blocking.
    Two-phase claim (`PENDING→SENDING`, send outside tx, `→DELIVERED/FAILED`) is the deferred evolution if
    notification scales out / providers get slow (see plan Phase 11). Delivery state is tracked
  separately from incident/request state (engineering rule).
- Senders: `EmailSender` (Spring `JavaMailSender` -> SMTP, mailhog locally) and `WebhookSender`
  (HTTP POST of a JSON body, bounded connect/read timeout; non-2xx throws -> retry).
- Visibility: `GET /v1/organizations/{orgId}/deliveries[?incidentId=&status=]` so failed messages are
  operationally visible.
- Resilience: `ErrorHandlingDeserializer` + bounded retry + DLT (mirrors incident-service Phase 3.5).

### Observability (Phase 8 T1, cross-cutting)
- `libs/common-observability` on every service (gateway, identity, catalog, schedule, integration,
  incident, escalation, notification) via one dependency + auto-config.
- **Structured logging**: logstash JSON logback (`logback-spring.xml`), MDC fields rendered into each line.
- **Correlation ids**: `CorrelationIdFilter` (servlet) reads/sets `X-Correlation-Id` (mints one if absent),
  puts it in the MDC and echoes it on the response. `CorrelationProducerInterceptor` stamps the id onto
  outbound Kafka records; `CorrelationRecordInterceptor` (on every listener container factory) lifts it
  back into the MDC on consume. Verified id propagates HTTP -> Kafka -> log end to end.
- Producer interceptor wired on the services that publish: integration, incident, escalation, notification.
- **Gateway**: reactive (WebFlux), so the servlet filter does not bind; it forwards client headers, so an
  upstream-supplied `X-Correlation-Id` still rides through. A reactive `WebFilter` is deferred.
- **Metrics (Phase 8 T2)**: `micrometer-registry-prometheus` on every service via `common-observability`
  (actuator already present). Each service exposes `/actuator/prometheus` + `/actuator/health` with
  `liveness`/`readiness` probe groups. Micrometer auto-instruments JVM, HTTP, and the spring-kafka
  listener/template timers.
  - Domain meters (exact Prometheus names, registered at startup): incident-service
    `incident_triggered_total`, `incident_unrouted_total` (Phase 10 T3),
    `incident_routed_from_cache_total` + `routing_cache_drift_total` (Phase 10 T4),
    `incident_acknowledged_total`, `incident_resolved_total` counters +
    `incident_time_to_ack_seconds`, `incident_time_to_resolve_seconds` timers (`IncidentMetrics`,
    AFTER_COMMIT off `IncidentDomainEvents`; trigger instant = incident `created_at`). notification-service
    `notification_delivery_success_total` (delivered) / `notification_delivery_failure_total` (terminal
    fail only, not retries). escalation-service `escalation_task_executed_total`.
  - Verified: incident-service scrape exposes all 5 meters; `health/liveness` + `health/readiness` UP.
- **Native Kafka client metrics (Phase 8 T3)**: `common-observability` attaches a `MicrometerConsumerListener`
  / `MicrometerProducerListener` to every Kafka factory after singletons exist (registry ready) and before
  containers start. Reached **through the `ConcurrentKafkaListenerContainerFactory` beans**, not by type:
  Boot can't autowire a service's `ConsumerFactory<String,Object>` into its default container factory (its
  provider is invariant `ConsumerFactory<Object,Object>`), so it builds a private, non-bean fallback consumer
  factory from yaml for the main listener — invisible to `getBeansOfType`. Skip-if-already-bound avoids a
  double bind. Result: `kafka_consumer_fetch_manager_records_lag_max` + the full `kafka_consumer_*` family
  exported for every consumer (verified incl. the primary `alert-received` consumer). Producer client metrics
  bind on first publish (listener attached to the producer-factory beans).
- **Distributed traces (Phase 8 T4a)**: `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` on
  every service via `common-observability`. Boot auto-configures the tracer, the OTLP/HTTP span exporter, and
  HTTP server+client (servlet + WebFlux) spans by classpath presence. Spans export to a local **Jaeger**
  all-in-one (compose; OTLP `:4318`, UI `http://localhost:16686`).
  - **Kafka spans**: `KafkaTracing` (gated on the `Tracer`) flips `observationEnabled(true)` on every
    `KafkaTemplate` and `ConcurrentKafkaListenerContainerFactory` **bean** via a `BeanPostProcessor` — Boot's
    `spring.kafka.{template,listener}.observation-enabled` flags only reach the factory/template Boot
    auto-creates, but each service defines its own (DLT + events producers, custom listeners). Same
    custom-factory gap as T3.
  - **Defaults without touching 8 ymls**: `TracingDefaultsEnvironmentPostProcessor` (registered via
    `META-INF/spring.factories`) adds a lowest-precedence source: `sampling.probability=1.0` (dev) and the
    OTLP endpoint (`OTLP_TRACES_ENDPOINT` overridable). Real config / env vars still win.
  - **Logs**: `traceId` + `spanId` added to the JSON encoder (Micrometer's correlation listener populates the
    MDC), so a log line ties to its trace alongside the existing `correlationId`.
  - Verified: one trace `HTTP POST -> alert.received.v1 send -> (incident) receive -> incident.triggered.v1
    send` spanning integration-service + incident-service, context propagated over Kafka record headers; both
    services report `otel.library=org.springframework.boot 3.3.5`. Warm client latency ~20ms; the larger
    end-to-end trace duration is async Kafka poll latency + cross-host clock skew, not request time.
  - **Sync REST hops (Phase 8 T4b)**: every internal `RestClient` (integration/incident/escalation/
    notification/schedule/catalog -> identity, incident -> catalog, escalation -> schedule, catalog ->
    escalation) and the notification `WebhookSender` now build from Boot's **auto-configured
    `RestClient.Builder`** bean (injected) instead of a raw `RestClient.builder()`. The auto-configured
    builder carries `RestClientObservationConfiguration`'s customizer, so each call emits a client span and
    propagates `traceparent` — the callee joins the trace. No yml/config change; observation comes from the
    customizer. Verified one trace spanning integration-service -> identity-service (`POST
    /v1/integration-keys/resolve`) + the integration -> incident Kafka hop in a single Jaeger trace.
- **Dashboards (Phase 8 T4c-1)**: Prometheus + Grafana in compose, scraping the fleet's `/actuator/prometheus`.
  - **Prometheus** (`prometheus/prometheus.yml`, UI `http://localhost:9090`): one `heimcall` job, 8 static
    targets (`localhost:8080`-`8087`) each tagged `service=<name>`; 15s scrape.
  - **Grafana** (UI `http://localhost:3000`, admin/admin, anonymous Viewer on): auto-provisioned Prometheus
    datasource (uid `prometheus`) + two dashboards in a "Heimcall" folder: **JVM & HTTP** (up, req-rate,
    avg-latency, 5xx, heap, CPU) and **Domain Metrics** (incident triggered/ack/resolved, MTTA/MTTR via
    `rate(sum)/rate(count)`, notification success/fail, `kafka_consumer_fetch_manager_records_lag_max`).
  - **Why `network_mode: host`**: the services run on the host, and this dev box runs firewalld, which drops
    docker-bridge->host packets — a bridged scraper + `host.docker.internal` times out (confirmed:
    host->`172.18.0.1:8082` 200, container->same timeout). Host networking has Prometheus scrape
    `localhost:808x` and Grafana read Prometheus at `localhost:9090` directly. Linux-only (fine for local dev).
  - Verified: booted incident-service, Prometheus target `incident-service` UP, Grafana datasource proxy
    resolves `incident_triggered_total{service=incident-service}`, both dashboards provisioned. Other 7
    targets down only because not running (identical config).
- **Infra dashboards (Phase 8 T4c-2)**: PostgreSQL + Redis exporters in compose, two more Grafana dashboards.
  - `postgres-exporter` (`:9187`, connects to the `postgres` container; `pg_stat_database` covers every db on
    the instance) and `redis-exporter` (`:9121`, `redis` container) run as bridge containers with ports
    published to the host, so the host-net Prometheus scrapes them at `localhost:9187`/`localhost:9121`
    (`postgres` + `redis` scrape jobs).
  - Dashboards (Heimcall folder): **PostgreSQL** (`pg_up`, connections/db, commits vs rollbacks, cache-hit
    ratio, tuples fetched/returned, deadlocks) and **Redis** (`redis_up`, clients, keys, memory, ops/s,
    keyspace-hit ratio, evicted/expired).
  - Note: adding scrape targets needs a `docker compose restart prometheus` (no `--web.enable-lifecycle`).
  - Verified: both targets UP, `pg_up`=1 / `redis_up`=1 via the Grafana datasource proxy, all four dashboards
    provisioned.

### Kafka topics in use
- `alert.received.v1` (integration-service -> incident-service) + `alert.received.v1.DLT`
- `incident.lifecycle.v1` (incident-service -> escalation-service): one ordered, `incidentId`-keyed stream carrying TRIGGERED/ACKNOWLEDGED/RESOLVED/CANCELED (Phase 12; replaced the four per-event topics so an ACK can't be processed before the TRIGGERED it cancels) + `incident.lifecycle.v1.DLT`
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
| ~~JWT secret is a shared HS256 dev default in yaml; no RS256/JWKS~~ DONE Phase 16 T1: RS256 + single-issuer JWKS, HS256 secret removed (hard cut), alg allowlist pinned in code | Phase 16 T1 |
| ~~No per-service service identity (internal calls carry no credentials)~~ ISSUANCE DONE Phase 16 T2: identity-service mints `client_credentials` service tokens (SAS, `/oauth2/token`), same RS256 key/`kid` as user tokens, code-level caller→scope matrix, single-audience, fail-closed secrets. **Enforcement still open**: `/v1/internal/**` + `key-resolve` remain `permitAll` until T3 | Phase 16 T2 (T3 enforces) |
| `/v1/internal/**` + `POST /v1/integration-keys/resolve` still `permitAll`; inter-service clients send no service token yet | Phase 16 T3 |
| Service-token credential rotation is replace-and-restart, not zero-downtime overlap; token transport is plaintext HTTP (NetworkPolicy only) | Phase 16 T3/T4 |
| Token revocation / introspection, refresh-token rotation/revocation, sender-constrained tokens | later (next horizon) |
| ~~Redis cache for integration-key resolution + cross-service tenant checks (latency)~~ MEASURED NOT WORTH IT: k6 load test (perf-mode CPU) showed the sync resolve hop is ~2-3ms and flat under load, not a bottleneck; ingest throughput is pod-CPU-bound (1 core), not connection/latency-bound. Earlier apparent saturation was the laptop's CPU governor (balanced→performance fixed it). | dropped (latency); routing **availability** cache is a separate Phase 10 follow-up |
| Ingest held a DB connection across the identity resolve network call (resolve was inside `@Transactional`) | DONE Sprint 15: `AlertEventWriter` split so resolve runs outside the tx |
| Incident routing: a transient catalog outage silently de-paged real incidents (resolve swallowed all errors) | DONE Phase 10 T1-T4. T1: 404 vs failure distinguished → retry/DLT, no orphan. T2: org-default catch-all → routing total. T3: genuine no-match → visible, counted `UNROUTED`. T4: routing-availability cache → outage pages from last-known-good (`routed_from_cache`), DLT only for a never-seen key; audit-only reconciliation. **Phase 10 complete** |
| ~~Integration ingest endpoint unprotected (no rate limit, §15)~~ DONE Phase 14 T1: gateway Redis token-bucket rate limit keyed per integrationKey → 429 over-limit, fail-open if Redis down | Phase 14 T1 |
| ~~Redis in compose but wired into zero services~~ DONE Phase 14: gateway rate limit (T1) + notification cooldown (T2). Locks/idempotency-cache/on-call-cache deliberately not on Redis (regress/redundant/low-value) | Phase 14 complete |
| RBAC beyond membership (role stored, not enforced per-action) | later |
| Integration-key revoke/rotate endpoints (revoke on entity, not exposed) | later |
| Single owning team only; multi-team ownership table | later |
| Rotation length DAILY/WEEKLY only; custom N-day rotations | later |
| DST transition-instant edge not tested; on-call has no Redis cache | later |
| `reassign` endpoint + `IncidentAssignment` model | later |
| Incident not fully leaned: still carries `dedup_key`/`source`/`external_entity_id` + backstop open-dedup index | later |
| `processed_event` ledger has no TTL/pruning (grows unbounded) | later |
| ~~Transactional outbox~~ DONE on all four producers via `common-outbox`: incident (T1), escalation + notification (T2), integration (T3) | Phase 9 complete |
| Routing is a flat `routingKey -> service` map; no Opsgenie-style rule engine (labels/severity/time) | later |
| TEAM target fan-out built (identity member list) but not runtime-exercised (USER + SCHEDULE were) | ongoing |
| Telegram channel (only EMAIL + WEBHOOK built) | later |
| Notification preference is just per-contact-method `enabled`; ~~no cooldown~~ DONE Phase 14 T2 (per-(incident,user,channel) Redis cooldown); per-incident throttle / quiet hours still later | Phase 14 T2 / later |
| Contact-method `destination` not format-validated (email/url); no verification step | later |
| ~~No Redis notification-cooldown cache~~ DONE Phase 14 T2 | Phase 14 T2 |
| ~~Workers (escalation/notification) not lock-safe on multi-replica~~ DONE Phase 11 T1: `FOR UPDATE SKIP LOCKED` per-task claim → exactly-once across replicas. Trade-off: lock held across notification's SMTP/webhook send (two-phase claim deferred) | Phase 11 |
| ~~`notification_delivered/failed` events have no consumer yet~~ DONE: incident-service consumes both -> incident timeline (NOTIFIED/NOTIFY_FAILED) | Phase 7 ticket 1 |
| ~~Tests: only `OnCallCalculatorTest`; no integration/contract tests~~ DONE Phase 13 (T1-T5): 62 tests — incident domain (18) + incident Kafka/`@EmbeddedKafka` (4) + escalation (12) + notification (11) + integration + schedule DST (5) + pre-existing (12). Mockito + EmbeddedKafka + compose-PG, no Docker. Remaining (deferred): no containerized full-stack e2e (live-fleet manual run is the e2e gate); claim tests guard SKIP LOCKED semantics not the literal `@Query`; `processed_event` ledger persistence not Kafka-level tested | Phase 13 complete |

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

Phases 1 + 3 + 4 + 5 + 6 + 7 complete; the trigger->notify loop is closed end to end, incidents have
real lifecycle REST commands, and a React UI (`web/`) drives auth + incident triage with live SSE
updates. **Phase 7 done** (see plan ticket breakdown):
- Ticket 1 DONE - notification.delivered/failed -> incident timeline (NOTIFIED/NOTIFY_FAILED).
- Ticket 2 DONE - real JWT auth: `libs/common-security` + identity register/login/refresh/me.
- Ticket 3 DONE - JWT propagated to all 6 services + gateway CORS + `/v1/auth` route.
- Ticket 3.1 DONE - tenant-scoped incident queries + ERROR-dispatch security fix.
- Ticket 4 DONE - SSE incident stream `GET /v1/incidents/stream`. Per-org in-heap `SseEmitter` registry
  (`IncidentStreamRegistry`) fed by `IncidentStreamPublisher` off `IncidentDomainEvents.{Triggered,
  Acknowledged,Resolved,Canceled}` AFTER_COMMIT (mirrors `IncidentEventPublisher`'s Kafka path, no ghost
  updates on rollback). Member-gated like the queries; emits `{incidentId,status,at}` + a 20s `:ping`
  heartbeat. Auth via `access_token` query param (EventSource can't set headers) — `common-security`
  accepts the query-param token **only on the stream path**, rejected elsewhere to limit URL-borne token
  exposure. Verified end-to-end (ingest->TRIGGERED, ACK, resolve all streamed; query-param 401 off-path).
  Known gaps (deferred): emitters are in this JVM's heap so a multi-replica deploy needs a Redis fan-out;
  `send` runs synchronously on the commit/heartbeat thread so a slow client can block it (Phase 8).
- Ticket 5 DONE - React + Vite + TS UI in `web/` (hand-written typed fetch client, no framework state lib).
  Login/register, access token in memory + refresh token in `localStorage` with on-load silent refresh and
  a single-flight 401->refresh->retry interceptor. `/me`-driven org selector + minimal create-org (POST org
  -> bootstrap self OWNER). Incident list with status filter, detail (timeline + alerts + lazy per-alert
  occurrences), status-aware ACK/resolve/cancel. SSE live updates via `EventSource` (access token in the
  `access_token` query param) — refetches the full list on (re)connect AND on every event (reload, not
  in-place patch, so the status filter stays honest; no `Last-Event-ID` replay). Dev server :5173, all calls
  through the gateway (`VITE_API_BASE`). Verified end-to-end against the running fleet incl. live SSE.
  Known gaps (deferred): refresh token in `localStorage` is XSS-readable — proper fix is an httpOnly
  `Set-Cookie` (backend sub-ticket); the `EventSource` keeps a stale access token if it expires mid-stream
  (no token-refresh reconnect) — manual reload re-auths.

Phase 7 complete (tickets 1-5). Note: `web/tasks/` (agent planning scaffolding) is git-excluded, not committed.

Operational note: the `common-security` lib carries two recent changes — the ticket 3.1 ERROR-dispatch fix
and the ticket 4 stream-path-scoped `access_token` query param. Both ship to every service on rebuild;
restart all services off fresh jars to propagate (happy paths unaffected either way).

**Phase 8 - Observability and Production Readiness** (complete):
- T1 DONE - `common-observability`: structured JSON logging + correlation ids across the fleet (HTTP->Kafka->log).
  Gateway reactive `WebFilter` deferred (servlet filter does not bind; client headers still forwarded).
- T2 DONE - Prometheus metrics + actuator probes: `/actuator/prometheus` + `health/{liveness,readiness}`
  on every service; domain meters on incident/notification/escalation (see §4 Observability).
- T3 DONE - native Kafka client metrics (consumer lag) fleet-wide via container-factory-reached Micrometer
  listeners (see §4 Observability).
- T4a DONE - OpenTelemetry distributed traces fleet-wide: HTTP + Kafka spans, OTLP -> Jaeger, traceId/spanId
  in JSON logs (see §4 Observability). Verified one cross-service trace over the Kafka hop.
- T4b DONE - sync REST hops join the trace: all internal `RestClient`s + the webhook sender build from Boot's
  auto-configured `RestClient.Builder` (observation customizer), so they emit client spans + propagate
  `traceparent` (see §4 Observability). Verified integration -> identity + integration -> incident in one trace.
- T4c-1 DONE - Prometheus + Grafana in compose: scrape all 8 services' `/actuator/prometheus` + two
  auto-provisioned dashboards (JVM/HTTP, Heimcall domain metrics). Host-networked (firewalld drops
  bridge->host); see §4 Observability. Verified incident-service scraped UP + domain metric queried via Grafana.
- T4c-2 DONE - PostgreSQL + Redis exporters in compose + two Grafana dashboards (see §4 Observability).
  Verified both targets UP + `pg_up`/`redis_up` queried via Grafana.
- T4c+ DONE - Kubernetes deploy + probes + HPA + runbooks. **Dockerfile per service** (`services/<svc>/
  Dockerfile`): multi-stage, `eclipse-temurin:21-jre`, Spring layered-jar extraction (dep layers cache),
  non-root uid 1001, `JarLauncher` entrypoint. Root `build.gradle` disables the redundant `-plain.jar` for
  boot services (`plugins.withType(SpringBootPlugin)`) so `COPY build/libs/*.jar` is unambiguous. **Helm
  chart `deploy/helm/heimcall`**: one range-over-services template each for Deployment / Service / HPA.
  Probes -> `/actuator/health/{liveness,readiness}`; startupProbe (failureThreshold 30 x 5s = ~150s grace)
  shields slow JVM+Flyway boot, liveness/readiness take over after. Shared `Secret` (jwt-secret + per-db
  username/password, dev defaults = db name, overridable via `secrets.db.<svc>`) + env wiring:
  `KAFKA_BOOTSTRAP_SERVERS`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `HEIMCALL_JWT_SECRET`, cross-service
  `*_BASE_URL` (cluster Service DNS); gateway also gets the route `*_URI`s + `HEIMCALL_UI_ORIGIN` +
  `serviceType: LoadBalancer`. HPA on api-gateway (2-5) + incident-service (2-6), CPU 70%. Gateway
  `application.yml` route URIs made env-overridable (`${CATALOG_URI:http://localhost:8084}` ...) — defaults
  stay local, k8s injects DNS. Chart deploys **only** the 8 services; Postgres/Kafka/Redis/Jaeger are BYO
  via `infra.*` in values. **Runbooks** `docs/05-runbooks.md`: 8 playbooks (service-down, kafka lag, notify
  failures, trigger storm, slow ack/resolve, postgres, redis, http latency) tied to the shipped metrics +
  Grafana dashboards. **Verified end-to-end on a real `kind` cluster** (not just render): `helm install`
  brought all 8 services to `Running 1/1` (liveness/readiness probes passing); api-gateway + incident-service
  ran 2 replicas each (HPA minReplicas) and after a `metrics-server` install the HPAs read real CPU
  (gw 1%/70%, incident 5%/70%). Full product flow through the gateway: register -> org -> membership ->
  integration-key -> ingest -> (Kafka) -> incident **TRIGGERED**; lifecycle ACK -> `ACKNOWLEDGED`,
  resolve -> `RESOLVED`, timeline `TRIGGER -> NO_POLICY -> ACK -> RESOLVE`. In-cluster deps (postgres w/ the
  7 service dbs, Kafka KRaft, Jaeger) come from `deploy/kind/` (infra.yaml + pg-initdb-configmap.yaml —
  reusable local-verify scaffolding; the chart itself stays deps-BYO). Three issues surfaced + fixed during
  the real deploy (render had hidden them): (1) stale `-plain.jar`s from pre-disable builds broke the docker
  COPY glob — `rm` them (the build.gradle disable is correct for fresh builds); (2) docker 29's
  containerd-store multi-arch index made `kind load docker-image` fail with "content digest not found" — the
  kind node pulled the infra images directly instead; (3) `confluentinc/cp-kafka` KRaft crashed opaquely
  (ensure-script exit 1, no log) — switched `deploy/kind/infra.yaml` to `apache/kafka:3.8.1` (self-formats,
  consumer groups joined). `helm lint` + template also clean (8 Deploy / 8 Svc / 2 HPA; gateway carries no
  DB env; db services map correct db + secret keys).
  Known gaps (deferred): default jwt/db secrets are DEV-ONLY (documented in NOTES + comments) — real envs
  override; no infra subcharts (deps are BYO); no Ingress (gateway via LoadBalancer/port-forward).

**Phase 8 complete** (T1-T4c+).

**Phase 9 - Transactional Outbox** (complete):
- T1 DONE - `libs/common-outbox` (auto-config: `OutboxAppender` / `OutboxRelay` / `OutboxPrune`) + wired into
  incident-service. `IncidentEventPublisher` now appends the 4 `incident.*` events to the `outbox` table
  inside the lifecycle tx (synchronous `@EventListener`) instead of an `AFTER_COMMIT` `KafkaTemplate.send`;
  the relay polls (`FOR UPDATE SKIP LOCKED`), publishes with confirm via a non-bean byte[] template (so the
  observability `KafkaTracing` BPP can't enable observation and overwrite the stored `traceparent`), marks
  `PUBLISHED`; published rows kept for audit and pruned past `heimcall.outbox.prune.retention` (P7D).
  Flyway `V5__outbox.sql`. Verified on real Kafka/Postgres: trigger -> PENDING row written in the same tx;
  process killed (relay parked) -> row survives; restart -> relay drains it (PUBLISHED, attempts=1, topic
  +1, no dup); published record carries `__TypeId__` + `X-Correlation-Id` + `traceparent` (matching the
  triggering log's trace/span), byte-identical to the old `JsonSerializer` form so consumers are unchanged.
  No-ghost-on-rollback is structural (same-tx `JdbcTemplate` INSERT).
- T2 DONE - escalation-service (`notification.requested`) + notification-service
  (`notification.delivered` / `notification.failed`) onto the same `common-outbox`. No new mechanism — each
  service gets the lib dep + its own `V2__outbox.sql` (table identical to incident `V5`); the inline
  `eventsKafkaTemplate.send(...)` calls (already inside `@Transactional` `fireDueTask` / `fireDelivery`)
  become `outboxAppender.append(...)` on the tx-bound connection — no separate `@EventListener` indirection
  needed here (unlike T1, where the publish was `AFTER_COMMIT`). Dead `eventsProducerFactory` /
  `eventsKafkaTemplate` beans removed from both `KafkaConfig`s (DLT beans untouched). Relay/prune come from
  the lib auto-config. Verified end-to-end on the full 8-service fleet (bootJars) through the gateway with
  real JWT: CRITICAL ingest -> incident TRIGGERED -> escalation task (delay 0) fired -> notification
  delivered -> EMAIL in mailhog; all three outbox tables (incident/escalation/notification) drained
  naturally to PUBLISHED, every row attempts=1 (no dup), `__TypeId__` + `traceparent` headers intact. No-loss
  across relay downtime re-confirmed: a hand-inserted PENDING row (a committed-but-unrelayed event) is
  drained on the next poll.
- T3 DONE - integration-service onto `common-outbox`. The ingest publish was the odd one out — *synchronous*
  (`acks=all`, broker outage -> 503), not an in-tx/AFTER_COMMIT send. Now `AlertNormalizer` is
  `@Transactional`: the `raw_inbound_event` audit row + the normalized `alert.received.v1` event are written
  in one tx (raw + `outbox.append`) and the relay publishes async. **Contract change (approved):** a broker
  outage no longer 503s the webhook caller — a **202 = "durably accepted"**, not "published to Kafka" (the
  relay drains the PENDING row on recovery). `raw_inbound_event` is now pure inbound audit (always
  `RECEIVED`; publish status moved to the outbox row). Following PagerDuty's Events API v2, the 202 body
  returns `{status, eventId, dedupKey}` — `dedupKey` is the alert correlation handle for follow-up
  ACK/RECOVERY. `EventPublishException` + the sync `KafkaTemplate` send removed. Flyway `V2__outbox.sql`.
  Verified on real Kafka/Postgres (identity + integration booted): ingest -> 202 `{status, eventId,
  dedupKey}` -> `outbox` row -> relay PUBLISHED (attempts=1, `__TypeId__`/correlation/`traceparent` intact)
  -> `alert.received.v1` carries the byte-correct message; `raw_inbound_event` `RECEIVED`. No-loss across
  relay downtime is the same lib relay verified in T1/T2.

**Phase 9 complete** (T1-T3): all four producing services publish domain events via the transactional outbox.

**Phase 10 - Routing Reliability** (T1-T4 done; **complete**). Goal: an incident never
silently fails to page anyone for a routing reason. Driven by a system-wide audit (finding #10) + industry research
(Alertmanager mandatory catch-all, PagerDuty suppressed-alert default, Opsgenie default rule).
- **T1 DONE** - `incident-service` distinguishes a definitive no-match (catalog 404 → `Optional.empty()`)
  from an infra failure (5xx/IO/timeout → `RoutingUnavailableException` → retry/DLT, `@Transactional`
  rollback so no orphan). Fixed two latent bugs en route: `CatalogClient` missing HTTP timeout (consumer
  hung on endpoint-less ClusterIP) and the incident DLT serializer exact-match (DLT publish failed →
  infinite loop for every application exception). Verified on kind (404 path, catalog-down→DLT+no-orphan,
  recovery). Retry budget kept short on purpose (single partition; blocking backoff would worsen stall).
- **T2 DONE** - `service-catalog` org-default catch-all escalation policy: routing resolve is now total
  (specific-with-policy → org-default → 404). New `org_routing_default` table (Flyway V3) +
  `OrgRoutingDefaultController` (`PUT/GET/DELETE /v1/organizations/{orgId}/routing-default`, member-gated,
  default policy validated against escalation-service → 409). `InternalController.resolve` returns the
  default when no specific policy matches; a 200 never carries a null `escalationPolicyId`. Gateway route
  added for the new subpath. incident-service unchanged. Verified on kind (default set → escalation fired on
  the default policy; bogus → 409; clear → no-match restored).
- **T3 DONE** - `incident-service` deliberate, observable UNROUTED outcome for a genuine no-match with no
  default. After T2 made resolution total, `routing.isEmpty()` is *exactly* a no-match (an outage throws
  before this point), so the old `NO_POLICY` branch becomes UNROUTED: incident flagged `unrouted=true`
  (Flyway V6), distinct `UNROUTED` timeline event, `incident_unrouted_total` counter (off the `Triggered`
  event's new `unrouted` flag), `unrouted` on the query response + a UI badge. `Triggered` still published
  with `policyId=null` → escalation short-circuits, no page. Verified on kind (unrouted ingest counted +
  not paged; routed regression still pages). "Nobody paged" is now a visible, counted decision.
- **T4 DONE** - `incident-service` routing **availability** cache: a catalog outage now pages from the
  last-known-good route instead of dead-lettering. New `RoutingAvailabilityResolver` wraps `CatalogClient`;
  `routing_cache` table (Flyway V7, PG, no TTL, write-through on 200, tombstone on 404). Outage + cached
  route → incident `routed_from_cache=true` (`ROUTED_FROM_CACHE` timeline + `incident_routed_from_cache_total`
  + UI badge), escalation fires on the cached policy. Outage + never-seen key → re-throw → DLT, no orphan
  (T1 path). Audit-only `@Scheduled` `RoutingReconciliationJob` (scoped to `routed_from_cache` incidents,
  grouped by distinct routingKey, capped, aborts while catalog down) stamps `reconcile_result`
  `CURRENT_MATCH` / `CURRENT_DRIFT` (→ `routing_cache_drift_total`) / `CURRENT_NOT_FOUND` (→ tombstone);
  never re-pages, never mutates the route. Verified on local fleet (catalog killed/restarted): warm→ROUTED+
  cache; outage+seen→from-cache page (escalation EXECUTED); outage+never-seen→DLT no-orphan; recovery→
  reconcile MATCH (+cold-catalog abort/retry backoff) / DRIFT (+counter) / NOT_FOUND (+tombstone).
- Open audit findings ranked for later phases (not silent-paging): ~~worker double-execution on multi-replica~~
  **DONE Phase 11 T1** (escalation/notification workers `FOR UPDATE SKIP LOCKED` per-task claim → exactly-once
  across replicas), ~~cross-topic lifecycle ordering race~~ **DONE Phase 12 T1** (single ordered
  `incident.lifecycle.v1` topic + outbox per-aggregate ordering guard), ~~outbox poison-row head-of-line
  block (no max-attempts/DLT)~~ **DONE Phase 15 T1** (relay classifies failures → poison/permanent rows
  dead-lettered `status='DEAD'` + `outbox_dead_total`, transient still break+retry with a max-attempts
  backstop), unbounded tables (alert_occurrence/timeline/raw_inbound_event/processed_event/
  notification_delivery), `/v1/internal/**` + key-resolve `permitAll` with no service auth/NetworkPolicy,
  single shared HS256 secret, SSE in-heap vs HPA min=2.

**Phase 11 - Concurrency Safety** (T1 done): escalation + notification `@Scheduled` workers made lock-safe
across replicas via a `FOR UPDATE SKIP LOCKED` per-task claim (`findPendingForUpdate`), mirroring the
`common-outbox` relay — no schema change, no Redis. Lock held across the side-effect (mild for escalation;
spans the bounded SMTP/webhook send for notification — accepted trade-off, two-phase claim deferred). Verified
on kind: 200 tasks under 2 escalation replicas → exactly-once (200/200/200, zero dup) + deterministic
concurrent-claim DB proof. See plan Phase 11.

**Phase 12 - Lifecycle Event Ordering** (T1 done): the four per-event incident lifecycle topics were
collapsed into one ordered `incident.lifecycle.v1` (partition-keyed by `incidentId`), so an ACK can no
longer be processed before the TRIGGERED it cancels (Kafka orders only within a partition). incident
publishes all four there; escalation consumes via a class-level `@KafkaListener` + `@KafkaHandler`-per-type
dispatch. The producer-side half (incident-service HPA min 2 → two relay instances) is closed by a
`common-outbox` relay **per-aggregate ordering guard** (`NOT EXISTS lower-id PENDING same aggregate_id`),
so per-aggregate publish order holds across instances. Tested: PG locking test (the guard) + dispatch unit
test + e2e (trigger+immediate-ACK → CANCELED, no page; regression still pages). See plan Phase 12.

**Phase 15 - Outbox Poison-Row Dead-Lettering** (T1 done): a single unrelayable outbox row no longer
stalls a service's entire event publishing. Reproduced first (real `OutboxRelay` vs compose-PG: a
corrupt-headers row as lowest id kept the whole relay PENDING, attempts climbing, `kafka.send` never
called for any row), then fixed. The relay now **classifies** failures instead of blindly `break`ing:
a pre-send poison (corrupt `headers`) or a permanent broker rejection (`RecordTooLargeException`,
`SerializationException`, `InvalidTopicException`, `UnknownTopicOrPartitionException`; cause chain scanned)
→ `status='DEAD'` + `continue` (rows behind it keep flowing); a transient failure (outage/timeout) keeps
the old `attempts++` + `break` (retry next poll), with a `heimcall.outbox.max-attempts` (default 10)
**backstop** that dead-letters an unforeseen always-failing row so it can never stall forever. DLT is
in-table (`DEAD` value — no new table/topic/Flyway; the claim + ordering-guard SQL already filter on
`PENDING`, so a DEAD row is invisible to the relay and *unblocks* its aggregate); visibility via an
`outbox_dead_total` Micrometer counter (optional `MeterRegistry`) + loud ERROR log; a DEAD row is
replayable by flipping it back to PENDING. Accepted trade-off: a total broker outage longer than
`max-attempts × per-poll-time` (~100s+ at the 10s publish-timeout) can dead-letter the head row (looks
identical to poison); replayable + alerted. Tested on compose-PG against the real relay: poison→DEAD +
good row PUBLISHED same round; transient→break+retry then backstop-DEAD; Phase 12 ordering regression
still green. See plan Phase 15.

**Phase 16 - Security Hardening** (T1 done): closing the two highest-risk open security findings as one
trust model (asymmetric issuer + scoped service identity + NetworkPolicy). **T1 done**: JWTs moved from a
shared HS256 secret (any verifier could forge) to **RS256 with a single issuer**. `identity-service` is the
sole holder of the RSA private key; it signs user access/refresh tokens (active `kid`) and publishes the
public keys at `GET /v1/.well-known/jwks.json` (+ authorization-server metadata). Every other service
verifies via that JWKS (`JwksKeyResolver`: cached, refetch-on-unknown-`kid`, single-flight); the signer
verifies in-process (`LocalKeyResolver`, no self-HTTP). The old single `JwtSupport` was split into
`JwtKeys`/`JwtIssuer`/`JwtVerifier` + `PublicKeyResolver` + `PemKeys`/`JwtClaims`. **Algorithm allowlist is
pinned in code** (RFC 8725 §3.1): the verifier fixes the accepted alg to the constant `RS256` and rejects
anything else *before* signature checking — `alg=none`, HS256, and even a validly-signed RS384/RS512 token
are refused; the `alg` header only triggers a rejection, never selects the algorithm; JWK `alg`/`use` are
advisory and never consulted. Also validates `iss`/`exp`/`nbf`/`aud`/`token_use` (refresh can't auth a
resource endpoint). The shared `HEIMCALL_JWT_SECRET` was **removed everywhere (hard cut)** — identity keeps
the private key, the six verifiers keep only `heimcall.jwt.jwks-uri`; helm updated to match. Verified: unit
`JwtT1Test` (10) + runtime on identity + incident (real Kafka/PG) — login mints an RS256 token, `/me` 200,
no-token/garbage/refresh-as-access → 401, and **cross-service** incident verified it via identity's JWKS
over HTTP (valid → 403 authz not 401; HS256-forged with the real `kid` → 401). See plan Phase 16.
Phase 16 **complete** (T1 RS256/JWKS trust spine, T2 client-credentials service tokens, T3 enforce on
internal endpoints + client wiring, T4 NetworkPolicy default-deny + helm wiring — see Sprint 32 row).

New follow-ups surfaced this sprint: a stale `deadbeef` null-org message in `notification.requested.v1`
(prior manual test) stalls notification-service's consumer — clean it / harden that consumer's error path;
Testcontainers is unusable locally (docker-java API 1.32 vs daemon ≥1.40) so integration tests fall back to
the compose PG — revisit if CI needs container-isolated tests.

Plus cross-cutting hardening still open (Redis caches/cooldown, `processed_event` TTL,
`reassign` + `IncidentAssignment`; JWT secret rotation/RS256 now closed by Phase 16 T1).
