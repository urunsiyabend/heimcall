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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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

    /** Matchable projection of the alert sent to the engine. Field names mirror catalog's RoutingContext. */
    public record RoutingContext(String routingKey, String source, String messageType, String severity,
                                 String externalEntityId, String title, String description,
                                 Map<String, String> metadata, Instant evaluatedAt) {
    }

    /** The engine's decision. Mirrors catalog's RoutingDecision (the per-rule trace is preview-only and
     *  not requested here, so it is ignored on the wire). */
    public record RoutingResult(UUID serviceId, UUID escalationPolicyId, UUID matchedRuleId,
                                long rulesetVersion, boolean unrouted) {
    }

    /** Legacy single-field routing shape, retained only for the dormant Phase 10 T4 cache /
     *  reconciliation path (superseded in Phase 17 T2). */
    public record Routing(UUID serviceId, UUID escalationPolicyId, UUID ownerTeamId) {
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

    public RoutingResult resolve(UUID organizationId, RoutingContext context) {
        try {
            RoutingResult result = restClient.post()
                    .uri("/v1/internal/organizations/{org}/routing/resolve", organizationId)
                    .body(context)
                    .retrieve()
                    .body(RoutingResult.class);
            if (result == null) {
                throw new RoutingUnavailableException(context.routingKey(),
                        new IllegalStateException("empty routing decision"));
            }
            return result;
        } catch (RuntimeException e) {
            if (e instanceof RoutingUnavailableException rue) {
                throw rue;
            }
            // Catalog unreachable / 5xx / timeout. NOT a routing decision: do not de-page. Surface it so
            // the event is retried + dead-lettered and no orphan incident is left.
            log.warn("Routing resolution UNAVAILABLE for routingKey={} org={}: {}",
                    context.routingKey(), organizationId, e.getMessage());
            throw new RoutingUnavailableException(context.routingKey(), e);
        }
    }

    /** Key-only resolve for the dormant reconciliation job (Phase 10 T4). Maps an UNROUTED decision to
     *  empty. Other fields are unknown from a bare key, so this is best-effort audit input only. */
    public Optional<Routing> resolveByKey(UUID organizationId, String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            return Optional.empty();
        }
        RoutingContext ctx = new RoutingContext(routingKey, null, null, null, null, null, null,
                Map.of(), Instant.now());
        RoutingResult result = resolve(organizationId, ctx);
        if (result.unrouted()) {
            return Optional.empty();
        }
        return Optional.of(new Routing(result.serviceId(), result.escalationPolicyId(), null));
    }
}
