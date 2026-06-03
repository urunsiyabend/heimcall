package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.incident.domain.TimelineEvent;

import java.time.Instant;
import java.util.UUID;

public record TimelineEventResponse(
        UUID id,
        UUID incidentId,
        String type,
        String message,
        Instant createdAt
) {
    public static TimelineEventResponse from(TimelineEvent e) {
        return new TimelineEventResponse(e.getId(), e.getIncidentId(), e.getType(), e.getMessage(), e.getCreatedAt());
    }
}
