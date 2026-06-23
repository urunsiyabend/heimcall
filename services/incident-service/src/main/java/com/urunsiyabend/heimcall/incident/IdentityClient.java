package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.security.ServiceTokenClients;
import com.urunsiyabend.heimcall.incident.web.ApiExceptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Enforces the tenant rule owned by identity-service over its internal API: the caller issuing a
 * lifecycle command must be a member of the incident's organization.
 */
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(RestClient.Builder builder, ServiceTokenClients serviceTokens,
                          @Value("${identity.base-url:http://localhost:8083}") String baseUrl) {
        // Boot's auto-configured builder carries the observation customizer, so this client emits a
        // client span + traceparent header and the callee joins the distributed trace (Phase 8 T4b).
        // Phase 16 T3: attach an identity-scoped service token to every call (registration "identity").
        this.restClient = serviceTokens.authorize(builder, "identity").baseUrl(baseUrl).build();
    }

    /** 403 if the caller is not a member of the org. */
    public void requireMember(UUID organizationId, UUID userId) {
        try {
            restClient.get()
                    .uri("/v1/internal/organizations/{org}/members/{user}", organizationId, userId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ApiExceptions.ForbiddenException("user is not a member of this organization");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ApiExceptions.DependencyUnavailableException("identity-service error");
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new ApiExceptions.DependencyUnavailableException("identity-service unreachable");
        }
    }
}
