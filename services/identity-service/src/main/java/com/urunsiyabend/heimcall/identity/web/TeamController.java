package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.AppUserRepository;
import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.Team;
import com.urunsiyabend.heimcall.identity.domain.TeamMember;
import com.urunsiyabend.heimcall.identity.domain.TeamMemberRepository;
import com.urunsiyabend.heimcall.identity.domain.TeamRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/teams")
public class TeamController {

    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final TenantGuard guard;

    public TeamController(TeamRepository teams, TeamMemberRepository teamMembers,
                          MembershipRepository memberships, AppUserRepository users, TenantGuard guard) {
        this.teams = teams;
        this.teamMembers = teamMembers;
        this.memberships = memberships;
        this.users = users;
        this.guard = guard;
    }

    public record CreateTeamRequest(@NotBlank String name) {
    }

    public record TeamResponse(UUID id, UUID organizationId, String name, Instant createdAt) {
        static TeamResponse of(Team t) {
            return new TeamResponse(t.getId(), t.getOrganizationId(), t.getName(), t.getCreatedAt());
        }
    }

    public record AddMemberRequest(@NotNull UUID userId) {
    }

    public record TeamMemberResponse(UUID id, UUID teamId, UUID userId, Instant createdAt) {
        static TeamMemberResponse of(TeamMember tm) {
            return new TeamMemberResponse(tm.getId(), tm.getTeamId(), tm.getUserId(), tm.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@PathVariable UUID orgId,
                               @RequestHeader("X-User-Id") UUID callerId,
                               @Valid @RequestBody CreateTeamRequest req) {
        guard.requireMember(orgId, callerId);
        Team saved = teams.save(Team.create(orgId, req.name(), Instant.now()));
        return TeamResponse.of(saved);
    }

    @GetMapping
    public List<TeamResponse> list(@PathVariable UUID orgId,
                                   @RequestHeader("X-User-Id") UUID callerId) {
        guard.requireMember(orgId, callerId);
        return teams.findByOrganizationId(orgId).stream().map(TeamResponse::of).toList();
    }

    @PostMapping("/{teamId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamMemberResponse addMember(@PathVariable UUID orgId, @PathVariable UUID teamId,
                                        @RequestHeader("X-User-Id") UUID callerId,
                                        @Valid @RequestBody AddMemberRequest req) {
        guard.requireMember(orgId, callerId);
        Team team = teams.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("team not found in organization: " + teamId));
        // A team member must belong to the team's organization.
        if (!memberships.existsByOrganizationIdAndUserId(orgId, req.userId())) {
            throw new ApiExceptions.ConflictException("user is not a member of this organization");
        }
        if (teamMembers.existsByTeamIdAndUserId(team.getId(), req.userId())) {
            throw new ApiExceptions.ConflictException("user already in team");
        }
        TeamMember saved = teamMembers.save(TeamMember.create(team.getId(), req.userId(), Instant.now()));
        return TeamMemberResponse.of(saved);
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberResponse> listMembers(@PathVariable UUID orgId, @PathVariable UUID teamId,
                                                @RequestHeader("X-User-Id") UUID callerId) {
        guard.requireMember(orgId, callerId);
        teams.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("team not found in organization: " + teamId));
        return teamMembers.findByTeamId(teamId).stream().map(TeamMemberResponse::of).toList();
    }
}
