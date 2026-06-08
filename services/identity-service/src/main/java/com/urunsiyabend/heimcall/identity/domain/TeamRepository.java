package com.urunsiyabend.heimcall.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    List<Team> findByOrganizationId(UUID organizationId);
    Optional<Team> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
