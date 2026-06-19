package com.urunsiyabend.heimcall.escalation.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationTaskRepository extends JpaRepository<EscalationTask, UUID> {
    List<EscalationTask> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(TaskStatus status, Instant cutoff);
    List<EscalationTask> findByIncidentIdAndStatus(UUID incidentId, TaskStatus status);
    boolean existsByIncidentId(UUID incidentId);

    /**
     * Claim a task for firing: lock the row only if it is still PENDING, skipping it if another worker
     * (a concurrent poll, or another replica) already holds the lock. {@code FOR UPDATE SKIP LOCKED}
     * makes the poll-then-fire path lock-safe across replicas (plan §3.2) — the winner holds the row
     * lock until its transaction commits the EXECUTED mark, so a loser sees either a locked row (empty)
     * or a no-longer-PENDING row (empty) and skips. Prevents double-firing the same task (a duplicate
     * notification.requested → double page). Mirrors the common-outbox relay's claim pattern.
     */
    @Query(value = "SELECT * FROM escalation_task WHERE id = :id AND status = 'PENDING' "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<EscalationTask> findPendingForUpdate(@Param("id") UUID id);
}
