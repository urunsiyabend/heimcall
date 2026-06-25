package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjection;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Repair pull for the routing ruleset projection (Phase 17 T2), mirroring the Phase 10 T4
 * reconciliation job. Catches what the snapshot stream alone can miss: a dropped/never-published
 * snapshot, a DB-restore gap, or a projection gone excessively stale. Re-pulls each stale projection
 * from catalog's ruleset API (off the hot path) and applies it under the same version gate, so a
 * stream/pull race is harmless. This — not catalog boot-publish — is the correctness mechanism.
 *
 * <p>Deliberately bounded: only projections older than the freshness window are re-pulled; new tenants
 * are handled by the hot-path cold-miss hydration, not here. If catalog is unavailable the cycle aborts
 * and retries next run (the projection keeps serving its last-known ruleset meanwhile).
 */
@Component
public class RulesetProjectionReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(RulesetProjectionReconciliationJob.class);

    private final RoutingRulesetProjectionRepository projections;
    private final RoutingProjectionStore store;
    private final CatalogClient catalog;
    private final Duration staleAfter;

    public RulesetProjectionReconciliationJob(RoutingRulesetProjectionRepository projections,
                                              RoutingProjectionStore store, CatalogClient catalog,
                                              @Value("${heimcall.routing.projection.max-age:PT10M}") Duration staleAfter) {
        this.projections = projections;
        this.store = store;
        this.catalog = catalog;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${heimcall.routing.projection.reconcile-interval-ms:300000}",
            initialDelayString = "${heimcall.routing.projection.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        Instant now = Instant.now();
        List<RoutingRulesetProjection> stale = projections.findByObservedAtBefore(now.minus(staleAfter));
        if (stale.isEmpty()) {
            return;
        }
        log.info("Reconciling {} stale routing projection(s)", stale.size());
        for (RoutingRulesetProjection p : stale) {
            try {
                RoutingRulesetSnapshotEvent snapshot = catalog.fetchRuleset(p.getOrganizationId());
                store.apply(snapshot, Instant.now());
            } catch (RoutingUnavailableException stillDown) {
                // Catalog has not recovered. Leave the rest; retry next cycle. Projections keep serving.
                log.warn("Catalog unavailable during ruleset reconciliation; aborting cycle: {}",
                        stillDown.getMessage());
                return;
            }
        }
    }
}
