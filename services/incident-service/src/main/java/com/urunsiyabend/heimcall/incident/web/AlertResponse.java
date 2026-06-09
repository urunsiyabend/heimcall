package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.incident.domain.Alert;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID organizationId,
        UUID incidentId,
        String source,
        String dedupKey,
        String externalEntityId,
        AlertStatus status,
        Severity severity,
        String title,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
    public static AlertResponse from(Alert a) {
        return new AlertResponse(
                a.getId(), a.getOrganizationId(), a.getIncidentId(), a.getSource(), a.getDedupKey(),
                a.getExternalEntityId(), a.getStatus(), a.getSeverity(), a.getTitle(),
                a.getOccurrenceCount(), a.getFirstSeenAt(), a.getLastSeenAt());
    }
}
