package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findFirstByOrganizationIdAndDedupKeyAndStatusIn(
            UUID organizationId, String dedupKey, List<IncidentStatus> statuses);

    List<Incident> findByStatusOrderByCreatedAtDesc(IncidentStatus status);

    List<Incident> findAllByOrderByCreatedAtDesc();
}
