package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.common.security.ServiceTokenClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Resolves an alert to its owning service + escalation policy via service-catalog's routing rule engine
 * (Phase 17). incident-service POSTs the full {@link RoutingContext} (built from the normalized
 * {@code AlertReceivedEvent}); the engine returns exactly one {@link RoutingResult}. The ruleset is
 * total, so UNROUTED comes back <b>in the body</b> ({@code unrouted=true}), not as a 404 — a genuine
 * routing decision, not a failure.
 *
 * <p>Any infra failure (catalog unreachable, 5xx, timeout) is NOT a routing decision: it throws
 * {@link RoutingUnavailableException} rather than silently de-paging, so the listener retries +
 * dead-letters and the {@code @Transactional} handler rolls back (no orphan). Phase 17 T1 fails safe to
 * the DLT on a catalog outage (no last-known-good cache for rule decisions — that is unsafe once routing
 * depends on more than the key); T2 removes the outage exposure by replicating the ruleset locally.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder, ServiceTokenClients serviceTokens,
                         @Value("${catalog.base-url:http://localhost:8084}") String baseUrl,
                         @Value("${catalog.connect-timeout-ms:2000}") long connectTimeoutMs,
                         @Value("${catalog.read-timeout-ms:3000}") long readTimeoutMs) {
        // Bounded timeouts so a catalog outage fails FAST -> RoutingUnavailableException -> retry/DLT,
        // instead of the consumer thread hanging and stalling the partition. Boot's builder carries the
        // observation customizer (client span + traceparent, Phase 8 T4b).
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        // Phase 16 T3: attach a catalog-scoped service token to every call (registration "catalog").
        this.restClient = serviceTokens.authorize(builder, "catalog")
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Pull the full current ruleset snapshot for an org (Phase 17 T2) — used to lazily hydrate or
     * reconcile the local read-model, NOT on the steady-state hot path (that evaluates locally).
     *
     * @throws RoutingUnavailableException catalog is unreachable; the caller falls back to UNROUTED
     *                                     (cold miss) or simply retries next cycle (reconciliation).
     */
    public RoutingRulesetSnapshotEvent fetchRuleset(UUID organizationId) {
        try {
            RoutingRulesetSnapshotEvent snapshot = restClient.get()
                    .uri("/v1/internal/organizations/{org}/routing/ruleset", organizationId)
                    .retrieve()
                    .body(RoutingRulesetSnapshotEvent.class);
            if (snapshot == null) {
                throw new RoutingUnavailableException(null, new IllegalStateException("empty ruleset snapshot"));
            }
            return snapshot;
        } catch (RuntimeException e) {
            if (e instanceof RoutingUnavailableException rue) {
                throw rue;
            }
            log.warn("Ruleset pull UNAVAILABLE for org={}: {}", organizationId, e.getMessage());
            throw new RoutingUnavailableException(null, e);
        }
    }
}
