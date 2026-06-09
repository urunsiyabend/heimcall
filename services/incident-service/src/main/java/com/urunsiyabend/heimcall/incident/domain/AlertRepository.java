package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** The single OPEN alert for a dedup key, if any (enforced unique by ux_alert_open_dedup). */
    Optional<Alert> findFirstByOrganizationIdAndDedupKeyAndStatus(
            UUID organizationId, String dedupKey, AlertStatus status);

    List<Alert> findByIncidentIdOrderByFirstSeenAtAsc(UUID incidentId);
}
