package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.incident.domain.Alert;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEvent;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import com.urunsiyabend.heimcall.incident.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-driven incident lifecycle commands invoked over REST (ACK / resolve / cancel), as opposed
 * to the inbound-signal-driven transitions in {@link IncidentService}. Each command is member-gated by
 * the caller, idempotent (a no-op when the incident already holds the target state), appends a timeline
 * event, and publishes the matching domain event so escalation cancels pending tasks.
 */
@Service
public class IncidentCommandService {

    private static final Logger log = LoggerFactory.getLogger(IncidentCommandService.class);

    private final IncidentRepository incidents;
    private final TimelineEventRepository timeline;
    private final AlertRepository alerts;
    private final IdentityClient identity;
    private final ApplicationEventPublisher events;

    public IncidentCommandService(IncidentRepository incidents, TimelineEventRepository timeline,
                                  AlertRepository alerts, IdentityClient identity,
                                  ApplicationEventPublisher events) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.alerts = alerts;
        this.identity = identity;
        this.events = events;
    }

    @Transactional
    public Incident acknowledge(UUID incidentId, UUID callerId) {
        Incident incident = authorize(incidentId, callerId);
        switch (incident.getStatus()) {
            case ACKNOWLEDGED -> { return incident; } // idempotent no-op
            case TRIGGERED -> {
                Instant at = Instant.now();
                incident.acknowledge(at);
                incidents.save(incident);
                syncOpenAlerts(incidentId, AlertStatus.ACKNOWLEDGED, at);
                timeline.save(TimelineEvent.of(incidentId, "ACK", "Incident acknowledged by user " + callerId, at));
                log.info("Incident {} acknowledged by {}", incidentId, callerId);
                events.publishEvent(new IncidentDomainEvents.Acknowledged(incidentId, incident.getOrganizationId(), at));
            }
            default -> throw illegal(incident, "acknowledge");
        }
        return incident;
    }

    @Transactional
    public Incident resolve(UUID incidentId, UUID callerId) {
        Incident incident = authorize(incidentId, callerId);
        switch (incident.getStatus()) {
            case RESOLVED -> { return incident; } // idempotent no-op
            case TRIGGERED, ACKNOWLEDGED -> {
                Instant at = Instant.now();
                incident.resolve(at);
                incidents.save(incident);
                syncOpenAlerts(incidentId, AlertStatus.CLOSED, at);
                timeline.save(TimelineEvent.of(incidentId, "RESOLVE", "Incident resolved by user " + callerId, at));
                log.info("Incident {} resolved by {}", incidentId, callerId);
                events.publishEvent(new IncidentDomainEvents.Resolved(incidentId, incident.getOrganizationId(), at));
            }
            default -> throw illegal(incident, "resolve");
        }
        return incident;
    }

    @Transactional
    public Incident cancel(UUID incidentId, UUID callerId) {
        Incident incident = authorize(incidentId, callerId);
        switch (incident.getStatus()) {
            case CANCELED -> { return incident; } // idempotent no-op
            case TRIGGERED, ACKNOWLEDGED -> {
                Instant at = Instant.now();
                incident.cancel(at);
                incidents.save(incident);
                syncOpenAlerts(incidentId, AlertStatus.CLOSED, at);
                timeline.save(TimelineEvent.of(incidentId, "CANCEL", "Incident canceled by user " + callerId, at));
                log.info("Incident {} canceled by {}", incidentId, callerId);
                events.publishEvent(new IncidentDomainEvents.Canceled(incidentId, incident.getOrganizationId(), at));
            }
            default -> throw illegal(incident, "cancel");
        }
        return incident;
    }

    /** Track the operator transition on the incident's alerts (alert state follows incident state). */
    private void syncOpenAlerts(UUID incidentId, AlertStatus target, Instant at) {
        for (Alert alert : alerts.findByIncidentIdOrderByFirstSeenAtAsc(incidentId)) {
            if (alert.getStatus() == AlertStatus.CLOSED) {
                continue;
            }
            if (target == AlertStatus.ACKNOWLEDGED) {
                if (alert.getStatus() == AlertStatus.OPEN) {
                    alert.acknowledge(at);
                    alerts.save(alert);
                }
            } else {
                alert.close(at);
                alerts.save(alert);
            }
        }
    }

    private Incident authorize(UUID incidentId, UUID callerId) {
        Incident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("incident not found: " + incidentId));
        identity.requireMember(incident.getOrganizationId(), callerId);
        return incident;
    }

    private ApiExceptions.ConflictException illegal(Incident incident, String command) {
        return new ApiExceptions.ConflictException(
                "cannot " + command + " an incident in state " + incident.getStatus());
    }
}
