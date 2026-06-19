package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import com.urunsiyabend.heimcall.incident.domain.Alert;
import com.urunsiyabend.heimcall.incident.domain.AlertOccurrenceRepository;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.ProcessedEventRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEvent;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T1: the core {@code Event -> Alert -> Incident} engine ({@link IncidentService}) — message-type
 * mapping, dedup, idempotency, and the routing-decision wiring (UNROUTED / ROUTED_FROM_CACHE). Pure
 * Mockito; the {@link RoutingAvailabilityResolver} is stubbed (its own table is covered separately).
 */
@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String DEDUP = "grafana:payment-5xx";
    private static final String KEY = "backend-critical";

    @Mock
    IncidentRepository incidents;
    @Mock
    TimelineEventRepository timeline;
    @Mock
    AlertRepository alerts;
    @Mock
    AlertOccurrenceRepository occurrences;
    @Mock
    ProcessedEventRepository processedEvents;
    @Mock
    RoutingAvailabilityResolver routing;
    @Mock
    org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks
    IncidentService service;

    private AlertReceivedEvent event(MessageType type) {
        return new AlertReceivedEvent(UUID.randomUUID(), Instant.now(), ORG, UUID.randomUUID(), KEY,
                "grafana", type, "payment-5xx", DEDUP, "Payment API 5xx", "error rate high",
                Severity.CRITICAL, null);
    }

    private Alert openAlertLinkedTo(UUID incidentId) {
        Alert alert = Alert.open(ORG, "grafana", DEDUP, "payment-5xx", Severity.CRITICAL, "Payment API 5xx", Instant.now());
        alert.linkIncident(incidentId);
        return alert;
    }

    private List<String> savedTimelineTypes() {
        ArgumentCaptor<TimelineEvent> captor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(TimelineEvent::getType).toList();
    }

    // ---- mapping: CRITICAL opens an alert + incident, routed ----

    @Test
    void critical_firstSignal_opensIncidentRoutedAndPublishesTriggered() {
        UUID policy = UUID.randomUUID();
        UUID svc = UUID.randomUUID();
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(routing.resolve(ORG, KEY)).thenReturn(RoutingDecision.routed(new Routing(svc, policy, UUID.randomUUID())));

        service.handle(event(MessageType.CRITICAL));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(saved.capture());
        Incident incident = saved.getValue();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(incident.getEscalationPolicyId()).isEqualTo(policy);
        assertThat(incident.getServiceId()).isEqualTo(svc);
        assertThat(incident.isUnrouted()).isFalse();
        assertThat(incident.isRoutedFromCache()).isFalse();
        assertThat(savedTimelineTypes()).containsExactly("TRIGGER");

        ArgumentCaptor<IncidentDomainEvents.Triggered> ev =
                ArgumentCaptor.forClass(IncidentDomainEvents.Triggered.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().escalationPolicyId()).isEqualTo(policy);
        assertThat(ev.getValue().unrouted()).isFalse();
        verify(processedEvents).save(any());
    }

    // ---- dedup: a repeat collapses onto the open alert, no second incident ----

    @Test
    void critical_repeat_dedupsOntoOpenAlert_noNewIncident() {
        UUID incidentId = UUID.randomUUID();
        Alert open = openAlertLinkedTo(incidentId);
        Incident existing = Incident.trigger(ORG, "grafana", DEDUP, "payment-5xx", "Payment API 5xx",
                "d", Severity.CRITICAL, KEY, Instant.now());
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.of(open));
        when(incidents.findById(incidentId)).thenReturn(Optional.of(existing));

        service.handle(event(MessageType.CRITICAL));

        assertThat(open.getOccurrenceCount()).isEqualTo(2);
        assertThat(savedTimelineTypes()).containsExactly("DUPLICATE");
        // No new incident is opened and no fresh TRIGGER is published.
        verify(events, never()).publishEvent(any(IncidentDomainEvents.Triggered.class));
        verify(routing, never()).resolve(any(), any());
    }

    // ---- RECOVERY closes the alert and resolves its incident ----

    @Test
    void recovery_closesAlertAndResolvesIncident() {
        UUID incidentId = UUID.randomUUID();
        Alert open = openAlertLinkedTo(incidentId);
        Incident triggered = Incident.trigger(ORG, "grafana", DEDUP, "payment-5xx", "Payment API 5xx",
                "d", Severity.CRITICAL, KEY, Instant.now());
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.of(open));
        when(incidents.findById(incidentId)).thenReturn(Optional.of(triggered));

        service.handle(event(MessageType.RECOVERY));

        assertThat(open.getStatus()).isEqualTo(AlertStatus.CLOSED);
        assertThat(triggered.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(savedTimelineTypes()).containsExactly("RESOLVE");
        verify(events).publishEvent(any(IncidentDomainEvents.Resolved.class));
    }

    // ---- ACKNOWLEDGEMENT acknowledges the TRIGGERED incident ----

    @Test
    void acknowledgement_acknowledgesTriggeredIncident() {
        UUID incidentId = UUID.randomUUID();
        Alert open = openAlertLinkedTo(incidentId);
        Incident triggered = Incident.trigger(ORG, "grafana", DEDUP, "payment-5xx", "Payment API 5xx",
                "d", Severity.CRITICAL, KEY, Instant.now());
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.of(open));
        when(incidents.findById(incidentId)).thenReturn(Optional.of(triggered));

        service.handle(event(MessageType.ACKNOWLEDGEMENT));

        assertThat(open.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(triggered.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        verify(events).publishEvent(any(IncidentDomainEvents.Acknowledged.class));
    }

    // ---- INFO records a non-actionable alert, never an incident ----

    @Test
    void info_recordsAlertWithoutIncident() {
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.empty());

        service.handle(event(MessageType.INFO));

        verify(alerts).save(any(Alert.class));
        verify(incidents, never()).save(any());
        verify(routing, never()).resolve(any(), any());
        verifyNoInteractions(timeline);
    }

    // ---- idempotency: an already-processed event is a no-op ----

    @Test
    void redeliveredEvent_isNoOp() {
        AlertReceivedEvent e = event(MessageType.CRITICAL);
        when(processedEvents.existsById(e.eventId())).thenReturn(true);

        service.handle(e);

        verify(alerts, never()).findFirstByOrganizationIdAndDedupKeyAndStatus(any(), any(), any());
        verify(incidents, never()).save(any());
        verify(events, never()).publishEvent(any());
        verify(processedEvents, never()).save(any());
    }

    // ---- no-match: deliberate, observable UNROUTED (Phase 10 T3) ----

    @Test
    void critical_noMatch_marksUnroutedAndPublishesUnroutedTriggered() {
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(routing.resolve(ORG, KEY)).thenReturn(RoutingDecision.noMatch());

        service.handle(event(MessageType.CRITICAL));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(saved.capture());
        assertThat(saved.getValue().isUnrouted()).isTrue();
        assertThat(saved.getValue().getEscalationPolicyId()).isNull();
        assertThat(savedTimelineTypes()).containsExactly("TRIGGER", "UNROUTED");

        ArgumentCaptor<IncidentDomainEvents.Triggered> ev =
                ArgumentCaptor.forClass(IncidentDomainEvents.Triggered.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().unrouted()).isTrue();
        assertThat(ev.getValue().escalationPolicyId()).isNull();
    }

    // ---- degraded page from last-known-good cache (Phase 10 T4) ----

    @Test
    void critical_outageFromCache_marksRoutedFromCache() {
        UUID policy = UUID.randomUUID();
        when(alerts.findFirstByOrganizationIdAndDedupKeyAndStatus(ORG, DEDUP, AlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(routing.resolve(ORG, KEY))
                .thenReturn(RoutingDecision.fromCache(new Routing(UUID.randomUUID(), policy, UUID.randomUUID())));

        service.handle(event(MessageType.CRITICAL));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(saved.capture());
        assertThat(saved.getValue().isRoutedFromCache()).isTrue();
        assertThat(saved.getValue().getEscalationPolicyId()).isEqualTo(policy);
        assertThat(savedTimelineTypes()).containsExactly("TRIGGER", "ROUTED_FROM_CACHE");
    }
}
