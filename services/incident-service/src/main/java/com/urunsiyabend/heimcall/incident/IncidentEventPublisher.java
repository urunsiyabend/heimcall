package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.IncidentAcknowledgedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentCanceledEvent;
import com.urunsiyabend.heimcall.common.events.IncidentResolvedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Forwards in-process incident domain events to Kafka, but only {@code AFTER_COMMIT} so a triggered
 * event is never emitted for an incident whose transaction rolled back. At-least-once: a send failure
 * after commit is logged and lost (transactional outbox is a deferred gap), and downstream consumers
 * are idempotent.
 */
@Component
public class IncidentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventPublisher.class);

    private final KafkaTemplate<String, Object> eventsKafkaTemplate;

    public IncidentEventPublisher(KafkaTemplate<String, Object> eventsKafkaTemplate) {
        this.eventsKafkaTemplate = eventsKafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTriggered(IncidentDomainEvents.Triggered e) {
        IncidentTriggeredEvent payload = new IncidentTriggeredEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId(), e.dedupKey(), e.title(), e.severity(), e.escalationPolicyId());
        send(Topics.INCIDENT_TRIGGERED, e.incidentId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAcknowledged(IncidentDomainEvents.Acknowledged e) {
        IncidentAcknowledgedEvent payload = new IncidentAcknowledgedEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        send(Topics.INCIDENT_ACKNOWLEDGED, e.incidentId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(IncidentDomainEvents.Resolved e) {
        IncidentResolvedEvent payload = new IncidentResolvedEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        send(Topics.INCIDENT_RESOLVED, e.incidentId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCanceled(IncidentDomainEvents.Canceled e) {
        IncidentCanceledEvent payload = new IncidentCanceledEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        send(Topics.INCIDENT_CANCELED, e.incidentId(), payload);
    }

    private void send(String topic, UUID incidentId, Object payload) {
        try {
            eventsKafkaTemplate.send(topic, incidentId.toString(), payload);
            log.debug("Published {} for incident {}", topic, incidentId);
        } catch (RuntimeException ex) {
            log.error("Failed to publish {} for incident {}: {}", topic, incidentId, ex.getMessage());
        }
    }
}
