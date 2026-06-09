package com.urunsiyabend.heimcall.notification.web;

import com.urunsiyabend.heimcall.notification.IdentityClient;
import com.urunsiyabend.heimcall.notification.domain.DeliveryStatus;
import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operational visibility into delivery state (success / failure), so failed messages are visible.
 * Org-scoped and member-gated; filterable by incident and status.
 */
@RestController
@RequestMapping("/v1/organizations/{orgId}/deliveries")
public class DeliveryController {

    private final NotificationDeliveryRepository deliveries;
    private final IdentityClient identity;

    public DeliveryController(NotificationDeliveryRepository deliveries, IdentityClient identity) {
        this.deliveries = deliveries;
        this.identity = identity;
    }

    public record DeliveryResponse(UUID id, UUID requestEventId, UUID incidentId, UUID recipientUserId,
                                   NotificationChannel channel, String destination, DeliveryStatus status,
                                   int attempts, String lastError, Instant nextAttemptAt, Instant lastAttemptAt,
                                   Instant createdAt, Instant updatedAt) {
        static DeliveryResponse of(NotificationDelivery d) {
            return new DeliveryResponse(d.getId(), d.getRequestEventId(), d.getIncidentId(), d.getRecipientUserId(),
                    d.getChannel(), d.getDestination(), d.getStatus(), d.getAttempts(), d.getLastError(),
                    d.getNextAttemptAt(), d.getLastAttemptAt(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    @GetMapping
    public List<DeliveryResponse> list(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                       @RequestParam(required = false) UUID incidentId,
                                       @RequestParam(required = false) DeliveryStatus status) {
        identity.requireMember(orgId, callerId);
        List<NotificationDelivery> result;
        if (incidentId != null) {
            result = deliveries.findByOrganizationIdAndIncidentIdOrderByCreatedAtAsc(orgId, incidentId);
        } else if (status != null) {
            result = deliveries.findByOrganizationIdAndStatusOrderByCreatedAtAsc(orgId, status);
        } else {
            result = deliveries.findByOrganizationIdOrderByCreatedAtAsc(orgId);
        }
        return result.stream().map(DeliveryResponse::of).toList();
    }
}
