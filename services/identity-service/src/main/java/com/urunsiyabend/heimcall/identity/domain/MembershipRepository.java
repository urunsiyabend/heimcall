package com.urunsiyabend.heimcall.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByOrganizationId(UUID organizationId);
    List<Membership> findByUserId(UUID userId);
    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
