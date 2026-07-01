package com.urunsiyabend.heimcall.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    /**
     * Phase 20 T1: claim up to {@code limit} due rows for sending. A row is claimable when it is a due
     * PENDING row, or a due SENDING row whose lease has expired (the sender that held it crashed). The
     * caller flips each to SENDING + lease in the same transaction; {@code FOR UPDATE SKIP LOCKED} makes
     * this lock-safe across replicas (plan §3.2) and lets concurrent claimers grab disjoint rows. The lock
     * is held only across the brief claim — the actual send runs outside the transaction. Expired-lease
     * reclaim is what preserves at-least-once (a crashed send is retried, never lost).
     */
    @Query(value = "SELECT * FROM notification_delivery "
            + "WHERE next_attempt_at <= :now "
            + "AND (status = 'PENDING' OR (status = 'SENDING' AND lease_expires_at < :now)) "
            + "ORDER BY next_attempt_at "
            + "FOR UPDATE SKIP LOCKED LIMIT :limit", nativeQuery = true)
    List<NotificationDelivery> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Lock a single row by id (plain {@code FOR UPDATE}) to record a send result. The caller verifies the
     * fencing {@code lease_token} still matches before applying — if a reaper handed the row to another
     * worker (lease expired) the token differs and the result is abandoned.
     */
    @Query(value = "SELECT * FROM notification_delivery WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<NotificationDelivery> findByIdForUpdate(@Param("id") UUID id);

    List<NotificationDelivery> findByOrganizationIdAndIncidentIdOrderByCreatedAtAsc(
            UUID organizationId, UUID incidentId);

    List<NotificationDelivery> findByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, DeliveryStatus status);

    List<NotificationDelivery> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
}
