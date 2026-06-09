package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.common.events.IncidentAcknowledgedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentCanceledEvent;
import com.urunsiyabend.heimcall.common.events.IncidentResolvedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes incident lifecycle events and drives the escalation engine. */
@Component
public class IncidentEventListener {

    private final EscalationService escalationService;

    public IncidentEventListener(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    @KafkaListener(topics = Topics.INCIDENT_TRIGGERED, groupId = "escalation-service.incident-consumer")
    public void onTriggered(IncidentTriggeredEvent event) {
        escalationService.onIncidentTriggered(event);
    }

    @KafkaListener(topics = Topics.INCIDENT_ACKNOWLEDGED, groupId = "escalation-service.incident-consumer")
    public void onAcknowledged(IncidentAcknowledgedEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "ACK");
    }

    @KafkaListener(topics = Topics.INCIDENT_RESOLVED, groupId = "escalation-service.incident-consumer")
    public void onResolved(IncidentResolvedEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "RESOLVE");
    }

    @KafkaListener(topics = Topics.INCIDENT_CANCELED, groupId = "escalation-service.incident-consumer")
    public void onCanceled(IncidentCanceledEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "CANCEL");
    }
}
