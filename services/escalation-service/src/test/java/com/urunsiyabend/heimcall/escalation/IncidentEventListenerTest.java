package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.IncidentAcknowledgedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentCanceledEvent;
import com.urunsiyabend.heimcall.common.events.IncidentResolvedEvent;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The single-topic lifecycle listener (Phase 12) routes each {@code @KafkaHandler} payload type to the
 * right {@link EscalationService} call. Type-based dispatch is exercised by invoking the handlers
 * directly with each event type.
 */
class IncidentEventListenerTest {

    private final EscalationService service = mock(EscalationService.class);
    private final IncidentEventListener listener = new IncidentEventListener(service);

    @Test
    void triggeredSchedulesEscalation() {
        IncidentTriggeredEvent e = new IncidentTriggeredEvent(UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID(), "grafana:x", "boom", Severity.CRITICAL, UUID.randomUUID());
        listener.onTriggered(e);
        verify(service).onIncidentTriggered(e);
        verifyNoMoreInteractions(service);
    }

    @Test
    void acknowledgedCancelsWithAckReason() {
        UUID eventId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        listener.onAcknowledged(new IncidentAcknowledgedEvent(eventId, Instant.now(), UUID.randomUUID(), incidentId));
        verify(service).onIncidentClosed(eventId, incidentId, "ACK");
        verifyNoMoreInteractions(service);
    }

    @Test
    void resolvedCancelsWithResolveReason() {
        UUID eventId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        listener.onResolved(new IncidentResolvedEvent(eventId, Instant.now(), UUID.randomUUID(), incidentId));
        verify(service).onIncidentClosed(eventId, incidentId, "RESOLVE");
        verifyNoMoreInteractions(service);
    }

    @Test
    void canceledCancelsWithCancelReason() {
        UUID eventId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        listener.onCanceled(new IncidentCanceledEvent(eventId, Instant.now(), UUID.randomUUID(), incidentId));
        verify(service).onIncidentClosed(eventId, incidentId, "CANCEL");
        verifyNoMoreInteractions(service);
    }

    @Test
    void unknownPayloadIsIgnored() {
        listener.onUnknown("some-unexpected-string");
        verifyNoMoreInteractions(service);
    }
}
