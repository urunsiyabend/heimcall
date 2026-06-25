package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

/**
 * Phase 8 T2 domain metrics. Mirrors {@link IncidentEventPublisher}: listens to the in-process
 * lifecycle events {@code AFTER_COMMIT} so a rolled-back change moves no counter. Time-to-ack /
 * time-to-resolve are measured from the incident's {@code created_at} (its trigger instant).
 *
 * <p>Counters surface in Prometheus as {@code incident_triggered_total} etc.; the timers as
 * {@code incident_time_to_ack_seconds} / {@code incident_time_to_resolve_seconds}.
 */
@Component
public class IncidentMetrics {

    private final MeterRegistry registry;
    private final IncidentRepository incidents;
    private final Counter triggered;
    private final Counter unrouted;
    private final Counter routedFromCache;
    private final Counter acknowledged;
    private final Counter resolved;
    private final Timer timeToAck;
    private final Timer timeToResolve;

    public IncidentMetrics(MeterRegistry registry, IncidentRepository incidents) {
        this.registry = registry;
        this.incidents = incidents;
        this.triggered = registry.counter("incident.triggered");
        this.unrouted = registry.counter("incident.unrouted");
        this.routedFromCache = registry.counter("incident.routed_from_cache");
        this.acknowledged = registry.counter("incident.acknowledged");
        this.resolved = registry.counter("incident.resolved");
        this.timeToAck = registry.timer("incident.time_to_ack");
        this.timeToResolve = registry.timer("incident.time_to_resolve");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTriggered(IncidentDomainEvents.Triggered e) {
        triggered.increment();
        // A genuine no-match with no org default (Phase 10 T3): "nobody paged" is now counted + alertable
        // (incident_unrouted_total), not invisible.
        if (e.unrouted()) {
            unrouted.increment();
        }
        // A catalog outage paged this incident from the last-known-good cache (Phase 10 T4): a degraded,
        // counted decision (incident_routed_from_cache_total), not a silent fallthrough.
        if (e.routedFromCache()) {
            routedFromCache.increment();
        }
        // Phase 17: which routing rule actually paged this incident. Tagged by rule id (bounded
        // cardinality — one per authored rule); a fallback/UNROUTED page has no matched rule.
        if (e.matchedRuleId() != null) {
            registry.counter("routing.rule_matched", "ruleId", e.matchedRuleId().toString()).increment();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAcknowledged(IncidentDomainEvents.Acknowledged e) {
        acknowledged.increment();
        incidents.findById(e.incidentId()).ifPresent(i ->
                timeToAck.record(Duration.between(i.getCreatedAt(), e.at())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(IncidentDomainEvents.Resolved e) {
        resolved.increment();
        incidents.findById(e.incidentId()).ifPresent(i ->
                timeToResolve.record(Duration.between(i.getCreatedAt(), e.at())));
    }
}
