package com.urunsiyabend.heimcall.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Phase 16 T4: lock the invariant that the gateway NEVER exposes any service's internal surface.
 * <p>
 * No route maps {@code /v1/internal/**} (or {@code /oauth2/token}), so the gateway must 404 those paths
 * (never proxy them to a backend). Service tokens / internal endpoints are reachable only pod-to-pod inside
 * the mesh — the gateway is the public edge and stays blind to them. A real product route
 * ({@code /v1/incidents/**}) is the positive control: it IS routed, so with its upstream down the gateway
 * returns a 5xx — proving the 404s below mean "no such route", not a generic gateway failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Upstreams point at closed ports on purpose: a routed request fails fast with 5xx
        // (connection refused), while an unrouted request 404s before any upstream is attempted.
        "CATALOG_URI=http://localhost:59001",
        "SCHEDULE_URI=http://localhost:59002",
        "ESCALATION_URI=http://localhost:59003",
        "NOTIFICATION_URI=http://localhost:59004",
        "IDENTITY_URI=http://localhost:59005",
        "INTEGRATION_URI=http://localhost:59006",
        "INCIDENT_URI=http://localhost:59007"
})
class InternalRouteIsolationTest {

    @Autowired
    WebTestClient client;

    @Test
    void internalEndpointsAreNotRoutedThroughTheGateway() {
        for (String path : new String[]{
                "/v1/internal/members/1",
                "/v1/internal/teams/1",
                "/v1/internal/teams/1/members",
                "/v1/internal",
                "/oauth2/token"}) {
            client.get().uri(path).exchange()
                    .expectStatus().isNotFound();
        }
    }

    @Test
    void aRealProductRouteIsActuallyRouted() {
        // /v1/incidents/** matches the incident route; upstream is down -> 5xx (routed), not 404 (unrouted).
        client.get().uri("/v1/incidents/123").exchange()
                .expectStatus().is5xxServerError();
    }
}
