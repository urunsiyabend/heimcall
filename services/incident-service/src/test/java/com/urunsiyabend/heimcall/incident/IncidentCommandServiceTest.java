package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.incident.domain.Alert;
import com.urunsiyabend.heimcall.incident.domain.AlertRepository;
import com.urunsiyabend.heimcall.incident.domain.Incident;
import com.urunsiyabend.heimcall.incident.domain.IncidentRepository;
import com.urunsiyabend.heimcall.incident.domain.TimelineEvent;
import com.urunsiyabend.heimcall.incident.domain.TimelineEventRepository;
import com.urunsiyabend.heimcall.incident.web.ApiExceptions;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T1: operator lifecycle commands ({@link IncidentCommandService}) — transition guards,
 * idempotency, timeline + domain-event side-effects, and member authorization. Pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
class IncidentCommandServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    @Mock
    IncidentRepository incidents;
    @Mock
    TimelineEventRepository timeline;
    @Mock
    AlertRepository alerts;
    @Mock
    IdentityClient identity;
    @Mock
    org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks
    IncidentCommandService service;

    private Incident incidentInState(IncidentStatus status) {
        Incident incident = Incident.trigger(ORG, "grafana", "grafana:x", "x", "API down",
                "desc", Severity.CRITICAL, "backend-critical", Instant.now());
        switch (status) {
            case ACKNOWLEDGED -> incident.acknowledge(Instant.now());
            case RESOLVED -> incident.resolve(Instant.now());
            case CANCELED -> incident.cancel(Instant.now());
            case TRIGGERED -> { /* already */ }
        }
        return incident;
    }

    @Test
    void acknowledge_fromTriggered_transitionsTimelineEventAndSyncsAlert() {
        Incident incident = incidentInState(IncidentStatus.TRIGGERED);
        UUID id = incident.getId();
        when(incidents.findById(id)).thenReturn(Optional.of(incident));
        Alert open = Alert.open(ORG, "grafana", "grafana:x", "x", Severity.CRITICAL, "API down", Instant.now());
        when(alerts.findByIncidentIdOrderByFirstSeenAtAsc(id)).thenReturn(List.of(open));

        service.acknowledge(id, CALLER);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        verify(identity).requireMember(ORG, CALLER);
        verify(incidents).save(incident);
        // Linked OPEN alert follows the incident into ACKNOWLEDGED.
        assertThat(open.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        ArgumentCaptor<TimelineEvent> ev = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline).save(ev.capture());
        assertThat(ev.getValue().getType()).isEqualTo("ACK");
        verify(events).publishEvent(any(IncidentDomainEvents.Acknowledged.class));
    }

    @Test
    void acknowledge_whenAlreadyAcknowledged_isIdempotentNoOp() {
        Incident incident = incidentInState(IncidentStatus.ACKNOWLEDGED);
        UUID id = incident.getId();
        when(incidents.findById(id)).thenReturn(Optional.of(incident));

        service.acknowledge(id, CALLER);

        // No second transition: nothing saved, no timeline, no event, no alert sync.
        verify(incidents, never()).save(any());
        verifyNoInteractions(timeline);
        verify(events, never()).publishEvent(any());
        verify(alerts, never()).findByIncidentIdOrderByFirstSeenAtAsc(any());
    }

    @Test
    void acknowledge_fromResolved_isIllegalTransitionConflict() {
        Incident incident = incidentInState(IncidentStatus.RESOLVED);
        UUID id = incident.getId();
        when(incidents.findById(id)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service.acknowledge(id, CALLER))
                .isInstanceOf(ApiExceptions.ConflictException.class);
        verify(incidents, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void resolve_fromAcknowledged_transitionsAndPublishes() {
        Incident incident = incidentInState(IncidentStatus.ACKNOWLEDGED);
        UUID id = incident.getId();
        when(incidents.findById(id)).thenReturn(Optional.of(incident));
        when(alerts.findByIncidentIdOrderByFirstSeenAtAsc(id)).thenReturn(List.of());

        service.resolve(id, CALLER);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        verify(events).publishEvent(any(IncidentDomainEvents.Resolved.class));
    }

    @Test
    void cancel_fromResolved_isIllegalTransitionConflict() {
        Incident incident = incidentInState(IncidentStatus.RESOLVED);
        UUID id = incident.getId();
        when(incidents.findById(id)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service.cancel(id, CALLER))
                .isInstanceOf(ApiExceptions.ConflictException.class);
    }

    @Test
    void command_onMissingIncident_isNotFound() {
        UUID id = UUID.randomUUID();
        when(incidents.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(id, CALLER))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
        // Authorization is only attempted once the incident exists.
        verifyNoInteractions(identity);
    }
}
