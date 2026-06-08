package com.urunsiyabend.heimcall.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEvent;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEventRepository;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a raw {@link WebhookRequest} into a normalized {@link AlertReceivedEvent}
 * and publishes it to Kafka. The dedup key collapses repeated signals from the same
 * source + entity onto one incident downstream.
 *
 * <p>Reliability (Phase 3.5): the raw payload is persisted before publishing, the send is
 * confirmed synchronously (acks=all), and a failed publish is surfaced to the caller via
 * {@link EventPublishException} instead of being silently dropped.
 *
 * <p>Phase 1a: the integration key is resolved against identity-service to obtain the real
 * organization + integration id, replacing the Sprint 1 dev placeholder. An invalid key is
 * rejected before anything is stored or published.
 */
@Service
public class AlertNormalizer {

    private static final Logger log = LoggerFactory.getLogger(AlertNormalizer.class);

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RawInboundEventRepository rawEvents;
    private final ObjectMapper objectMapper;
    private final IdentityClient identityClient;

    public AlertNormalizer(KafkaTemplate<String, Object> kafkaTemplate,
                           RawInboundEventRepository rawEvents,
                           ObjectMapper objectMapper,
                           IdentityClient identityClient) {
        this.kafkaTemplate = kafkaTemplate;
        this.rawEvents = rawEvents;
        this.objectMapper = objectMapper;
        this.identityClient = identityClient;
    }

    public UUID normalizeAndPublish(String integrationKey, String routingKey, WebhookRequest request) {
        // Validate + resolve the key first; an invalid key is rejected (401) before we store or publish.
        IdentityClient.Resolution tenant = identityClient.resolve(integrationKey);

        UUID eventId = UUID.randomUUID();
        String dedupKey = request.source() + ":" + request.entityId();

        // Persist the raw payload first so a publish failure leaves a durable, replayable trace.
        RawInboundEvent raw = rawEvents.save(RawInboundEvent.received(
                eventId, integrationKey, routingKey, request.source(), dedupKey,
                serialize(request), Instant.now()));

        AlertReceivedEvent event = new AlertReceivedEvent(
                eventId,
                Instant.now(),
                tenant.organizationId(),
                tenant.integrationId(),
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

        try {
            // Block until the broker acknowledges the write (acks=all). No silent loss.
            kafkaTemplate.send(Topics.ALERT_RECEIVED, dedupKey, event)
                    .get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            raw.markPublished(Instant.now());
            rawEvents.save(raw);
            return eventId;
        } catch (ExecutionException | TimeoutException e) {
            raw.markFailed(e.getMessage());
            rawEvents.save(raw);
            log.error("Failed to publish {} for dedupKey={}, rawEventId={}",
                    Topics.ALERT_RECEIVED, dedupKey, raw.getId(), e);
            throw new EventPublishException("Could not publish alert to Kafka", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            raw.markFailed("interrupted");
            rawEvents.save(raw);
            throw new EventPublishException("Interrupted while publishing alert to Kafka", e);
        }
    }

    private String serialize(WebhookRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            // Should not happen for a validated record; store a marker rather than failing ingestion.
            return "{\"_serializationError\":\"" + e.getMessage() + "\"}";
        }
    }
}
