package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.incident.domain.Alert;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrence;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrenceRepository;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Core engine for the domain flow {@code Event -> Alert -> Incident} (glossary §2). An inbound
 * {@link AlertReceivedEvent} is logged as an {@link AlertOccurrence} under the deduplicated
 * {@link Alert} aggregate; an actionable alert then creates or updates an {@link Incident}.
 * Deduplication lives at the alert level: at most one OPEN alert per {@code (organization, dedupKey)};
 * repeats bump the alert's occurrence count. Every meaningful incident change appends a timeline event.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final TimelineEventRepository timeline;
    private final AlertRepository alerts;
    private final AlertOccurrenceRepository occurrences;
    private final ProcessedEventRepository processedEvents;
    private final CatalogClient catalog;
    private final ApplicationEventPublisher events;

    public IncidentService(IncidentRepository incidents, TimelineEventRepository timeline,
                           AlertRepository alerts, AlertOccurrenceRepository occurrences,
                           ProcessedEventRepository processedEvents, CatalogClient catalog,
                           ApplicationEventPublisher events) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.alerts = alerts;
        this.occurrences = occurrences;
        this.processedEvents = processedEvents;
        this.catalog = catalog;
        this.events = events;
    }

    @Transactional
    public void handle(AlertReceivedEvent event) {
        // Idempotency: a Kafka redelivery of an already-handled event must be a no-op.
        // The ledger row is written in this same transaction, so it commits atomically
        // with the alert/incident change and is absent if processing rolled back.
        if (event.eventId() != null && processedEvents.existsById(event.eventId())) {
            log.debug("Skipping already-processed event {}", event.eventId());
            return;
        }

        Instant at = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        Optional<Alert> openAlert = alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(
                event.organizationId(), event.dedupKey(), AlertStatus.OPEN);

        switch (event.messageType()) {
            case CRITICAL, WARNING -> triggerOrDeduplicate(event, openAlert, at);
            case RECOVERY -> openAlert.ifPresent(alert -> recover(event, alert, at));
            case ACKNOWLEDGEMENT -> openAlert.ifPresent(alert -> acknowledge(event, alert, at));
            case INFO -> recordNonActionable(event, openAlert, at);
        }

        if (event.eventId() != null) {
            processedEvents.save(new ProcessedEvent(event.eventId(), Instant.now()));
        }
    }

    private void triggerOrDeduplicate(AlertReceivedEvent event, Optional<Alert> openAlert, Instant at) {
        if (openAlert.isPresent()) {
            Alert alert = openAlert.get();
            alert.registerOccurrence(event.severity(), event.title(), at);
            alerts.save(alert);
            logOccurrence(alert.getId(), event, at);
            // Reflect the duplicate on the actionable alert's incident timeline, if it has an open one.
            if (alert.getIncidentId() != null) {
                incidents.findById(alert.getIncidentId())
                        .filter(Incident::isOpen)
                        .ifPresent(incident -> {
                            incident.touch(at);
                            incidents.save(incident);
                            timeline.save(TimelineEvent.of(incident.getId(), "DUPLICATE",
                                    "Duplicate signal collapsed onto incident (occurrences=" + alert.getOccurrenceCount() + ")", at));
                        });
            }
            log.info("Duplicate collapsed onto alert {} dedupKey={} occurrences={}",
                    alert.getId(), event.dedupKey(), alert.getOccurrenceCount());
            return;
        }

        // First signal for this dedup key. CRITICAL/WARNING are actionable, so it also opens an incident.
        Alert alert = Alert.open(event.organizationId(), event.source(), event.dedupKey(),
                event.externalEntityId(), event.severity(), event.title(), at);
        alerts.save(alert);
        logOccurrence(alert.getId(), event, at);

        Incident incident = Incident.trigger(
                event.organizationId(), event.source(), event.dedupKey(),
                event.externalEntityId(), event.title(), event.description(),
                event.severity(), event.routingKey(), at);

        // Resolve routing -> owning service + escalation policy (best-effort; incident is created regardless).
        Optional<Routing> routing = catalog.resolve(event.organizationId(), event.routingKey());
        UUID policyId = routing.map(Routing::escalationPolicyId).orElse(null);
        incident.stampRouting(routing.map(Routing::serviceId).orElse(null), policyId);

        incidents.save(incident);
        alert.linkIncident(incident.getId());
        alerts.save(alert);

        timeline.save(TimelineEvent.of(incident.getId(), "TRIGGER", "Incident triggered: " + event.title(), at));
        if (policyId == null) {
            timeline.save(TimelineEvent.of(incident.getId(), "NO_POLICY",
                    "No escalation policy resolved (routingKey=" + event.routingKey() + ")", at));
        }
        log.info("Alert {} opened incident {} dedupKey={} policy={}",
                alert.getId(), incident.getId(), event.dedupKey(), policyId);

        events.publishEvent(new IncidentDomainEvents.Triggered(incident.getId(), incident.getOrganizationId(),
                incident.getDedupKey(), incident.getTitle(), incident.getSeverity(), policyId, at));
    }

    private void recover(AlertReceivedEvent event, Alert alert, Instant at) {
        alert.close(at);
        alerts.save(alert);
        logOccurrence(alert.getId(), event, at);
        if (alert.getIncidentId() == null) {
            log.info("Recovery closed non-actionable alert {} dedupKey={}", alert.getId(), alert.getDedupKey());
            return;
        }
        incidents.findById(alert.getIncidentId())
                .filter(Incident::isOpen)
                .ifPresent(incident -> {
                    incident.resolve(at);
                    incidents.save(incident);
                    timeline.save(TimelineEvent.of(incident.getId(), "RESOLVE", "Incident resolved by recovery event", at));
                    log.info("Incident {} resolved by recovery", incident.getId());
                    events.publishEvent(new IncidentDomainEvents.Resolved(incident.getId(), incident.getOrganizationId(), at));
                });
    }

    private void acknowledge(AlertReceivedEvent event, Alert alert, Instant at) {
        alert.acknowledge(at);
        alerts.save(alert);
        logOccurrence(alert.getId(), event, at);
        if (alert.getIncidentId() == null) {
            log.info("Ack on non-actionable alert {} dedupKey={}", alert.getId(), alert.getDedupKey());
            return;
        }
        incidents.findById(alert.getIncidentId())
                .filter(incident -> incident.getStatus() == IncidentStatus.TRIGGERED)
                .ifPresent(incident -> {
                    incident.acknowledge(at);
                    incidents.save(incident);
                    timeline.save(TimelineEvent.of(incident.getId(), "ACK", "Incident acknowledged by external event", at));
                    log.info("Incident {} acknowledged", incident.getId());
                    events.publishEvent(new IncidentDomainEvents.Acknowledged(incident.getId(), incident.getOrganizationId(), at));
                });
    }

    /** INFO is non-actionable: log/dedupe the alert (glossary "an alert may have no incident"); no incident. */
    private void recordNonActionable(AlertReceivedEvent event, Optional<Alert> openAlert, Instant at) {
        Alert alert = openAlert.orElse(null);
        if (alert != null) {
            alert.registerOccurrence(event.severity(), event.title(), at);
            alerts.save(alert);
        } else {
            alert = Alert.open(event.organizationId(), event.source(), event.dedupKey(),
                    event.externalEntityId(), event.severity(), event.title(), at);
            alerts.save(alert);
            log.debug("Recorded non-actionable alert dedupKey={}", event.dedupKey());
        }
        logOccurrence(alert.getId(), event, at);
    }

    /** Append the immutable per-signal record under the alert. */
    private void logOccurrence(UUID alertId, AlertReceivedEvent event, Instant at) {
        occurrences.save(AlertOccurrence.of(alertId, event.eventId(), event.messageType(), event.severity(),
                event.title(), event.description(), at, Instant.now()));
    }
}
