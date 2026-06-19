package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import com.urunsiyabend.heimcall.incident.domain.RoutingCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves routing with a last-known-good availability fallback (Phase 10 T4). Wraps the low-level
 * {@link CatalogClient} (live HTTP, 404-vs-outage) and adds the {@link RoutingCacheStore} layer so a
 * catalog OUTAGE no longer dead-letters a real incident — it pages from the cached route instead.
 *
 * <p>The fallback deliberately lives here (incident-service), not hidden inside {@code CatalogClient.resolve},
 * so the client stays a dumb live-resolve and the availability policy is explicit. Called inside
 * {@code IncidentService.handle}'s transaction, so the write-through / tombstone JDBC writes commit
 * atomically with the incident change.
 */
@Component
public class RoutingAvailabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingAvailabilityResolver.class);

    private final CatalogClient catalog;
    private final RoutingCacheStore cache;

    public RoutingAvailabilityResolver(CatalogClient catalog, RoutingCacheStore cache) {
        this.catalog = catalog;
        this.cache = cache;
    }

    /**
     * @throws RoutingUnavailableException catalog is unavailable AND there is no cached fallback for this
     *                                     key — the genuine unknown; let it propagate to retry/DLT.
     */
    public RoutingDecision resolve(UUID organizationId, String routingKey) {
        Optional<Routing> live;
        try {
            live = catalog.resolve(organizationId, routingKey);
        } catch (RoutingUnavailableException outage) {
            return fallBackToCache(organizationId, routingKey, outage);
        }

        if (live.isPresent()) {
            // Catalog 200: write-through so this stays the last-known-good for a future outage.
            cache.put(organizationId, routingKey, live.get(), Instant.now());
            return RoutingDecision.routed(live.get());
        }
        // Catalog 404: a definitive no-match. Tombstone any positive row so a dead route can never page
        // from cache on a later outage, then take the deliberate UNROUTED path (Phase 10 T3).
        cache.evict(organizationId, routingKey);
        return RoutingDecision.noMatch();
    }

    private RoutingDecision fallBackToCache(UUID organizationId, String routingKey,
                                            RoutingUnavailableException outage) {
        Optional<Routing> cached = (routingKey == null || routingKey.isBlank())
                ? Optional.empty()
                : cache.find(organizationId, routingKey);
        if (cached.isPresent()) {
            log.warn("Catalog unavailable for routingKey={} org={}; paging from last-known-good cache (policy={})",
                    routingKey, organizationId, cached.get().escalationPolicyId());
            return RoutingDecision.fromCache(cached.get());
        }
        // No cached fallback: the genuine unknown. Re-throw so the event is retried + dead-lettered
        // (no orphan incident), rather than paging or silently dropping it.
        log.warn("Catalog unavailable for routingKey={} org={} and no cached route; deferring to retry/DLT",
                routingKey, organizationId);
        throw outage;
    }
}
