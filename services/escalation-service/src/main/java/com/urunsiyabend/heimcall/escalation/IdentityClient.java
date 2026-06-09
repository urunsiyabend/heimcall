package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.escalation.web.ApiExceptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Enforces tenant rules owned by identity-service (caller org membership, user/team membership of
 * rule targets) and resolves TEAM targets to member user ids. Checked over the internal identity API.
 */
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${identity.base-url:http://localhost:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 403 if the caller is not a member of the org. */
    public void requireMember(UUID organizationId, UUID userId) {
        check(organizationId, userId, new ApiExceptions.ForbiddenException("user is not a member of this organization"));
    }

    /** 409 if a user referenced by a rule target is not a member of the org. */
    public void requireOrgUser(UUID organizationId, UUID userId) {
        check(organizationId, userId, new ApiExceptions.ConflictException("user is not a member of this organization"));
    }

    /** 409 if a team referenced by a rule target does not belong to the org. */
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

    /** Member user ids of a team. Used to fan out a TEAM target at fire time. 503 if identity is down. */
    public List<UUID> teamMemberIds(UUID organizationId, UUID teamId) {
        try {
            UUID[] ids = restClient.get()
                    .uri("/v1/internal/organizations/{org}/teams/{team}/members", organizationId, teamId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ApiExceptions.DependencyUnavailableException("identity-service error");
                    })
                    .body(UUID[].class);
            return ids == null ? List.of() : List.of(ids);
        } catch (ResourceAccessException e) {
            throw new ApiExceptions.DependencyUnavailableException("identity-service unreachable");
        }
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
