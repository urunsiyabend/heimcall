package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.AppUserRepository;
import com.urunsiyabend.heimcall.identity.domain.Membership;
import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.OrganizationRepository;
import com.urunsiyabend.heimcall.identity.domain.Role;
import jakarta.validation.Valid;
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
@RequestMapping("/v1/organizations/{orgId}/memberships")
public class MembershipController {

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final TenantGuard guard;

    public MembershipController(MembershipRepository memberships, OrganizationRepository organizations,
                                AppUserRepository users, TenantGuard guard) {
        this.memberships = memberships;
        this.organizations = organizations;
        this.users = users;
        this.guard = guard;
    }

    public record CreateRequest(@NotNull UUID userId, @NotNull Role role) {
    }

    public record MembershipResponse(UUID id, UUID organizationId, UUID userId, Role role, Instant createdAt) {
        static MembershipResponse of(Membership m) {
            return new MembershipResponse(m.getId(), m.getOrganizationId(), m.getUserId(), m.getRole(), m.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse add(@PathVariable UUID orgId,
                                  @RequestHeader(value = "X-User-Id", required = false) UUID callerId,
                                  @Valid @RequestBody CreateRequest req) {
        if (!organizations.existsById(orgId)) {
            throw new ApiExceptions.NotFoundException("organization not found: " + orgId);
        }
        // Bootstrap: the first membership of an org needs no existing member to authorize it.
        // Every subsequent add must come from an existing member of the org.
        boolean bootstrap = memberships.findByOrganizationId(orgId).isEmpty();
        if (!bootstrap) {
            if (callerId == null) {
                throw new ApiExceptions.ForbiddenException("X-User-Id required to modify organization");
            }
            guard.requireMember(orgId, callerId);
        }
        if (!users.existsById(req.userId())) {
            throw new ApiExceptions.NotFoundException("user not found: " + req.userId());
        }
        if (memberships.existsByOrganizationIdAndUserId(orgId, req.userId())) {
            throw new ApiExceptions.ConflictException("user already a member of this organization");
        }
        Membership saved = memberships.save(Membership.create(orgId, req.userId(), req.role(), Instant.now()));
        return MembershipResponse.of(saved);
    }

    @GetMapping
    public List<MembershipResponse> list(@PathVariable UUID orgId,
                                         @RequestHeader("X-User-Id") UUID callerId) {
        guard.requireMember(orgId, callerId);
        return memberships.findByOrganizationId(orgId).stream().map(MembershipResponse::of).toList();
    }
}
