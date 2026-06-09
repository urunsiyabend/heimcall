package com.urunsiyabend.heimcall.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by incident-service when an open incident is canceled by an operator. Consumed by
 * escalation-service to cancel pending escalation tasks for the incident (same handling as resolve).
 *
 * @param eventId        unique id for this event (idempotency)
 * @param occurredAt     when the cancellation happened
 * @param organizationId tenant boundary
 * @param incidentId     the canceled incident
 */
public record IncidentCanceledEvent(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        UUID incidentId
) {
}
