package com.urunsiyabend.heimcall.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEvent;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEventRepository;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence half of ingestion (Phase 9 T3 atomicity, split out in Phase 10).
 *
 * <p>Writes the {@code raw_inbound_event} audit row and the {@code outbox} row in one transaction: a
 * rollback writes neither (no ghost), a commit durably accepts the event (never lost), and the relay
 * publishes to Kafka asynchronously. The {@code dedupKey} is the outbox message key, preserving
 * per-aggregate partition order.
 *
 * <p>Separated from {@link AlertNormalizer} so the synchronous identity-service resolve runs OUTSIDE this
 * transaction — a self-invocation would not cross the Spring proxy, so the tx boundary lives on this bean.
 * The transaction now spans only the two DB writes, holding a pooled connection for milliseconds, not for
 * the duration of the upstream network call.
 */
@Component
public class AlertEventWriter {

    private static final String AGGREGATE_TYPE = "alert";

    private final OutboxAppender outbox;
    private final RawInboundEventRepository rawEvents;
    private final ObjectMapper objectMapper;

    public AlertEventWriter(OutboxAppender outbox, RawInboundEventRepository rawEvents, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.rawEvents = rawEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persist(UUID eventId, String integrationKey, String routingKey, WebhookRequest request,
                        String dedupKey, AlertReceivedEvent event) {
        // Raw inbound payload (audit + replay trace of what arrived at the door).
        rawEvents.save(RawInboundEvent.received(
                eventId, integrationKey, routingKey, request.source(), dedupKey,
                serialize(request), Instant.now()));

        // Same tx as the audit row: rolls back together (no ghost), commits together (durably accepted).
        outbox.append(AGGREGATE_TYPE, dedupKey, Topics.ALERT_RECEIVED, dedupKey, event);
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
