package com.urunsiyabend.heimcall.common.events;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published by integration-service after a raw inbound webhook payload is normalized.
 * Consumed by incident-service to drive the incident lifecycle.
 *
 * @param eventId          unique id for this event (idempotency / dedup of the event itself)
 * @param occurredAt       when the source signal occurred
 * @param organizationId   tenant boundary
 * @param integrationId    integration the payload arrived through
 * @param routingKey       routing key used to direct the payload to a service/team/policy
 * @param source           originating system, e.g. "grafana"
 * @param messageType      CRITICAL / WARNING / INFO / ACKNOWLEDGEMENT / RECOVERY
 * @param externalEntityId provider-side entity id
 * @param dedupKey         key used to collapse repeated signals onto one incident
 * @param title            short human-readable title
 * @param description      longer state message
 * @param severity         alert severity
 * @param metadata         free-form provider metadata
 */
public record AlertReceivedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        UUID integrationId,
        String routingKey,
        String source,
        MessageType messageType,
        String externalEntityId,
        String dedupKey,
        String title,
        String description,
        Severity severity,
        Map<String, String> metadata
) {
}
