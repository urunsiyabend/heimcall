# Heimcall Runbooks

Operational playbooks for the on-call engineer. Each runbook is **symptom → detect → diagnose →
mitigate → escalate**. They lean on the observability stack already in place (Phase 8):

| Tool | Where | Use |
| --- | --- | --- |
| Prometheus | `:9090` | ad-hoc metric queries, target health (`/targets`) |
| Grafana | `:3000` (admin/admin) | dashboards: *JVM/HTTP*, *Heimcall domain*, *PostgreSQL*, *Redis* |
| Jaeger | OTLP all-in-one | cross-service traces (traceId/spanId also in JSON logs) |
| Logs | stdout JSON (logstash encoder) | filter by `correlationId` / `traceId` |
| k8s probes | `/actuator/health/{liveness,readiness}` | pod restart + traffic gating |

Key domain metrics (see `docs/01-development-plan.md` §Phase 8): `incident_triggered_total`,
`incident_acknowledged_total`, `incident_resolved_total`, `incident_time_to_ack_seconds`,
`incident_time_to_resolve_seconds`, `notification_delivery_success_total`,
`notification_delivery_failure_total`, `escalation_task_executed_total`,
`kafka_consumer_fetch_manager_records_lag_max` (the exported name for consumer lag), `pg_up`, `redis_up`.

---

## RB-1 — A service is down / restarting

**Symptom.** A pod is `CrashLoopBackOff`, readiness is red, or Prometheus shows the `heimcall` target DOWN.

**Detect.**
- `kubectl get pods -l app.kubernetes.io/part-of=heimcall` — look for non-`Running` / high restart count.
- Prometheus `up{job="heimcall"} == 0`, or Grafana *JVM/HTTP* shows a service with no datapoints.

**Diagnose.**
1. `kubectl describe pod <pod>` — `Liveness/Readiness probe failed` events, OOMKilled, image pull errors.
2. `kubectl logs <pod> --previous` — read the JSON log; startup failures are usually:
   - **Flyway** — `Migration ... failed` / schema mismatch (`ddl-auto: validate` rejects drift).
   - **Datasource** — cannot reach `DB_URL`; check Postgres (see RB-6) and the secret keys.
   - **Kafka** — broker unreachable; `KAFKA_BOOTSTRAP_SERVERS` wrong.
3. Readiness fails but liveness passes ⇒ a downstream dependency (db/kafka) is unhealthy, not the app.
   The pod stays up, just out of rotation — fix the dependency, don't restart blindly.

**Mitigate.**
- Dependency outage: fix the dependency; readiness recovers on its own (startupProbe gives ~150s of grace).
- Bad deploy: `kubectl rollout undo deploy/<service>`.
- OOMKilled: bump `services.<svc>.resources.limits.memory` in values and `helm upgrade`.

**Escalate.** Platform/infra owner if Postgres/Kafka itself is down.

---

## RB-2 — Kafka consumer lag growing

**Symptom.** Incidents trigger late, notifications/escalations lag behind reality.

**Detect.** Grafana *Heimcall domain* (consumer-lag panel) or Prometheus:
`kafka_consumer_fetch_manager_records_lag_max{job="heimcall"} > 0` and trending up.

**Diagnose.**
1. Identify the lagging consumer by the `client.id`/service label. Primary consumers:
   incident-service (`alert.received`), escalation-service, notification-service.
2. Is the consumer alive? If the pod is down, it's RB-1. If alive but lag grows, it's slow processing
   or a poison message.
3. Check the **dead-letter topic** — a repeatedly failing record blocks nothing (retry-safe consumers
   dead-letter it), but a flood of DLT traffic points at a systemic payload/schema problem.
4. Trace a slow message in Jaeger (the Kafka hop carries trace context on record headers).

**Mitigate.**
- Throughput-bound: scale the consumer (`helm upgrade --set services.<svc>.autoscaling.enabled=true`
  or bump replicas) — but note partition count caps useful consumer parallelism.
- Poison message: confirm it's dead-lettered; if not, inspect and purge after fixing the normalizer.
- Downstream slow (e.g. notification provider): see RB-3.

**Escalate.** If lag is from a broker-side issue (under-replicated partitions), hand to infra.

---

## RB-3 — Notification delivery failures rising

**Symptom.** Acked incidents but responders report no page; `NOTIFY_FAILED` timeline events.

**Detect.** Grafana *Heimcall domain*:
`rate(notification_delivery_failure_total[5m]) / rate(notification_delivery_success_total[5m])` climbing.

**Diagnose.**
1. Failure concentrated on one channel (email/webhook/SMS) ⇒ provider/credential issue for that channel.
2. Check notification-service logs for the failing delivery's `correlationId`; the webhook sender emits a
   client span — find the callee status in Jaeger.
3. All channels failing ⇒ look upstream: is notification-service even consuming? (RB-2).

**Mitigate.**
- Provider outage: failures are dead-lettered/retried per the retry-safe contract; once the provider
  recovers, redelivery resumes. Confirm no records are being dropped.
- Bad credential/config: fix the secret/contact-method, `helm upgrade`, redeliver.

**Escalate.** Provider vendor if their status page is red.

---

## RB-4 — Incident trigger storm

**Symptom.** `incident_triggered_total` spikes; responders flooded.

**Detect.** Grafana *Heimcall domain* trigger-rate panel; sudden steep slope.

**Diagnose.**
1. Real outage vs. noisy integration. Group incidents by `source`/service — a single misbehaving
   monitor (e.g. a flapping check) usually dominates.
2. Dedup working? Repeated identical alerts should fold into one open incident (dedup key). A storm of
   *distinct* incidents from one source suggests the source isn't sending a stable dedup key.

**Mitigate.**
- Noisy source: mute/disable that integration key at the source, or pause routing.
- Real mass outage: this is expected signal — focus on the underlying outage, not the platform.

**Escalate.** Per the incident's escalation policy (the platform is doing its job).

---

## RB-5 — Slow time-to-ack / time-to-resolve

**Symptom.** `incident_time_to_ack_seconds` / `incident_time_to_resolve_seconds` p95 rising.

**Detect.** Grafana *Heimcall domain* timer panels (histogram quantiles).

**Diagnose.**
1. Ack slow but notifications succeed (RB-3 clean) ⇒ human/process problem (schedule gaps, escalation
   policy too lax), not a platform fault. Check schedule-service coverage for the on-call window.
2. Ack slow AND notifications failing ⇒ it's RB-3; fix delivery first.
3. `escalation_task_executed_total` flat while incidents stay unacked ⇒ escalation engine not firing;
   check escalation-service health and its schedule lookups.

**Mitigate.** Tighten escalation policy / fix schedule coverage; resolve any RB-3 delivery issue.

**Escalate.** Team lead for on-call coverage gaps.

---

## RB-6 — PostgreSQL trouble

**Symptom.** Multiple services readiness-red; DB errors in logs.

**Detect.** Grafana *PostgreSQL*: `pg_up == 0`, connection saturation, rising deadlocks/rollbacks.

**Diagnose.**
1. `pg_up == 0` ⇒ instance down/unreachable — every service's readiness fails (RB-1 cascade).
2. Connections maxed ⇒ pool exhaustion; one service leaking connections or traffic spike.
3. Deadlocks/rollbacks climbing ⇒ contention; correlate with a recent deploy via traces.

**Mitigate.**
- Instance down: restore Postgres; services self-recover via readiness once reachable.
- Pool exhaustion: cap/raise pool size per service; identify the leaker from per-db connection panels.

**Escalate.** DBA / infra owner for instance-level failure or storage issues.

---

## RB-7 — Redis trouble

**Symptom.** Degraded performance on cache-backed paths (cooldown/dedup once Redis-backed; deferred work).

**Detect.** Grafana *Redis*: `redis_up == 0`, memory near `maxmemory`, eviction/expiry spikes, low hit ratio.

**Diagnose.**
1. `redis_up == 0` ⇒ Redis down. Cache is **never the source of truth** (engineering rule), so correctness
   holds; expect higher latency and DB load, not data loss.
2. Evictions high ⇒ memory pressure; keys evicted early, hit ratio drops.

**Mitigate.** Restart/restore Redis; raise `maxmemory` if evictions are chronic. No data-recovery step —
caches rebuild from the source of truth.

**Escalate.** Infra owner for instance failure.

---

## RB-8 — High HTTP latency / error rate

**Symptom.** UI slow, gateway 5xx, alerts on request duration.

**Detect.** Grafana *JVM/HTTP*: `http_server_requests_seconds` p99 and 5xx rate per service.

**Diagnose.**
1. Localize: which service/endpoint? The gateway fans out, so a single slow upstream surfaces there.
2. Open a slow trace in Jaeger — client spans on internal REST hops (T4b) show exactly which hop is slow
   (e.g. incident → catalog, escalation → schedule).
3. JVM pressure? Check heap/GC panels — sustained GC ⇒ memory undersized (raise limits) or a leak.

**Mitigate.** Scale the hot service (HPA/replicas); fix or roll back the slow path; raise resources if
GC-bound.

**Escalate.** Service owner for an endpoint-level regression.

---

## Appendix — common commands

```bash
# Pod / rollout status across the fleet
kubectl get pods,deploy,hpa -l app.kubernetes.io/part-of=heimcall

# Tail one service's JSON logs, follow a correlation id
kubectl logs -f deploy/incident-service | grep <correlationId>

# Roll back a bad deploy
kubectl rollout undo deploy/<service>

# Port-forward the gateway locally
kubectl port-forward svc/api-gateway 8080:8080

# Prometheus instant query (lag)
curl -s 'http://localhost:9090/api/v1/query?query=kafka_consumer_fetch_manager_records_lag_max'
```
