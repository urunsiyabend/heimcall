package com.urunsiyabend.heimcall.schedule;

import com.urunsiyabend.heimcall.schedule.domain.OnCallSchedule;
import com.urunsiyabend.heimcall.schedule.domain.RotationParticipant;
import com.urunsiyabend.heimcall.schedule.domain.RotationParticipantRepository;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleOverride;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleOverrideRepository;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleRotation;
import com.urunsiyabend.heimcall.schedule.domain.ScheduleRotationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the on-call responder for a schedule at an instant: an active override wins; otherwise
 * the highest-priority rotation that has started and has participants. Rotation math is delegated
 * to {@link OnCallCalculator}.
 */
@Service
public class OnCallResolver {

    private final ScheduleRotationRepository rotations;
    private final RotationParticipantRepository participants;
    private final ScheduleOverrideRepository overrides;

    public OnCallResolver(ScheduleRotationRepository rotations, RotationParticipantRepository participants,
                          ScheduleOverrideRepository overrides) {
        this.rotations = rotations;
        this.participants = participants;
        this.overrides = overrides;
    }

    public enum Source { OVERRIDE, ROTATION }

    public record OnCall(UUID userId, Source source, UUID rotationId) {
    }

    public Optional<OnCall> resolve(OnCallSchedule schedule, Instant at) {
        // Overrides take priority; if several overlap, the most recently created one wins.
        Optional<ScheduleOverride> override = overrides.findByScheduleId(schedule.getId()).stream()
                .filter(o -> o.coversInstant(at))
                .max(Comparator.comparing(ScheduleOverride::getCreatedAt));
        if (override.isPresent()) {
            return Optional.of(new OnCall(override.get().getUserId(), Source.OVERRIDE, null));
        }

        ZoneId zone = ZoneId.of(schedule.getTimezone());
        for (ScheduleRotation rotation : rotations.findByScheduleIdOrderByPriorityDesc(schedule.getId())) {
            List<UUID> ordered = participants.findByRotationIdOrderByPositionAsc(rotation.getId()).stream()
                    .map(RotationParticipant::getUserId)
                    .toList();
            Optional<UUID> user = OnCallCalculator.resolve(
                    new OnCallCalculator.RotationView(rotation.getType(), rotation.getStartDate(),
                            rotation.getHandoffTime(), ordered),
                    zone, at);
            if (user.isPresent()) {
                return Optional.of(new OnCall(user.get(), Source.ROTATION, rotation.getId()));
            }
        }
        return Optional.empty();
    }
}
