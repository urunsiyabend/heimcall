package com.urunsiyabend.heimcall.routing;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * An optional time-of-day restriction on a rule (Phase 17): the rule matches only on the given
 * days-of-week and within the given local-time window, evaluated in the ruleset's timezone (see
 * {@link Ruleset#timezone()}, DST-aware via {@link ZonedDateTime}).
 *
 * <ul>
 *   <li>{@code days} empty (or null) means every day.</li>
 *   <li>{@code start == end} means the whole day.</li>
 *   <li>{@code start < end} is a same-day window {@code [start, end)}.</li>
 *   <li>{@code start > end} spans midnight (e.g. 22:00–06:00): match {@code time >= start || time < end}.</li>
 * </ul>
 * The day-of-week check is applied to the local day of the evaluation instant.
 */
public record TimeRestriction(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

    public TimeRestriction {
        if (start == null || end == null) {
            throw new IllegalArgumentException("time restriction requires start and end");
        }
        days = days == null ? Set.of() : Set.copyOf(days);
    }

    public boolean matches(ZonedDateTime zdt) {
        if (!days.isEmpty() && !days.contains(zdt.getDayOfWeek())) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }
}
