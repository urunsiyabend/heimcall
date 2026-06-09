package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.incident.IncidentCommandService;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrenceRepository;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public IncidentController(IncidentRepository incidents, TimelineEventRepository timeline,
                              AlertRepository alerts, AlertOccurrenceRepository occurrences,
                              IncidentCommandService commands) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.alerts = alerts;
        this.occurrences = occurrences;
        this.commands = commands;
    }

    @GetMapping
    public List<IncidentResponse> list(@RequestParam(required = false) IncidentStatus status) {
        var result = (status == null)
                ? incidents.findAllByOrderByCreatedAtDesc()
                : incidents.findByStatusOrderByCreatedAtDesc(status);
        return result.stream().map(IncidentResponse::from).toList();
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentResponse> get(@PathVariable UUID incidentId) {
        return incidents.findById(incidentId)
                .map(IncidentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{incidentId}/timeline")
    public List<TimelineEventResponse> timeline(@PathVariable UUID incidentId) {
        return timeline.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(TimelineEventResponse::from)
                .toList();
    }

    @GetMapping("/{incidentId}/alerts")
    public List<AlertResponse> alerts(@PathVariable UUID incidentId) {
        return alerts.findByIncidentIdOrderByFirstSeenAtAsc(incidentId).stream()
                .map(AlertResponse::from)
                .toList();
    }

    @GetMapping("/{incidentId}/alerts/{alertId}/occurrences")
    public List<AlertOccurrenceResponse> occurrences(@PathVariable UUID incidentId, @PathVariable UUID alertId) {
        return occurrences.findByAlertIdOrderByReceivedAtAsc(alertId).stream()
                .map(AlertOccurrenceResponse::from)
                .toList();
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
