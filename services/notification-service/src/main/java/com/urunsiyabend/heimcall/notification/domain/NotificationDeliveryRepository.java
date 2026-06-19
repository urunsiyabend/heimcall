package com.urunsiyabend.heimcall.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            DeliveryStatus status, Instant deadline);

    /**
     * Claim a delivery for sending: lock the row only if still PENDING, skipping it if another worker
     * already holds the lock. {@code FOR UPDATE SKIP LOCKED} makes the poll-then-send path lock-safe
     * across replicas (plan §3.2) — the winner holds the lock until its transaction commits the
     * DELIVERED/FAILED/retry mark, so a loser sees a locked or no-longer-PENDING row and skips. Prevents
     * sending the same notification twice (duplicate email/webhook). Mirrors the common-outbox relay.
     */
    @Query(value = "SELECT * FROM notification_delivery WHERE id = :id AND status = 'PENDING' "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<NotificationDelivery> findPendingForUpdate(@Param("id") UUID id);

    List<NotificationDelivery> findByOrganizationIdAndIncidentIdOrderByCreatedAtAsc(
            UUID organizationId, UUID incidentId);

    List<NotificationDelivery> findByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, DeliveryStatus status);

    List<NotificationDelivery> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
}
