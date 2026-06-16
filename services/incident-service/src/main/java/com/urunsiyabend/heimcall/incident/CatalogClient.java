package com.urunsiyabend.heimcall.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an alert routingKey to its owning service + escalation policy via service-catalog.
 *
 * <p>Best-effort by design: routing is an enrichment, not a precondition. A missing mapping (404),
 * a catalog error, or an unreachable catalog all resolve to {@link Optional#empty()} so the incident
 * is still created — it simply carries no escalation policy and no escalation fires.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${catalog.base-url:http://localhost:8084}") String baseUrl) {
        // Boot's auto-configured builder carries the observation customizer, so this client emits a
        // client span + traceparent header and the callee joins the distributed trace (Phase 8 T4b).
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public record Routing(UUID serviceId, UUID escalationPolicyId, UUID ownerTeamId) {
    }

    public Optional<Routing> resolve(UUID organizationId, String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            return Optional.empty();
        }
        try {
            Routing routing = restClient.get()
                    .uri(uri -> uri.path("/v1/internal/organizations/{org}/routing")
                            .queryParam("routingKey", routingKey).build(organizationId))
                    .retrieve()
                    .body(Routing.class);
            return Optional.ofNullable(routing);
        } catch (RuntimeException e) {
            log.warn("Routing resolution skipped for routingKey={} org={}: {}",
                    routingKey, organizationId, e.getMessage());
            return Optional.empty();
        }
    }
}
