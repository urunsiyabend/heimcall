package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.incident.IdentityClient;
import com.urunsiyabend.heimcall.incident.IncidentCommandService;
import com.urunsiyabend.heimcall.incident.IncidentStreamRegistry;
import com.urunsiyabend.heimcall.incident.domain.Alert;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrenceRepository;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/incidents")
public class IncidentController {

    private final IncidentRepository incidents;
    private final TimelineEventRepository timeline;
    private final AlertRepository alerts;
    private final AlertOccurrenceRepository occurrences;
    private final IncidentCommandService commands;
    private final IdentityClient identity;
    private final IncidentStreamRegistry stream;

    public IncidentController(IncidentRepository incidents, TimelineEventRepository timeline,
                              AlertRepository alerts, AlertOccurrenceRepository occurrences,
                              IncidentCommandService commands, IdentityClient identity,
                              IncidentStreamRegistry stream) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.alerts = alerts;
        this.occurrences = occurrences;
        this.commands = commands;
        this.identity = identity;
        this.stream = stream;
    }

    // Queries are tenant-scoped: the caller must be a member of the organization owning the incident.
    // List requires an explicit organizationId; per-incident reads derive the org from the incident.

    @GetMapping
    public List<IncidentResponse> list(@RequestParam UUID organizationId,
                                       @RequestParam(required = false) IncidentStatus status,
                                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(organizationId, callerId);
        var result = (status == null)
                ? incidents.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                : incidents.findByOrganizationIdAndStatusOrderByCreatedAtDesc(organizationId, status);
        return result.stream().map(IncidentResponse::from).toList();
    }

    // Live lifecycle stream for an org. Member-gated like the queries. EventSource cannot set headers,
    // so the access token arrives as the access_token query param (validated by the JWT filter, which
    // then injects X-User-Id). Pushes one "incident" event per TRIGGER/ACK/RESOLVE/CANCEL.
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam UUID organizationId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(organizationId, callerId);
        return stream.register(organizationId);
    }

    @GetMapping("/{incidentId}")
    public IncidentResponse get(@PathVariable UUID incidentId, @RequestHeader("X-User-Id") UUID callerId) {
        return IncidentResponse.from(visibleIncident(incidentId, callerId));
    }

    @GetMapping("/{incidentId}/timeline")
    public List<TimelineEventResponse> timeline(@PathVariable UUID incidentId,
                                                @RequestHeader("X-User-Id") UUID callerId) {
        visibleIncident(incidentId, callerId);
        return timeline.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(TimelineEventResponse::from)
                .toList();
    }

    @GetMapping("/{incidentId}/alerts")
    public List<AlertResponse> alerts(@PathVariable UUID incidentId, @RequestHeader("X-User-Id") UUID callerId) {
        visibleIncident(incidentId, callerId);
        return alerts.findByIncidentIdOrderByFirstSeenAtAsc(incidentId).stream()
                .map(AlertResponse::from)
                .toList();
    }

    @GetMapping("/{incidentId}/alerts/{alertId}/occurrences")
    public List<AlertOccurrenceResponse> occurrences(@PathVariable UUID incidentId, @PathVariable UUID alertId,
                                                     @RequestHeader("X-User-Id") UUID callerId) {
        visibleIncident(incidentId, callerId);
        Alert alert = alerts.findById(alertId)
                .filter(a -> incidentId.equals(a.getIncidentId()))
                .orElseThrow(() -> new ApiExceptions.NotFoundException("alert not found on incident: " + alertId));
        return occurrences.findByAlertIdOrderByReceivedAtAsc(alert.getId()).stream()
                .map(AlertOccurrenceResponse::from)
                .toList();
    }

    /** Loads the incident (404 if absent) and enforces caller membership of its org (403 if not). */
    private Incident visibleIncident(UUID incidentId, UUID callerId) {
        Incident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("incident not found: " + incidentId));
        identity.requireMember(incident.getOrganizationId(), callerId);
        return incident;
    }

    @PostMapping("/{incidentId}/acknowledge")
    public IncidentResponse acknowledge(@PathVariable UUID incidentId, @RequestHeader("X-User-Id") UUID callerId) {
        return IncidentResponse.from(commands.acknowledge(incidentId, callerId));
    }

    @PostMapping("/{incidentId}/resolve")
    public IncidentResponse resolve(@PathVariable UUID incidentId, @RequestHeader("X-User-Id") UUID callerId) {
        return IncidentResponse.from(commands.resolve(incidentId, callerId));
    }

    @PostMapping("/{incidentId}/cancel")
    public IncidentResponse cancel(@PathVariable UUID incidentId, @RequestHeader("X-User-Id") UUID callerId) {
        return IncidentResponse.from(commands.cancel(incidentId, callerId));
    }
}
