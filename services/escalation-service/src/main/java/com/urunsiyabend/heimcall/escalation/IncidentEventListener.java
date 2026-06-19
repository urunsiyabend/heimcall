package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.common.events.IncidentAcknowledgedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentCanceledEvent;
import com.urunsiyabend.heimcall.common.events.IncidentResolvedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the single ordered incident lifecycle stream ({@code incident.lifecycle.v1}, Phase 12) and
 * drives the escalation engine. All four lifecycle events share one topic, partition-keyed by
 * {@code incidentId}, so an incident's events are delivered in publish order — an ACK can no longer
 * overtake the TRIGGERED it cancels. The concrete type is taken from the producer's {@code __TypeId__}
 * header and routed to the matching {@link KafkaHandler}.
 */
@Component
@KafkaListener(topics = Topics.INCIDENT_LIFECYCLE, groupId = "escalation-service.incident-consumer")
public class IncidentEventListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventListener.class);

    private final EscalationService escalationService;

    public IncidentEventListener(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    @KafkaHandler
    public void onTriggered(IncidentTriggeredEvent event) {
        escalationService.onIncidentTriggered(event);
    }

    @KafkaHandler
    public void onAcknowledged(IncidentAcknowledgedEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "ACK");
    }

    @KafkaHandler
    public void onResolved(IncidentResolvedEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "RESOLVE");
    }

    @KafkaHandler
    public void onCanceled(IncidentCanceledEvent event) {
        escalationService.onIncidentClosed(event.eventId(), event.incidentId(), "CANCEL");
    }

    /** Unexpected payload type on the lifecycle topic: log and skip (don't poison the partition). */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object event) {
        log.warn("Ignoring unexpected payload on {}: {}", Topics.INCIDENT_LIFECYCLE,
                event != null ? event.getClass().getName() : "null");
    }
}
