package com.urunsiyabend.heimcall.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactMethodRepository extends JpaRepository<ContactMethod, UUID> {

    List<ContactMethod> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<ContactMethod> findByOrganizationIdAndUserIdAndEnabledTrue(UUID organizationId, UUID userId);

    Optional<ContactMethod> findByIdAndOrganizationIdAndUserId(UUID id, UUID organizationId, UUID userId);
}
