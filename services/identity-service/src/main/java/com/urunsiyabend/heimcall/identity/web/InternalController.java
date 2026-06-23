package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.Team;
import com.urunsiyabend.heimcall.identity.domain.TeamMember;
import com.urunsiyabend.heimcall.identity.domain.TeamMemberRepository;
import com.urunsiyabend.heimcall.identity.domain.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Service-to-service lookups for other services to enforce tenant rules they cannot check locally
 * (membership + team ownership live only in identity-service). Not for external callers.
 *
 * <p>Phase 16 T3: each endpoint requires a service token addressed to {@code identity} ({@code aud}) carrying
 * the specific scope below — enforced by {@code @PreAuthorize} against the {@code SCOPE_*} authorities the
 * auth filter derives from the token's {@code scope} claim. A user token (no {@code SCOPE_*}) is rejected.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final MembershipRepository memberships;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;

    public InternalController(MembershipRepository memberships, TeamRepository teams,
                              TeamMemberRepository teamMembers) {
        this.memberships = memberships;
        this.teams = teams;
        this.teamMembers = teamMembers;
    }

    public record TeamResponse(UUID id, UUID organizationId, String name) {
        static TeamResponse of(Team t) {
            return new TeamResponse(t.getId(), t.getOrganizationId(), t.getName());
        }
    }

    /** 204 if the user is a member of the org, 404 otherwise. */
    @GetMapping("/organizations/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_identity.membership.read')")
    public void checkMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        if (!memberships.existsByOrganizationIdAndUserId(orgId, userId)) {
            throw new ApiExceptions.NotFoundException("not a member");
        }
    }

    /** Returns the team if it belongs to the org, 404 otherwise. */
    @GetMapping("/organizations/{orgId}/teams/{teamId}")
    @PreAuthorize("hasAuthority('SCOPE_identity.team.read')")
    public TeamResponse getTeam(@PathVariable UUID orgId, @PathVariable UUID teamId) {
        return teams.findByIdAndOrganizationId(teamId, orgId)
                .map(TeamResponse::of)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("team not found in organization"));
    }

    /** Member userIds of a team. 404 if the team does not belong to the org. Used by escalation TEAM-target fan-out. */
    @GetMapping("/organizations/{orgId}/teams/{teamId}/members")
    @PreAuthorize("hasAuthority('SCOPE_identity.team-members.read')")
    public List<UUID> teamMemberIds(@PathVariable UUID orgId, @PathVariable UUID teamId) {
        teams.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("team not found in organization"));
        return teamMembers.findByTeamId(teamId).stream().map(TeamMember::getUserId).toList();
    }
}
