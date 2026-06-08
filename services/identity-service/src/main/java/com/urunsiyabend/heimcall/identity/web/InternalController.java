package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.Team;
import com.urunsiyabend.heimcall.identity.domain.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service lookups for other services to enforce tenant rules they cannot check locally
 * (membership + team ownership live only in identity-service). Not for external callers.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final MembershipRepository memberships;
    private final TeamRepository teams;

    public InternalController(MembershipRepository memberships, TeamRepository teams) {
        this.memberships = memberships;
        this.teams = teams;
    }

    public record TeamResponse(UUID id, UUID organizationId, String name) {
        static TeamResponse of(Team t) {
            return new TeamResponse(t.getId(), t.getOrganizationId(), t.getName());
        }
    }

    /** 204 if the user is a member of the org, 404 otherwise. */
    @GetMapping("/organizations/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        if (!memberships.existsByOrganizationIdAndUserId(orgId, userId)) {
            throw new ApiExceptions.NotFoundException("not a member");
        }
    }

    /** Returns the team if it belongs to the org, 404 otherwise. */
    @GetMapping("/organizations/{orgId}/teams/{teamId}")
    public TeamResponse getTeam(@PathVariable UUID orgId, @PathVariable UUID teamId) {
        return teams.findByIdAndOrganizationId(teamId, orgId)
                .map(TeamResponse::of)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("team not found in organization"));
    }
}
