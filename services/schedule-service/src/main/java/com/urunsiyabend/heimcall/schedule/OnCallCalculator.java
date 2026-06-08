package com.urunsiyabend.heimcall.schedule;

import com.urunsiyabend.heimcall.schedule.domain.RotationType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure rotation math: which participant is on-call for a single rotation at a given instant.
 *
 * <p>Boundaries are calendar-based in the schedule's zone. The rotation is anchored at
 * {@code startDate} + {@code handoffTime} in {@code zone}; DAILY hands off every local day at
 * {@code handoffTime}, WEEKLY every 7 local days. Using {@link ChronoUnit} on {@link ZonedDateTime}
 * makes the count DST-aware. No persistence here so it is trivially unit-testable.
 */
public final class OnCallCalculator {

    private OnCallCalculator() {
    }

    public record RotationView(RotationType type, LocalDate startDate, LocalTime handoffTime,
                               List<UUID> participants) {
    }

    /** The on-call participant for this rotation at {@code t}, or empty if not started / no participants. */
    public static Optional<UUID> resolve(RotationView rotation, ZoneId zone, Instant t) {
        List<UUID> participants = rotation.participants();
        if (participants.isEmpty()) {
            return Optional.empty();
        }
        ZonedDateTime anchor = ZonedDateTime.of(rotation.startDate(), rotation.handoffTime(), zone);
        ZonedDateTime query = t.atZone(zone);
        if (query.isBefore(anchor)) {
            return Optional.empty();
        }
        long periods = switch (rotation.type()) {
            case DAILY -> ChronoUnit.DAYS.between(anchor, query);
            case WEEKLY -> ChronoUnit.WEEKS.between(anchor, query);
        };
        int index = (int) Math.floorMod(periods, participants.size());
        return Optional.of(participants.get(index));
    }
}
