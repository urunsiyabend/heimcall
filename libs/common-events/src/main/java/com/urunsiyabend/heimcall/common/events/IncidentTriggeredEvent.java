package com.urunsiyabend.heimcall.common.events;

import com.urunsiyabend.heimcall.common.domain.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by incident-service when a new incident is triggered. Consumed by escalation-service
 * to start the escalation engine for the incident.
 *
 * @param eventId             unique id for this event (idempotency)
 * @param occurredAt          when the incident was triggered
 * @param organizationId      tenant boundary
 * @param incidentId          the triggered incident
 * @param dedupKey            incident dedup key
 * @param title               incident title
 * @param severity            incident severity
 * @param escalationPolicyId  resolved policy to run, or {@code null} if no routing match (no escalation)
 */
public record IncidentTriggeredEvent(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        UUID incidentId,
        String dedupKey,
        String title,
        Severity severity,
        UUID escalationPolicyId
) {
}
