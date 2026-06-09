package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.ProcessedEvent;
import com.urunsiyabend.heimcall.incident.domain.ProcessedEventRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEvent;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core incident engine: applies deduplication and the incident lifecycle rules
 * to inbound {@link AlertReceivedEvent}s. Every meaningful change appends a timeline event.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final List<IncidentStatus> OPEN_STATUSES =
            List.of(IncidentStatus.TRIGGERED, IncidentStatus.ACKNOWLEDGED);

    private final IncidentRepository incidents;
    private final TimelineEventRepository timeline;
    private final ProcessedEventRepository processedEvents;
    private final CatalogClient catalog;
    private final ApplicationEventPublisher events;

    public IncidentService(IncidentRepository incidents, TimelineEventRepository timeline,
                           ProcessedEventRepository processedEvents, CatalogClient catalog,
                           ApplicationEventPublisher events) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.processedEvents = processedEvents;
        this.catalog = catalog;
        this.events = events;
    }

    @Transactional
    public void handle(AlertReceivedEvent event) {
        // Idempotency: a Kafka redelivery of an already-handled event must be a no-op.
        // The ledger row is written in this same transaction, so it commits atomically
        // with the incident change and is absent if processing rolled back.
        if (event.eventId() != null && processedEvents.existsById(event.eventId())) {
            log.debug("Skipping already-processed event {}", event.eventId());
            return;
        }

        Instant at = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        Optional<Incident> open = incidents.findFirstByOrganizationIdAndDedupKeyAndStatusIn(
                event.organizationId(), event.dedupKey(), OPEN_STATUSES);

        switch (event.messageType()) {
            case CRITICAL, WARNING -> triggerOrDeduplicate(event, open, at);
            case RECOVERY -> open.ifPresent(incident -> resolve(incident, at));
            case ACKNOWLEDGEMENT -> open.ifPresent(incident -> acknowledge(incident, at));
            case INFO -> log.debug("INFO event ignored, dedupKey={}", event.dedupKey());
        }

        if (event.eventId() != null) {
            processedEvents.save(new ProcessedEvent(event.eventId(), Instant.now()));
        }
    }

    private void triggerOrDeduplicate(AlertReceivedEvent event, Optional<Incident> open, Instant at) {
        if (open.isPresent()) {
            Incident incident = open.get();
            incident.registerDuplicate(at);
            incidents.save(incident);
            timeline.save(TimelineEvent.of(incident.getId(), "DUPLICATE",
                    "Duplicate signal collapsed onto incident (alertCount=" + incident.getAlertCount() + ")", at));
            log.info("Duplicate collapsed onto incident {} dedupKey={}", incident.getId(), event.dedupKey());
            return;
        }

        Incident incident = Incident.trigger(
                event.organizationId(), event.source(), event.dedupKey(),
                event.externalEntityId(), event.title(), event.description(),
                event.severity(), event.routingKey(), at);

        // Resolve routing -> owning service + escalation policy (best-effort; incident is created regardless).
        Optional<Routing> routing = catalog.resolve(event.organizationId(), event.routingKey());
        UUID policyId = routing.map(Routing::escalationPolicyId).orElse(null);
        incident.stampRouting(routing.map(Routing::serviceId).orElse(null), policyId);

        incidents.save(incident);
        timeline.save(TimelineEvent.of(incident.getId(), "TRIGGER", "Incident triggered: " + event.title(), at));
        if (policyId == null) {
            timeline.save(TimelineEvent.of(incident.getId(), "NO_POLICY",
                    "No escalation policy resolved (routingKey=" + event.routingKey() + ")", at));
        }
        log.info("Incident {} triggered dedupKey={} policy={}", incident.getId(), event.dedupKey(), policyId);

        events.publishEvent(new IncidentDomainEvents.Triggered(incident.getId(), incident.getOrganizationId(),
                incident.getDedupKey(), incident.getTitle(), incident.getSeverity(), policyId, at));
    }

    private void resolve(Incident incident, Instant at) {
        incident.resolve(at);
        incidents.save(incident);
        timeline.save(TimelineEvent.of(incident.getId(), "RESOLVE", "Incident resolved by recovery event", at));
        log.info("Incident {} resolved by recovery", incident.getId());
        events.publishEvent(new IncidentDomainEvents.Resolved(incident.getId(), incident.getOrganizationId(), at));
    }

    private void acknowledge(Incident incident, Instant at) {
        incident.acknowledge(at);
        incidents.save(incident);
        timeline.save(TimelineEvent.of(incident.getId(), "ACK", "Incident acknowledged by external event", at));
        log.info("Incident {} acknowledged", incident.getId());
        events.publishEvent(new IncidentDomainEvents.Acknowledged(incident.getId(), incident.getOrganizationId(), at));
    }
}
