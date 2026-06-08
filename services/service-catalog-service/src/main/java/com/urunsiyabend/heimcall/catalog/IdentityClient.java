package com.urunsiyabend.heimcall.catalog;

import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Enforces tenant rules that live in identity-service: org membership of the caller and ownership
 * teams belonging to the org. Membership / teams are not in this service's database, so they are
 * checked over the internal identity API.
 */
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${identity.base-url:http://localhost:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Throws 403 if the user is not a member of the org. */
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

    /** Throws 409 if the team does not exist in the org. */
    public void requireTeamInOrg(UUID organizationId, UUID teamId) {
        try {
            restClient.get()
                    .uri("/v1/internal/organizations/{org}/teams/{team}", organizationId, teamId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ApiExceptions.ConflictException("team does not belong to this organization");
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
