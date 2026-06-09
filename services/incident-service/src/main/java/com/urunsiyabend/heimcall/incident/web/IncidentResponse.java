package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.incident.domain.Incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID organizationId,
        String source,
        String dedupKey,
        String title,
        String description,
        Severity severity,
        IncidentStatus status,
        String routingKey,
        UUID serviceId,
        UUID escalationPolicyId,
        Instant createdAt,
        Instant updatedAt,
        Instant lastEventAt
) {
    public static IncidentResponse from(Incident i) {
        return new IncidentResponse(
                i.getId(), i.getOrganizationId(), i.getSource(), i.getDedupKey(),
                i.getTitle(), i.getDescription(), i.getSeverity(), i.getStatus(),
                i.getRoutingKey(), i.getServiceId(), i.getEscalationPolicyId(),
                i.getCreatedAt(), i.getUpdatedAt(), i.getLastEventAt());
    }
}
