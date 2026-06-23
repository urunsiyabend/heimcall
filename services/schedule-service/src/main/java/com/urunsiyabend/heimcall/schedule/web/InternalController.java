package com.urunsiyabend.heimcall.schedule.web;

import com.urunsiyabend.heimcall.schedule.OnCallResolver;
import com.urunsiyabend.heimcall.schedule.domain.OnCallSchedule;
import com.urunsiyabend.heimcall.schedule.domain.OnCallScheduleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-to-service on-call lookup. escalation-service calls this (no user context) to resolve a
 * SCHEDULE rule target to the current on-call user. Not for external callers.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final OnCallScheduleRepository schedules;
    private final OnCallResolver resolver;

    public InternalController(OnCallScheduleRepository schedules, OnCallResolver resolver) {
        this.schedules = schedules;
        this.resolver = resolver;
    }

    public record OnCallResponse(UUID scheduleId, UUID userId, String source, UUID rotationId) {
    }

    /** Current on-call responder of the schedule. 404 if no such schedule in the org; 204 if no one is on call. */
    @GetMapping("/organizations/{orgId}/schedules/{id}/on-call")
    @PreAuthorize("hasAuthority('SCOPE_schedule.on-call.read')")
    public ResponseEntity<OnCallResponse> onCall(@PathVariable UUID orgId, @PathVariable UUID id) {
        OnCallSchedule schedule = schedules.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("schedule not found in organization"));
        return resolver.resolve(schedule, Instant.now())
                .map(oc -> ResponseEntity.ok(
                        new OnCallResponse(id, oc.userId(), oc.source().name(), oc.rotationId())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
