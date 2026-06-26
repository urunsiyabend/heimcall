package com.urunsiyabend.heimcall.common.events;

import com.urunsiyabend.heimcall.common.domain.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by escalation-service when an escalation task fires and a target is resolved to a
 * concrete user. Consumed (Phase 6) by notification-service to deliver the message.
 *
 * @param eventId            unique id for this event (idempotency)
 * @param requestedAt        when the escalation task fired
 * @param organizationId     tenant boundary
 * @param incidentId         incident being escalated
 * @param escalationPolicyId policy that produced this request
 * @param level              escalation level that fired (1-based)
 * @param targetUserId       resolved recipient
 * @param targetSource       how the recipient was resolved: USER, SCHEDULE, or TEAM
 * @param title              incident title (notification subject)
 * @param severity           incident severity
 * @param alertOccurredAt    pipeline origin — the originating alert's occurredAt, threaded through
 *                           incident (IncidentTriggeredEvent.occurredAt) and escalation
 *                           (EscalationIncident.triggeredAt) so notification-service can record true
 *                           end-to-end alert→delivered latency (Phase 19 T5). Nullable: an in-flight
 *                           pre-T5 message has none, and the e2e timer skips it.
 */
public record NotificationRequestedEvent(
        UUID eventId,
        Instant requestedAt,
        UUID organizationId,
        UUID incidentId,
        UUID escalationPolicyId,
        int level,
        UUID targetUserId,
        String targetSource,
        String title,
        Severity severity,
        Instant alertOccurredAt
) {
}
