package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tenant-isolation gate (Phase 1a: header-context stub). Org-scoped endpoints pass the caller's
 * {@code X-User-Id} and the target {@code X-Org-Id}; only an existing member may proceed. Real
 * authentication (JWT) replaces the trusted-header assumption in a later phase.
 */
@Component
public class TenantGuard {

    private final MembershipRepository memberships;

    public TenantGuard(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public void requireMember(UUID organizationId, UUID userId) {
        if (!memberships.existsByOrganizationIdAndUserId(organizationId, userId)) {
            throw new ApiExceptions.ForbiddenException("user is not a member of this organization");
        }
    }
}
