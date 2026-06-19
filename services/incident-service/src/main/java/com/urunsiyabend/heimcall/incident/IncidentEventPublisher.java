package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.IncidentAcknowledgedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentCanceledEvent;
import com.urunsiyabend.heimcall.common.events.IncidentResolvedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends incident domain events to the transactional outbox (Phase 9). Runs as a synchronous
 * {@link EventListener} so the outbox INSERT joins the same transaction as the incident change: a
 * rolled-back command writes no row (no ghost event), a committed one is published later by the relay
 * (never lost). Replaces the previous {@code AFTER_COMMIT} {@code KafkaTemplate.send}, whose post-commit
 * window could drop an event on a crash or broker outage. At-least-once; downstream consumers are idempotent.
 */
@Component
public class IncidentEventPublisher {

    private static final String AGGREGATE_TYPE = "incident";

    private final OutboxAppender outbox;

    public IncidentEventPublisher(OutboxAppender outbox) {
        this.outbox = outbox;
    }

    @EventListener
    public void onTriggered(IncidentDomainEvents.Triggered e) {
        IncidentTriggeredEvent payload = new IncidentTriggeredEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId(), e.dedupKey(), e.title(), e.severity(), e.escalationPolicyId());
        append(e.incidentId(), payload);
    }

    @EventListener
    public void onAcknowledged(IncidentDomainEvents.Acknowledged e) {
        IncidentAcknowledgedEvent payload = new IncidentAcknowledgedEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        append(e.incidentId(), payload);
    }

    @EventListener
    public void onResolved(IncidentDomainEvents.Resolved e) {
        IncidentResolvedEvent payload = new IncidentResolvedEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        append(e.incidentId(), payload);
    }

    @EventListener
    public void onCanceled(IncidentDomainEvents.Canceled e) {
        IncidentCanceledEvent payload = new IncidentCanceledEvent(UUID.randomUUID(), e.at(),
                e.organizationId(), e.incidentId());
        append(e.incidentId(), payload);
    }

    private void append(UUID incidentId, Object payload) {
        // All lifecycle events go to one ordered, incidentId-keyed topic (Phase 12). aggregate_id +
        // msg_key are both incidentId, so the outbox relay's per-aggregate ordering guard and Kafka's
        // per-partition ordering together preserve publish order for an incident's events.
        outbox.append(AGGREGATE_TYPE, incidentId.toString(), Topics.INCIDENT_LIFECYCLE, incidentId.toString(), payload);
    }
}
