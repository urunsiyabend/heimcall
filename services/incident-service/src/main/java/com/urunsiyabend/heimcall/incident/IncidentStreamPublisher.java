package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.incident.web.IncidentStreamEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes in-process incident lifecycle changes to live SSE subscribers, only {@code AFTER_COMMIT} so a
 * rolled-back transaction never streams a ghost update. Mirrors {@link IncidentEventPublisher} (which
 * forwards the same domain events to Kafka) but targets the in-memory {@link IncidentStreamRegistry}.
 */
@Component
public class IncidentStreamPublisher {

    private final IncidentStreamRegistry registry;

    public IncidentStreamPublisher(IncidentStreamRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTriggered(IncidentDomainEvents.Triggered e) {
        registry.publish(e.organizationId(), new IncidentStreamEvent(e.incidentId(), IncidentStatus.TRIGGERED, e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAcknowledged(IncidentDomainEvents.Acknowledged e) {
        registry.publish(e.organizationId(), new IncidentStreamEvent(e.incidentId(), IncidentStatus.ACKNOWLEDGED, e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(IncidentDomainEvents.Resolved e) {
        registry.publish(e.organizationId(), new IncidentStreamEvent(e.incidentId(), IncidentStatus.RESOLVED, e.at()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCanceled(IncidentDomainEvents.Canceled e) {
        registry.publish(e.organizationId(), new IncidentStreamEvent(e.incidentId(), IncidentStatus.CANCELED, e.at()));
    }
}
