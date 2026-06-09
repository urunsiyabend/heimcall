package com.urunsiyabend.heimcall.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            DeliveryStatus status, Instant deadline);

    List<NotificationDelivery> findByOrganizationIdAndIncidentIdOrderByCreatedAtAsc(
            UUID organizationId, UUID incidentId);

    List<NotificationDelivery> findByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, DeliveryStatus status);

    List<NotificationDelivery> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
}
