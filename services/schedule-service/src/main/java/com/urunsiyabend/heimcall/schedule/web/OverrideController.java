package com.urunsiyabend.heimcall.schedule.web;

import com.urunsiyabend.heimcall.schedule.IdentityClient;
import com.urunsiyabend.heimcall.schedule.domain.OnCallScheduleRepository;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleOverride;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleOverrideRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/schedules/{scheduleId}/overrides")
public class OverrideController {

    private final ScheduleOverrideRepository overrides;
    private final OnCallScheduleRepository schedules;
    private final IdentityClient identity;

    public OverrideController(ScheduleOverrideRepository overrides, OnCallScheduleRepository schedules,
                              IdentityClient identity) {
        this.overrides = overrides;
        this.schedules = schedules;
        this.identity = identity;
    }

    public record CreateRequest(@NotNull UUID userId, @NotNull Instant startAt, @NotNull Instant endAt) {
    }

    public record OverrideResponse(UUID id, UUID scheduleId, UUID userId, Instant startAt, Instant endAt) {
        static OverrideResponse of(ScheduleOverride o) {
            return new OverrideResponse(o.getId(), o.getScheduleId(), o.getUserId(), o.getStartAt(), o.getEndAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OverrideResponse create(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                   @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody CreateRequest req) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        if (!req.endAt().isAfter(req.startAt())) {
            throw new ApiExceptions.BadRequestException("endAt must be after startAt");
        }
        identity.requireOrgUser(orgId, req.userId());
        ScheduleOverride saved = overrides.save(
                ScheduleOverride.create(scheduleId, req.userId(), req.startAt(), req.endAt(), Instant.now()));
        return OverrideResponse.of(saved);
    }

    @GetMapping
    public List<OverrideResponse> list(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        return overrides.findByScheduleId(scheduleId).stream().map(OverrideResponse::of).toList();
    }

    @DeleteMapping("/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID scheduleId, @PathVariable UUID overrideId,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        overrides.findById(overrideId)
                .filter(o -> o.getScheduleId().equals(scheduleId))
                .ifPresent(overrides::delete);
    }

    private void requireSchedule(UUID orgId, UUID scheduleId) {
        if (schedules.findByIdAndOrganizationId(scheduleId, orgId).isEmpty()) {
            throw new ApiExceptions.NotFoundException("schedule not found in organization: " + scheduleId);
        }
    }
}
