package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.NotificationDeliveredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationFailedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes notification outcome events and reflects them on the incident timeline, closing the
 * trigger -> notify feedback loop (the incident shows who was notified and whether it succeeded).
 * Uses the type-header listener factory because the two topics carry two distinct event types.
 */
@Component
public class NotificationEventListener {

    private final IncidentService incidentService;

    public NotificationEventListener(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_DELIVERED,
            groupId = "incident-service.notification-consumer",
            containerFactory = "notificationListenerContainerFactory")
    public void onDelivered(NotificationDeliveredEvent event) {
        incidentService.recordDelivered(event);
    }

    @KafkaListener(topics = Topics.NOTIFICATION_FAILED,
            groupId = "incident-service.notification-consumer",
            containerFactory = "notificationListenerContainerFactory")
    public void onFailed(NotificationFailedEvent event) {
        incidentService.recordFailed(event);
    }
}
