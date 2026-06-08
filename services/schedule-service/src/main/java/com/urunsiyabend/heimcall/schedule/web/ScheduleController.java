package com.urunsiyabend.heimcall.schedule.web;

import com.urunsiyabend.heimcall.schedule.IdentityClient;
import com.urunsiyabend.heimcall.schedule.OnCallResolver;
import com.urunsiyabend.heimcall.schedule.domain.OnCallSchedule;
import com.urunsiyabend.heimcall.schedule.domain.OnCallScheduleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/schedules")
public class ScheduleController {

    private final OnCallScheduleRepository schedules;
    private final OnCallResolver resolver;
    private final IdentityClient identity;

    public ScheduleController(OnCallScheduleRepository schedules, OnCallResolver resolver, IdentityClient identity) {
        this.schedules = schedules;
        this.resolver = resolver;
        this.identity = identity;
    }

    public record CreateRequest(@NotBlank String name, @NotBlank String timezone) {
    }

    public record ScheduleResponse(UUID id, UUID organizationId, String name, String timezone) {
        static ScheduleResponse of(OnCallSchedule s) {
            return new ScheduleResponse(s.getId(), s.getOrganizationId(), s.getName(), s.getTimezone());
        }
    }

    public record OnCallResponse(UUID scheduleId, Instant at, UUID userId, String source, UUID rotationId) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleResponse create(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                   @Valid @RequestBody CreateRequest req) {
        identity.requireMember(orgId, callerId);
        validateZone(req.timezone());
        OnCallSchedule saved = schedules.save(
                OnCallSchedule.create(orgId, req.name(), req.timezone(), Instant.now()));
        return ScheduleResponse.of(saved);
    }

    @GetMapping
    public List<ScheduleResponse> list(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return schedules.findByOrganizationId(orgId).stream().map(ScheduleResponse::of).toList();
    }

    @GetMapping("/{id}")
    public ScheduleResponse get(@PathVariable UUID orgId, @PathVariable UUID id,
                                @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return ScheduleResponse.of(load(orgId, id));
    }

    @PutMapping("/{id}")
    public ScheduleResponse update(@PathVariable UUID orgId, @PathVariable UUID id,
                                   @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody CreateRequest req) {
        identity.requireMember(orgId, callerId);
        validateZone(req.timezone());
        OnCallSchedule s = load(orgId, id);
        s.update(req.name(), req.timezone(), Instant.now());
        return ScheduleResponse.of(schedules.save(s));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID id,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        schedules.delete(load(orgId, id));
    }

    /** Current (or at a given instant) on-call responder. {@code at} defaults to now. */
    @GetMapping("/{id}/on-call")
    public OnCallResponse onCall(@PathVariable UUID orgId, @PathVariable UUID id,
                                 @RequestHeader("X-User-Id") UUID callerId,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        identity.requireMember(orgId, callerId);
        OnCallSchedule schedule = load(orgId, id);
        Instant when = at != null ? at : Instant.now();
        return resolver.resolve(schedule, when)
                .map(oc -> new OnCallResponse(id, when, oc.userId(), oc.source().name(), oc.rotationId()))
                .orElse(new OnCallResponse(id, when, null, null, null));
    }

    private OnCallSchedule load(UUID orgId, UUID id) {
        return schedules.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("schedule not found in organization: " + id));
    }

    private void validateZone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new ApiExceptions.BadRequestException("invalid timezone: " + timezone);
        }
    }
}
