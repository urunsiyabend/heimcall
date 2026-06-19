package com.urunsiyabend.heimcall.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEvent;
import com.urunsiyabend.heimcall.integration.domain.RawInboundEventRepository;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Phase 13 T5: the persistence half of ingestion ({@link AlertEventWriter}) writes both the
 * `raw_inbound_event` audit row and the `outbox` row, with the `dedupKey` as the outbox message key
 * (preserving per-aggregate partition order). The `@Transactional` atomicity itself isn't unit-testable;
 * this asserts the two writes happen with the right arguments. Pure Mockito (real Jackson for serialize).
 */
@ExtendWith(MockitoExtension.class)
class AlertEventWriterTest {

    @Mock OutboxAppender outbox;
    @Mock RawInboundEventRepository rawEvents;

    @Test
    void persistsRawAuditRowAndOutboxRow() {
        AlertEventWriter writer = new AlertEventWriter(outbox, rawEvents, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        String dedupKey = "grafana:payment-5xx";
        WebhookRequest request = new WebhookRequest(MessageType.CRITICAL, "payment-5xx", "Payment API",
                "msg", "payments", Severity.CRITICAL, "grafana", Map.of("env", "prod"));
        AlertReceivedEvent event = new AlertReceivedEvent(eventId, Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), "backend-critical", "grafana", MessageType.CRITICAL, "payment-5xx",
                dedupKey, "Payment API", "msg", Severity.CRITICAL, Map.of());

        writer.persist(eventId, "hc_key", "backend-critical", request, dedupKey, event);

        verify(rawEvents).save(org.mockito.ArgumentMatchers.any(RawInboundEvent.class));
        // dedupKey is both the outbox aggregate id AND the message key (per-aggregate order).
        verify(outbox).append(eq("alert"), eq(dedupKey), eq(Topics.ALERT_RECEIVED), eq(dedupKey), eq(event));
    }
}
