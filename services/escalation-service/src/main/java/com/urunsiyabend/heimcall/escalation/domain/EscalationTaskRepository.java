package com.urunsiyabend.heimcall.escalation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EscalationTaskRepository extends JpaRepository<EscalationTask, UUID> {
    List<EscalationTask> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(TaskStatus status, Instant cutoff);
    List<EscalationTask> findByIncidentIdAndStatus(UUID incidentId, TaskStatus status);
    boolean existsByIncidentId(UUID incidentId);
}
