package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.security.ServiceTokenClients;
import com.urunsiyabend.heimcall.notification.web.ApiExceptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Enforces tenant rules owned by identity-service over its internal API: the caller must be a member
 * of the org, and a user a contact method is registered for must belong to the org.
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
        check(organizationId, userId, new ApiExceptions.ForbiddenException("user is not a member of this organization"));
    }

    /** 409 if the user a contact method targets is not a member of the org. */
    public void requireOrgUser(UUID organizationId, UUID userId) {
        check(organizationId, userId, new ApiExceptions.ConflictException("user is not a member of this organization"));
    }

    private void check(UUID organizationId, UUID userId, RuntimeException on4xx) {
        try {
            restClient.get()
                    .uri("/v1/internal/organizations/{org}/members/{user}", organizationId, userId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw on4xx;
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
