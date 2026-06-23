package com.urunsiyabend.heimcall.catalog;

import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import com.urunsiyabend.heimcall.common.security.ServiceTokenClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Validates that an escalation policy referenced by a service actually exists in escalation-service
 * and belongs to the org. Policies live in another service's database, so this is a sync internal call.
 */
@Component
public class EscalationClient {

    private final RestClient restClient;

    public EscalationClient(RestClient.Builder builder, ServiceTokenClients serviceTokens,
                            @Value("${escalation.base-url:http://localhost:8086}") String baseUrl) {
        // Boot's auto-configured builder carries the observation customizer, so this client emits a
        // client span + traceparent header and the callee joins the distributed trace (Phase 8 T4b).
        // Phase 16 T3: attach an escalation-scoped service token to every call (registration "escalation").
        this.restClient = serviceTokens.authorize(builder, "escalation").baseUrl(baseUrl).build();
    }

    /** Throws 409 if the policy does not exist in the org. */
    public void requirePolicyInOrg(UUID organizationId, UUID policyId) {
        try {
            restClient.get()
                    .uri("/v1/internal/organizations/{org}/escalation-policies/{policy}", organizationId, policyId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ApiExceptions.ConflictException("escalation policy does not belong to this organization");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ApiExceptions.DependencyUnavailableException("escalation-service error");
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new ApiExceptions.DependencyUnavailableException("escalation-service unreachable");
        }
    }
}
