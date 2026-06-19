package com.urunsiyabend.heimcall.schedule;

import com.urunsiyabend.heimcall.schedule.domain.RotationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnCallCalculatorTest {

    private static final UUID P0 = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul"); // permanent +03, no DST

    private OnCallCalculator.RotationView daily() {
        return new OnCallCalculator.RotationView(
                RotationType.DAILY, LocalDate.of(2026, 6, 8), LocalTime.of(9, 0), List.of(P0, P1));
    }

    private OnCallCalculator.RotationView weekly() {
        // 2026-06-08 is a Monday
        return new OnCallCalculator.RotationView(
                RotationType.WEEKLY, LocalDate.of(2026, 6, 8), LocalTime.of(9, 0), List.of(P0, P1));
    }

    @Test
    void emptyParticipantsHasNoOnCall() {
        var r = new OnCallCalculator.RotationView(RotationType.DAILY, LocalDate.of(2026, 6, 8),
                LocalTime.of(9, 0), List.of());
        assertTrue(OnCallCalculator.resolve(r, ISTANBUL, Instant.parse("2026-06-08T12:00:00Z")).isEmpty());
    }

    @Test
    void beforeAnchorHasNoOnCall() {
        // 2026-06-08 08:00 +03 is before the 09:00 handoff anchor
        assertTrue(OnCallCalculator.resolve(daily(), ISTANBUL, Instant.parse("2026-06-08T05:00:00Z")).isEmpty());
    }

    @Test
    void dailyRotatesAndWrapsAround() {
        assertEquals(P0, OnCallCalculator.resolve(daily(), ISTANBUL, Instant.parse("2026-06-08T07:00:00Z")).orElseThrow());
        assertEquals(P1, OnCallCalculator.resolve(daily(), ISTANBUL, Instant.parse("2026-06-09T06:00:00Z")).orElseThrow());
        assertEquals(P0, OnCallCalculator.resolve(daily(), ISTANBUL, Instant.parse("2026-06-10T06:00:00Z")).orElseThrow());
    }

    @Test
    void dailyDoesNotHandOffBeforeHandoffTime() {
        // 2026-06-09 08:00 +03 is still period 0 (handoff is at 09:00)
        assertEquals(P0, OnCallCalculator.resolve(daily(), ISTANBUL, Instant.parse("2026-06-09T05:00:00Z")).orElseThrow());
    }

    @Test
    void weeklyReturnsCorrectParticipant() {
        // within first week -> P0
        assertEquals(P0, OnCallCalculator.resolve(weekly(), ISTANBUL, Instant.parse("2026-06-14T20:00:00Z")).orElseThrow());
        // next Monday 09:00 +03 -> P1
        assertEquals(P1, OnCallCalculator.resolve(weekly(), ISTANBUL, Instant.parse("2026-06-15T06:00:00Z")).orElseThrow());
    }

    @Test
    void dstSpringForwardKeepsLocalHandoffTime() {
        // Phase 13 T5: the DST transition-instant edge the prior tests skipped (Istanbul has no DST).
        // Berlin springs forward 2026-03-29 02:00->03:00 (a 23h local day). The 09:00 LOCAL handoff must
        // still hold on that day (ChronoUnit.DAYS on ZonedDateTime is DST-aware), not drift by the lost hour.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        var r = new OnCallCalculator.RotationView(
                RotationType.DAILY, LocalDate.of(2026, 3, 27), LocalTime.of(9, 0), List.of(P0, P1));
        // 2026-03-27 P0 (period 0), 03-28 P1 (period 1), 03-29 (DST day) P0 (period 2).
        Instant beforeHandoff = ZonedDateTime.of(2026, 3, 29, 8, 30, 0, 0, berlin).toInstant();
        Instant afterHandoff = ZonedDateTime.of(2026, 3, 29, 9, 30, 0, 0, berlin).toInstant();
        assertEquals(P1, OnCallCalculator.resolve(r, berlin, beforeHandoff).orElseThrow()); // still period 1
        assertEquals(P0, OnCallCalculator.resolve(r, berlin, afterHandoff).orElseThrow());  // period 2 after 09:00 local
    }

    @Test
    void timezoneChangesThePeriod() {
        // Same instant, midnight handoff. In UTC it is still day 0; in Tokyo (+09) it has crossed into day 1.
        var r = new OnCallCalculator.RotationView(
                RotationType.DAILY, LocalDate.of(2026, 6, 8), LocalTime.MIDNIGHT, List.of(P0, P1));
        Instant t = Instant.parse("2026-06-08T16:00:00Z");
        assertEquals(P0, OnCallCalculator.resolve(r, ZoneId.of("UTC"), t).orElseThrow());
        assertEquals(P1, OnCallCalculator.resolve(r, ZoneId.of("Asia/Tokyo"), t).orElseThrow());
    }
}
