package com.urunsiyabend.heimcall.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an alert routingKey to its owning service + escalation policy via service-catalog.
 *
 * <p>Phase 10: a definitive no-match (catalog 404 — no service carries this routingKey) returns
 * {@link Optional#empty()} so the catch-all / UNROUTED path can handle it deliberately. Any OTHER
 * failure (catalog unreachable, 5xx, timeout) is an infra problem, not a routing decision, and throws
 * {@link RoutingUnavailableException} rather than silently de-paging the incident — the listener then
 * retries + dead-letters the event, and the {@code @Transactional} handler rolls back (no orphan).
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${catalog.base-url:http://localhost:8084}") String baseUrl,
                         @Value("${catalog.connect-timeout-ms:2000}") long connectTimeoutMs,
                         @Value("${catalog.read-timeout-ms:3000}") long readTimeoutMs) {
        // Bounded timeouts so a catalog outage fails FAST -> RoutingUnavailableException -> retry/DLT,
        // instead of the consumer thread hanging on the resolve call and stalling the partition (an
        // endpoint-less ClusterIP does not RST, so without a connect timeout the call blocks for the OS
        // default). Boot's auto-configured builder still carries the observation customizer, so this
        // client emits a client span + traceparent and the callee joins the distributed trace (Phase 8 T4b).
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .baseUrl(baseUrl)
                .build();
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
        } catch (HttpClientErrorException.NotFound e) {
            // Definitive no-match: no service in the org carries this routingKey. A genuine routing
            // decision, not a failure — the catch-all / UNROUTED path takes over.
            log.debug("No routing mapping for routingKey={} org={}", routingKey, organizationId);
            return Optional.empty();
        } catch (RuntimeException e) {
            // Catalog unreachable / 5xx / timeout / any non-404 error. NOT a routing decision: do not
            // de-page. Surface it so the event is retried + dead-lettered and no orphan incident is left.
            log.warn("Routing resolution UNAVAILABLE for routingKey={} org={}: {}",
                    routingKey, organizationId, e.getMessage());
            throw new RoutingUnavailableException(routingKey, e);
        }
    }
}
