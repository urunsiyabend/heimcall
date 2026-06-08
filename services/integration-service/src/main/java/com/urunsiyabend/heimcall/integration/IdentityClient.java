package com.urunsiyabend.heimcall.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves an inbound integration key against identity-service. Replaces the Sprint 1 dev
 * placeholder: the real organization + integration id now come from the issued key.
 */
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${identity.base-url:http://localhost:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record Resolution(UUID organizationId, UUID integrationId, String name) {
    }

    public Resolution resolve(String integrationKey) {
        try {
            return restClient.post()
                    .uri("/v1/integration-keys/resolve")
                    .body(Map.of("key", integrationKey))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new InvalidIntegrationKeyException("integration key rejected by identity-service");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new KeyResolutionUnavailableException("identity-service returned " + res.getStatusCode());
                    })
                    .body(Resolution.class);
        } catch (ResourceAccessException e) {
            // Connection refused / timeout: identity-service is down, cannot validate the key.
            throw new KeyResolutionUnavailableException("identity-service unreachable");
        }
    }
}
