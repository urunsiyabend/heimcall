package com.urunsiyabend.heimcall.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationKeyRepository extends JpaRepository<IntegrationKey, UUID> {
    List<IntegrationKey> findByOrganizationId(UUID organizationId);
    Optional<IntegrationKey> findByKeyHashAndActiveTrue(String keyHash);
}
