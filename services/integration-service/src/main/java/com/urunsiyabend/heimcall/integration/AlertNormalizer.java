package com.urunsiyabend.heimcall.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEvent;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEventRepository;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a raw {@link WebhookRequest} into a normalized {@link AlertReceivedEvent}
 * and hands it to the transactional outbox. The dedup key collapses repeated signals from the same
 * source + entity onto one incident downstream and is returned to the caller as the correlation handle.
 *
 * <p>Reliability (Phase 9 T3): the raw payload audit row and the normalized event are written in one
 * transaction — the {@code outbox} INSERT joins the same tx as the {@code raw_inbound_event} row, so a
 * rollback writes neither (no ghost) and a commit durably accepts the event (never lost). The
 * {@link OutboxRelay} publishes the row to Kafka asynchronously with confirm; ingestion no longer blocks
 * on the broker. A 202 therefore means "durably accepted", not "published to Kafka".
 *
 * <p>Phase 1a: the integration key is resolved against identity-service to obtain the real
 * organization + integration id. An invalid key is rejected (401) before anything is stored.
 */
@Service
public class AlertNormalizer {

    private static final String AGGREGATE_TYPE = "alert";

    private final OutboxAppender outbox;
    private final RawInboundEventRepository rawEvents;
    private final ObjectMapper objectMapper;
    private final IdentityClient identityClient;

    public AlertNormalizer(OutboxAppender outbox,
                           RawInboundEventRepository rawEvents,
                           ObjectMapper objectMapper,
                           IdentityClient identityClient) {
        this.outbox = outbox;
        this.rawEvents = rawEvents;
        this.objectMapper = objectMapper;
        this.identityClient = identityClient;
    }

    /** Outcome of a successful ingestion: the per-request event id plus the alert correlation key. */
    public record Accepted(UUID eventId, String dedupKey) {
    }

    @Transactional
    public Accepted normalizeAndPublish(String integrationKey, String routingKey, WebhookRequest request) {
        // Validate + resolve the key first; an invalid key is rejected (401) before we store anything.
        IdentityClient.Resolution tenant = identityClient.resolve(integrationKey);

        UUID eventId = UUID.randomUUID();
        String dedupKey = request.source() + ":" + request.entityId();

        // Persist the raw inbound payload (audit + replay trace of what arrived at the door).
        rawEvents.save(RawInboundEvent.received(
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

        // Same tx as the audit row: rolls back together (no ghost), commits together (durably accepted).
        // The relay publishes to Kafka later with confirm. dedupKey is the key, preserving partition order.
        outbox.append(AGGREGATE_TYPE, dedupKey, Topics.ALERT_RECEIVED, dedupKey, event);

        return new Accepted(eventId, dedupKey);
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
