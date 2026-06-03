package com.urunsiyabend.heimcall.integration;

import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a raw {@link WebhookRequest} into a normalized {@link AlertReceivedEvent}
 * and publishes it to Kafka. The dedup key collapses repeated signals from the same
 * source + entity onto one incident downstream.
 */
@Service
public class AlertNormalizer {

    // Sprint 1 placeholder tenant. Replaced once identity-service resolves the
    // integration key to a real organization.
    private static final UUID DEV_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AlertNormalizer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public UUID normalizeAndPublish(String integrationKey, String routingKey, WebhookRequest request) {
        UUID eventId = UUID.randomUUID();
        String dedupKey = request.source() + ":" + request.entityId();

        AlertReceivedEvent event = new AlertReceivedEvent(
                eventId,
                Instant.now(),
                DEV_ORGANIZATION_ID,
                deriveIntegrationId(integrationKey),
                routingKey,
                request.source(),
                request.messageType(),
                request.entityId(),
                dedupKey,
                request.entityDisplayName() != null ? request.entityDisplayName() : request.entityId(),
                request.stateMessage(),
                request.severity(),
                request.metadata() != null ? request.metadata() : Map.of()
        );

        kafkaTemplate.send(Topics.ALERT_RECEIVED, dedupKey, event);
        return eventId;
    }

    // Sprint 1: deterministically derive a stable id from the key so repeated calls
    // with the same key map to the same integration id.
    private UUID deriveIntegrationId(String integrationKey) {
        return UUID.nameUUIDFromBytes(integrationKey.getBytes());
    }
}
