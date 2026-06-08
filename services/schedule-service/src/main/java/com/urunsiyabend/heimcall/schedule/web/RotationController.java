package com.urunsiyabend.heimcall.schedule.web;

import com.urunsiyabend.heimcall.schedule.IdentityClient;
import com.urunsiyabend.heimcall.schedule.domain.OnCallScheduleRepository;
import com.urunsiyabend.heimcall.schedule.domain.RotationParticipant;
import com.urunsiyabend.heimcall.schedule.domain.RotationParticipantRepository;
import com.urunsiyabend.heimcall.schedule.domain.RotationType;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleRotation;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleRotationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/schedules/{scheduleId}/rotations")
public class RotationController {

    private final ScheduleRotationRepository rotations;
    private final RotationParticipantRepository participants;
    private final OnCallScheduleRepository schedules;
    private final IdentityClient identity;

    public RotationController(ScheduleRotationRepository rotations, RotationParticipantRepository participants,
                             OnCallScheduleRepository schedules, IdentityClient identity) {
        this.rotations = rotations;
        this.participants = participants;
        this.schedules = schedules;
        this.identity = identity;
    }

    public record CreateRotationRequest(@NotBlank String name, @NotNull RotationType type,
                                        @NotNull LocalDate startDate, @NotNull LocalTime handoffTime,
                                        int priority) {
    }

    public record RotationResponse(UUID id, UUID scheduleId, String name, RotationType type,
                                   LocalDate startDate, LocalTime handoffTime, int priority) {
        static RotationResponse of(ScheduleRotation r) {
            return new RotationResponse(r.getId(), r.getScheduleId(), r.getName(), r.getType(),
                    r.getStartDate(), r.getHandoffTime(), r.getPriority());
        }
    }

    public record AddParticipantRequest(@NotNull UUID userId, @PositiveOrZero int position) {
    }

    public record ParticipantResponse(UUID id, UUID rotationId, UUID userId, int position) {
        static ParticipantResponse of(RotationParticipant p) {
            return new ParticipantResponse(p.getId(), p.getRotationId(), p.getUserId(), p.getPosition());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RotationResponse create(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                   @RequestHeader("X-User-Id") UUID callerId,
                                   @Valid @RequestBody CreateRotationRequest req) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        ScheduleRotation saved = rotations.save(ScheduleRotation.create(scheduleId, req.name(), req.type(),
                req.startDate(), req.handoffTime(), req.priority(), Instant.now()));
        return RotationResponse.of(saved);
    }

    @GetMapping
    public List<RotationResponse> list(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        return rotations.findByScheduleIdOrderByPriorityDesc(scheduleId).stream().map(RotationResponse::of).toList();
    }

    @DeleteMapping("/{rotationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID scheduleId, @PathVariable UUID rotationId,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        rotations.delete(loadRotation(scheduleId, rotationId));
    }

    @PostMapping("/{rotationId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse addParticipant(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                              @PathVariable UUID rotationId, @RequestHeader("X-User-Id") UUID callerId,
                                              @Valid @RequestBody AddParticipantRequest req) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        loadRotation(scheduleId, rotationId);
        identity.requireOrgUser(orgId, req.userId());
        if (participants.existsByRotationIdAndPosition(rotationId, req.position())) {
            throw new ApiExceptions.ConflictException("position already taken: " + req.position());
        }
        RotationParticipant saved = participants.save(
                RotationParticipant.create(rotationId, req.userId(), req.position()));
        return ParticipantResponse.of(saved);
    }

    @GetMapping("/{rotationId}/participants")
    public List<ParticipantResponse> listParticipants(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                                      @PathVariable UUID rotationId,
                                                      @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        loadRotation(scheduleId, rotationId);
        return participants.findByRotationIdOrderByPositionAsc(rotationId).stream()
                .map(ParticipantResponse::of).toList();
    }

    @DeleteMapping("/{rotationId}/participants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(@PathVariable UUID orgId, @PathVariable UUID scheduleId,
                                  @PathVariable UUID rotationId, @PathVariable UUID userId,
                                  @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireSchedule(orgId, scheduleId);
        loadRotation(scheduleId, rotationId);
        participants.deleteByRotationIdAndUserId(rotationId, userId);
    }

    private void requireSchedule(UUID orgId, UUID scheduleId) {
        if (schedules.findByIdAndOrganizationId(scheduleId, orgId).isEmpty()) {
            throw new ApiExceptions.NotFoundException("schedule not found in organization: " + scheduleId);
        }
    }

    private ScheduleRotation loadRotation(UUID scheduleId, UUID rotationId) {
        return rotations.findByIdAndScheduleId(rotationId, scheduleId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("rotation not found in schedule: " + rotationId));
    }
}
