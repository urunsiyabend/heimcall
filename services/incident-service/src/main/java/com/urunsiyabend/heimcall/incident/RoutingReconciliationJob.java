package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.ReconcileResult;
import com.urunsiyabend.heimcall.incident.domain.RoutingCacheStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Audit-only reconciliation of {@code routed_from_cache} incidents (Phase 10 T4). After a catalog
 * outage, incidents that were paged from the last-known-good cache are re-resolved against catalog once
 * it recovers, and the outcome (catalog NOW matches / drifts / no-match) is recorded on the incident.
 *
 * <p>Deliberately <b>scoped to degraded incidents</b>, NOT a full {@code routing_cache} sweep: it scans
 * only incidents with {@code routed_from_cache=true AND reconciled_at IS NULL}, groups by distinct
 * {@code (org, routingKey)} so catalog load is one resolve per degraded key (not per cache row), and is
 * capped per cycle. If catalog is still unavailable the cycle aborts (rows stay unreconciled → retried
 * next run) — paced by the schedule + the per-cycle cap, backed off by abort-on-outage.
 *
 * <p>It never re-pages and never mutates an incident's route; the {@code CURRENT_*} result states how
 * catalog answers <i>now</i>, not that the cached route was wrong at outage time.
 */
@Component
public class RoutingReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(RoutingReconciliationJob.class);

    private final IncidentRepository incidents;
    private final CatalogClient catalog;
    private final RoutingCacheStore cache;
    private final Counter drift;
    private final int batchSize;

    public RoutingReconciliationJob(IncidentRepository incidents, CatalogClient catalog,
                                    RoutingCacheStore cache, MeterRegistry registry,
                                    @Value("${heimcall.routing.reconcile.batch-size:200}") int batchSize) {
        this.incidents = incidents;
        this.catalog = catalog;
        this.cache = cache;
        this.drift = registry.counter("routing.cache_drift");
        this.batchSize = batchSize;
    }

    private record Key(UUID organizationId, String routingKey) {
    }

    @Scheduled(fixedDelayString = "${heimcall.routing.reconcile.interval-ms:900000}")
    public void reconcile() {
        List<Incident> pending = incidents.findByRoutedFromCacheTrueAndReconciledAtIsNullOrderByCreatedAtAsc(
                PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }

        // One catalog resolve per distinct degraded (org, routingKey); all incidents on that key share the result.
        Map<Key, List<Incident>> groups = new LinkedHashMap<>();
        for (Incident incident : pending) {
            groups.computeIfAbsent(new Key(incident.getOrganizationId(), incident.getRoutingKey()),
                    k -> new java.util.ArrayList<>()).add(incident);
        }

        log.info("Reconciling {} cache-routed incident(s) across {} distinct routingKey(s)",
                pending.size(), groups.size());

        for (Map.Entry<Key, List<Incident>> group : groups.entrySet()) {
            Key key = group.getKey();
            Optional<Routing> live;
            try {
                live = catalog.resolveByKey(key.organizationId(), key.routingKey());
            } catch (RoutingUnavailableException stillDown) {
                // Catalog has not recovered. Leave the rest unreconciled and retry next cycle (backoff).
                log.warn("Catalog still unavailable during reconciliation; aborting cycle: {}", stillDown.getMessage());
                return;
            }
            Instant now = Instant.now();
            if (live.isPresent()) {
                cache.put(key.organizationId(), key.routingKey(), live.get(), now); // refresh last-known-good
                resolveGroup(group.getValue(), live.get().escalationPolicyId(), now);
            } else {
                // Catalog now returns a definitive no-match: tombstone the (stale) positive cache row.
                cache.evict(key.organizationId(), key.routingKey());
                markGroup(group.getValue(), ReconcileResult.CURRENT_NOT_FOUND, now);
            }
        }
    }

    private void resolveGroup(List<Incident> group, UUID livePolicy, Instant now) {
        for (Incident incident : group) {
            boolean match = Objects.equals(livePolicy, incident.getEscalationPolicyId());
            ReconcileResult result = match ? ReconcileResult.CURRENT_MATCH : ReconcileResult.CURRENT_DRIFT;
            if (!match) {
                drift.increment();
                log.warn("Routing drift on incident {}: cached policy {} != catalog policy {} (routingKey={})",
                        incident.getId(), incident.getEscalationPolicyId(), livePolicy, incident.getRoutingKey());
            }
            incident.reconcile(result, now);
            incidents.save(incident);
        }
    }

    private void markGroup(List<Incident> group, ReconcileResult result, Instant now) {
        for (Incident incident : group) {
            incident.reconcile(result, now);
            incidents.save(incident);
        }
    }
}
