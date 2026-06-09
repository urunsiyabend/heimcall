package com.urunsiyabend.heimcall.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by incident-service when an open incident is resolved. Consumed by escalation-service
 * to cancel pending escalation tasks for the incident.
 *
 * @param eventId        unique id for this event (idempotency)
 * @param occurredAt     when the resolution happened
 * @param organizationId tenant boundary
 * @param incidentId     the resolved incident
 */
public record IncidentResolvedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        UUID incidentId
) {
}
