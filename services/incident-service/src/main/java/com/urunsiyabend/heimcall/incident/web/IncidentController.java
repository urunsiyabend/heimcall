package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public IncidentController(IncidentRepository incidents, TimelineEventRepository timeline) {
        this.incidents = incidents;
        this.timeline = timeline;
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
}
