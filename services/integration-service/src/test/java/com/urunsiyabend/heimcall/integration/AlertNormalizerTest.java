package com.urunsiyabend.heimcall.integration;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T5: inbound payload normalization ({@link AlertNormalizer}) — the resolved tenant + the
 * `dedupKey = source:entityId` correlation handle stamped onto the {@link AlertReceivedEvent}, and the
 * resolve-before-persist ordering (an invalid key stores nothing). Pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AlertNormalizerTest {

    @Mock IdentityClient identityClient;
    @Mock AlertEventWriter writer;
    @InjectMocks AlertNormalizer normalizer;

    private WebhookRequest request(String displayName) {
        return new WebhookRequest(MessageType.CRITICAL, "payment-5xx", displayName, "error rate high",
                "payments", Severity.CRITICAL, "grafana", Map.of("env", "prod"));
    }

    @Test
    void normalizesTenantAndDedupKey_thenPersists() {
        UUID org = UUID.randomUUID();
        UUID integration = UUID.randomUUID();
        when(identityClient.resolve("hc_key")).thenReturn(new IdentityClient.Resolution(org, integration, "grafana-prod"));

        AlertNormalizer.Accepted accepted = normalizer.normalizeAndPublish("hc_key", "backend-critical", request("Payment API"));

        assertThat(accepted.dedupKey()).isEqualTo("grafana:payment-5xx");
        ArgumentCaptor<AlertReceivedEvent> ev = ArgumentCaptor.forClass(AlertReceivedEvent.class);
        verify(writer).persist(any(), any(), any(), any(), any(), ev.capture());
        AlertReceivedEvent e = ev.getValue();
        assertThat(e.organizationId()).isEqualTo(org);
        assertThat(e.integrationId()).isEqualTo(integration);
        assertThat(e.routingKey()).isEqualTo("backend-critical");
        assertThat(e.dedupKey()).isEqualTo("grafana:payment-5xx");
        assertThat(e.source()).isEqualTo("grafana");
        assertThat(e.messageType()).isEqualTo(MessageType.CRITICAL);
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.externalEntityId()).isEqualTo("payment-5xx");
        assertThat(e.title()).isEqualTo("Payment API"); // entityDisplayName
        assertThat(e.eventId()).isEqualTo(accepted.eventId());
    }

    @Test
    void titleFallsBackToEntityId_whenNoDisplayName() {
        when(identityClient.resolve(any())).thenReturn(new IdentityClient.Resolution(UUID.randomUUID(), UUID.randomUUID(), "n"));

        normalizer.normalizeAndPublish("hc_key", "rk", request(null));

        ArgumentCaptor<AlertReceivedEvent> ev = ArgumentCaptor.forClass(AlertReceivedEvent.class);
        verify(writer).persist(any(), any(), any(), any(), any(), ev.capture());
        assertThat(ev.getValue().title()).isEqualTo("payment-5xx");
    }

    @Test
    void invalidKey_rejectedBeforeAnythingIsStored() {
        when(identityClient.resolve("bad")).thenThrow(new InvalidIntegrationKeyException("unknown key"));

        assertThatThrownBy(() -> normalizer.normalizeAndPublish("bad", "rk", request("x")))
                .isInstanceOf(InvalidIntegrationKeyException.class);
        verifyNoInteractions(writer);
    }
}
