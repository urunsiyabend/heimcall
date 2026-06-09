package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrence;

import java.time.Instant;
import java.util.UUID;

public record AlertOccurrenceResponse(
        UUID id,
        UUID alertId,
        UUID eventId,
        MessageType messageType,
        Severity severity,
        String title,
        String description,
        Instant occurredAt,
        Instant receivedAt
) {
    public static AlertOccurrenceResponse from(AlertOccurrence o) {
        return new AlertOccurrenceResponse(
                o.getId(), o.getAlertId(), o.getEventId(), o.getMessageType(), o.getSeverity(),
                o.getTitle(), o.getDescription(), o.getOccurredAt(), o.getReceivedAt());
    }
}
