package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationRequestedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.escalation.domain.EscalationIncidentRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicy;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicyRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRule;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTarget;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTargetRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationTask;
import com.urunsiyabend.heimcall.escalation.domain.EscalationTaskRepository;
import com.urunsiyabend.heimcall.escalation.domain.ProcessedEventRepository;
import com.urunsiyabend.heimcall.escalation.domain.TargetType;
import com.urunsiyabend.heimcall.escalation.domain.TaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T3: the escalation engine ({@link EscalationService}) — task materialization + repeat math,
 * the scheduling guards, cancel-on-close + idempotency, and {@code fireDueTask} target resolution. Pure
 * Mockito (a real {@link SimpleMeterRegistry} for the counter); the lock-safe claim itself is proven
 * against a real PostgreSQL in {@code EscalationTaskClaimTest}.
 */
@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();

    @Mock EscalationPolicyRepository policies;
    @Mock EscalationRuleRepository rules;
    @Mock EscalationRuleTargetRepository targets;
    @Mock EscalationTaskRepository tasks;
    @Mock EscalationIncidentRepository incidents;
    @Mock ProcessedEventRepository processedEvents;
    @Mock IdentityClient identity;
    @Mock ScheduleClient schedule;
    @Mock OutboxAppender outbox;

    private EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(policies, rules, targets, tasks, incidents, processedEvents,
                identity, schedule, outbox, new SimpleMeterRegistry());
    }

    private IncidentTriggeredEvent triggered(UUID policyId, Instant at) {
        return new IncidentTriggeredEvent(UUID.randomUUID(), at, ORG, INCIDENT, "grafana:x", "boom",
                Severity.CRITICAL, policyId);
    }

    private EscalationRule rule(int level, int delaySeconds) {
        EscalationRule r = mock(EscalationRule.class);
        when(r.getId()).thenReturn(UUID.randomUUID());
        when(r.getLevel()).thenReturn(level);
        when(r.getDelaySeconds()).thenReturn(delaySeconds);
        return r;
    }

    // ---- materialization: one task per level per repeat round, at triggeredAt + round*span + delay ----

    @Test
    void onIncidentTriggered_materializesLevelsTimesRounds_withRepeatOffsets() {
        Instant triggeredAt = Instant.parse("2026-06-19T12:00:00Z");
        EscalationPolicy policy = mock(EscalationPolicy.class);
        when(policy.getId()).thenReturn(POLICY);
        when(policy.getRepeatCount()).thenReturn(1); // -> 2 rounds
        when(policies.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.of(policy));
        EscalationRule level1 = rule(1, 0);
        EscalationRule level2 = rule(2, 300);
        when(rules.findByPolicyIdOrderByLevelAsc(POLICY)).thenReturn(List.of(level1, level2));

        service.onIncidentTriggered(triggered(POLICY, triggeredAt));

        ArgumentCaptor<EscalationTask> saved = ArgumentCaptor.forClass(EscalationTask.class);
        verify(tasks, times(4)).save(saved.capture());
        // roundSpan = max(0,300) = 300. round0: +0,+300 ; round1: +300,+600.
        assertThat(saved.getAllValues())
                .extracting(t -> t.getScheduledAt().getEpochSecond() - triggeredAt.getEpochSecond())
                .containsExactly(0L, 300L, 300L, 600L);
        assertThat(saved.getAllValues()).allMatch(t -> t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void onIncidentTriggered_nullPolicy_schedulesNothing() {
        service.onIncidentTriggered(triggered(null, Instant.now()));
        verify(tasks, never()).save(any());
        verifyNoInteractions(policies);
    }

    @Test
    void onIncidentTriggered_whenTasksAlreadyExist_skips() {
        when(tasks.existsByIncidentId(INCIDENT)).thenReturn(true);
        service.onIncidentTriggered(triggered(POLICY, Instant.now()));
        verify(tasks, never()).save(any());
        verifyNoInteractions(policies);
    }

    @Test
    void onIncidentTriggered_policyNotFound_schedulesNothing() {
        when(policies.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.empty());
        service.onIncidentTriggered(triggered(POLICY, Instant.now()));
        verify(tasks, never()).save(any());
    }

    @Test
    void onIncidentTriggered_policyWithNoRules_schedulesNothing() {
        EscalationPolicy policy = mock(EscalationPolicy.class);
        when(policies.findByIdAndOrganizationId(POLICY, ORG)).thenReturn(Optional.of(policy));
        when(policy.getId()).thenReturn(POLICY);
        when(rules.findByPolicyIdOrderByLevelAsc(POLICY)).thenReturn(List.of());
        service.onIncidentTriggered(triggered(POLICY, Instant.now()));
        verify(tasks, never()).save(any());
        verify(incidents, never()).save(any());
    }

    @Test
    void onIncidentTriggered_idempotentOnEventId() {
        IncidentTriggeredEvent e = triggered(POLICY, Instant.now());
        when(processedEvents.existsById(e.eventId())).thenReturn(true);
        service.onIncidentTriggered(e);
        verify(tasks, never()).save(any());
        verifyNoInteractions(policies);
    }

    // ---- cancel-on-close: pending tasks canceled; idempotent ----

    @Test
    void onIncidentClosed_cancelsPendingTasks() {
        UUID eventId = UUID.randomUUID();
        EscalationTask t1 = EscalationTask.pending(ORG, INCIDENT, POLICY, UUID.randomUUID(), 1, 0, Instant.now(), Instant.now());
        EscalationTask t2 = EscalationTask.pending(ORG, INCIDENT, POLICY, UUID.randomUUID(), 2, 0, Instant.now(), Instant.now());
        when(tasks.findByIncidentIdAndStatus(INCIDENT, TaskStatus.PENDING)).thenReturn(List.of(t1, t2));

        service.onIncidentClosed(eventId, INCIDENT, "ACK");

        assertThat(t1.getStatus()).isEqualTo(TaskStatus.CANCELED);
        assertThat(t2.getStatus()).isEqualTo(TaskStatus.CANCELED);
        verify(tasks).saveAll(List.of(t1, t2));
    }

    @Test
    void onIncidentClosed_idempotentOnEventId() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(true);
        service.onIncidentClosed(eventId, INCIDENT, "ACK");
        verify(tasks, never()).findByIncidentIdAndStatus(any(), any());
        verify(tasks, never()).saveAll(any());
    }

    // ---- fireDueTask: claim, resolve targets, request one notification per recipient, mark executed ----

    private EscalationTask pendingTask(UUID ruleId) {
        return EscalationTask.pending(ORG, INCIDENT, POLICY, ruleId, 1, 0, Instant.now(), Instant.now());
    }

    @Test
    void fireDueTask_userTarget_requestsOneNotification_andMarksExecuted() {
        UUID ruleId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        EscalationTask task = pendingTask(ruleId);
        when(tasks.findPendingForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(incidents.findById(INCIDENT)).thenReturn(Optional.empty());
        EscalationRuleTarget target = mock(EscalationRuleTarget.class);
        when(target.getTargetType()).thenReturn(TargetType.USER);
        when(target.getTargetId()).thenReturn(user);
        when(targets.findByRuleId(ruleId)).thenReturn(List.of(target));

        service.fireDueTask(task.getId());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq("escalation"), eq(INCIDENT.toString()), eq(Topics.NOTIFICATION_REQUESTED),
                eq(INCIDENT.toString()), payload.capture());
        assertThat(((NotificationRequestedEvent) payload.getValue()).targetUserId()).isEqualTo(user);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTED);
        verify(tasks).save(task);
    }

    @Test
    void fireDueTask_claimEmpty_doesNothing() {
        UUID id = UUID.randomUUID();
        when(tasks.findPendingForUpdate(id)).thenReturn(Optional.empty());
        service.fireDueTask(id);
        verifyNoInteractions(outbox);
        verify(tasks, never()).save(any());
    }

    @Test
    void fireDueTask_teamTarget_requestsOnePerMember() {
        UUID ruleId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        EscalationTask task = pendingTask(ruleId);
        when(tasks.findPendingForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(incidents.findById(INCIDENT)).thenReturn(Optional.empty());
        EscalationRuleTarget target = mock(EscalationRuleTarget.class);
        when(target.getTargetType()).thenReturn(TargetType.TEAM);
        when(target.getTargetId()).thenReturn(team);
        when(targets.findByRuleId(ruleId)).thenReturn(List.of(target));
        when(identity.teamMemberIds(ORG, team)).thenReturn(List.of(u1, u2));

        service.fireDueTask(task.getId());

        verify(outbox, times(2)).append(any(), any(), eq(Topics.NOTIFICATION_REQUESTED), any(), any());
    }
}
