package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.NotificationRequestedEvent;
import com.urunsiyabend.heimcall.notification.domain.ContactMethod;
import com.urunsiyabend.heimcall.notification.domain.ContactMethodRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Consumes notification requests and fans each one out into a PENDING delivery per enabled contact
 * method of the recipient. Idempotent on the request event id (the {@link NotificationRequest} PK).
 * Actual sending is done by {@link DeliveryService} via the {@link DeliveryWorker}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRequestRepository requests;
    private final NotificationDeliveryRepository deliveries;
    private final ContactMethodRepository contactMethods;

    public NotificationService(NotificationRequestRepository requests, NotificationDeliveryRepository deliveries,
                               ContactMethodRepository contactMethods) {
        this.requests = requests;
        this.deliveries = deliveries;
        this.contactMethods = contactMethods;
    }

    @Transactional
    public void onNotificationRequested(NotificationRequestedEvent event) {
        if (event.eventId() != null && requests.existsById(event.eventId())) {
            log.debug("Skipping already-processed notification request {}", event.eventId());
            return;
        }

        Instant now = Instant.now();
        requests.save(NotificationRequest.of(event.eventId(), event.organizationId(), event.incidentId(),
                event.targetUserId(), event.level(), event.targetSource(), event.title(), event.severity(), now));

        List<ContactMethod> enabled = contactMethods.findByOrganizationIdAndUserIdAndEnabledTrue(
                event.organizationId(), event.targetUserId());
        if (enabled.isEmpty()) {
            log.warn("No enabled contact methods for user {} in org {}; notification request {} has nothing to deliver",
                    event.targetUserId(), event.organizationId(), event.eventId());
            return;
        }

        for (ContactMethod cm : enabled) {
            deliveries.save(NotificationDelivery.pending(event.eventId(), event.organizationId(),
                    event.incidentId(), event.targetUserId(), cm.getId(), cm.getChannel(), cm.getDestination(), now));
        }
        log.info("Notification request {} for incident {} fanned out to {} delivery(ies)",
                event.eventId(), event.incidentId(), enabled.size());
    }
}
