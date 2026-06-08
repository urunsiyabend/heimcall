package com.urunsiyabend.heimcall.catalog.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {
    List<MonitoredService> findByOrganizationId(UUID organizationId);
    Optional<MonitoredService> findByIdAndOrganizationId(UUID id, UUID organizationId);
    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);
}
