package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findByStatusOrderByCreatedAtDesc(IncidentStatus status);

    List<Incident> findAllByOrderByCreatedAtDesc();

    List<Incident> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<Incident> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID organizationId, IncidentStatus status);
}
