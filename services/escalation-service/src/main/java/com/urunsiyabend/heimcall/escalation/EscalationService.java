package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.common.events.IncidentTriggeredEvent;
import com.urunsiyabend.heimcall.common.events.NotificationRequestedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.escalation.domain.EscalationIncident;
import com.urunsiyabend.heimcall.escalation.domain.EscalationIncidentRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicy;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicyRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRule;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTarget;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTargetRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationTask;
import com.urunsiyabend.heimcall.escalation.domain.EscalationTaskRepository;
import com.urunsiyabend.heimcall.escalation.domain.ProcessedEvent;
import com.urunsiyabend.heimcall.escalation.domain.ProcessedEventRepository;
import com.urunsiyabend.heimcall.escalation.domain.TaskStatus;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escalation engine. On a triggered incident it materializes one {@link EscalationTask} per policy
 * level (and per repeat round), scheduled at {@code triggeredAt + delay}. On acknowledge/resolve it
 * cancels still-pending tasks. The worker calls {@link #fireDueTask} to execute a task: resolve its
 * targets to concrete users and request a notification for each.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final EscalationPolicyRepository policies;
    private final EscalationRuleRepository rules;
    private final EscalationRuleTargetRepository targets;
    private final EscalationTaskRepository tasks;
    private final EscalationIncidentRepository incidents;
    private final ProcessedEventRepository processedEvents;
    private final IdentityClient identity;
    private final ScheduleClient schedule;
    private final OutboxAppender outbox;
    // Phase 8 T2: escalation_task_executed_total.
    private final io.micrometer.core.instrument.Counter taskExecuted;

    public EscalationService(EscalationPolicyRepository policies, EscalationRuleRepository rules,
                             EscalationRuleTargetRepository targets, EscalationTaskRepository tasks,
                             EscalationIncidentRepository incidents, ProcessedEventRepository processedEvents,
                             IdentityClient identity, ScheduleClient schedule,
                             OutboxAppender outbox,
                             io.micrometer.core.instrument.MeterRegistry registry) {
        this.policies = policies;
        this.rules = rules;
        this.targets = targets;
        this.tasks = tasks;
        this.incidents = incidents;
        this.processedEvents = processedEvents;
        this.identity = identity;
        this.schedule = schedule;
        this.outbox = outbox;
        this.taskExecuted = registry.counter("escalation.task.executed");
    }

    /** Materialize escalation tasks for a newly triggered incident. Idempotent on event id and incident. */
    @Transactional
    public void onIncidentTriggered(IncidentTriggeredEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        recordProcessed(event.eventId());

        if (event.escalationPolicyId() == null) {
            log.debug("Incident {} has no escalation policy; nothing to schedule", event.incidentId());
            return;
        }
        if (tasks.existsByIncidentId(event.incidentId())) {
            log.debug("Escalation tasks already exist for incident {}; skipping", event.incidentId());
            return;
        }
        EscalationPolicy policy = policies.findByIdAndOrganizationId(event.escalationPolicyId(), event.organizationId())
                .orElse(null);
        if (policy == null) {
            log.warn("Escalation policy {} not found for org {}; incident {} not escalated",
                    event.escalationPolicyId(), event.organizationId(), event.incidentId());
            return;
        }
        List<EscalationRule> levels = rules.findByPolicyIdOrderByLevelAsc(policy.getId());
        if (levels.isEmpty()) {
            log.warn("Escalation policy {} has no rules; incident {} not escalated", policy.getId(), event.incidentId());
            return;
        }

        Instant triggeredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        Instant now = Instant.now();
        incidents.save(EscalationIncident.of(event.incidentId(), event.organizationId(), policy.getId(),
                event.title(), event.severity(), triggeredAt, now));

        // Each repeat round restarts the whole ladder, offset by the round's span (longest level delay).
        int roundSpan = levels.stream().mapToInt(EscalationRule::getDelaySeconds).max().orElse(0);
        int rounds = policy.getRepeatCount() + 1;
        for (int round = 0; round < rounds; round++) {
            for (EscalationRule rule : levels) {
                Instant scheduledAt = triggeredAt.plusSeconds((long) round * roundSpan + rule.getDelaySeconds());
                tasks.save(EscalationTask.pending(event.organizationId(), event.incidentId(), policy.getId(),
                        rule.getId(), rule.getLevel(), round, scheduledAt, now));
            }
        }
        log.info("Scheduled {} level(s) x {} round(s) for incident {} policy {}",
                levels.size(), rounds, event.incidentId(), policy.getId());
    }

    /** Cancel pending tasks when an incident is acknowledged or resolved. Idempotent on event id. */
    @Transactional
    public void onIncidentClosed(UUID eventId, UUID incidentId, String reason) {
        if (alreadyProcessed(eventId)) {
            return;
        }
        recordProcessed(eventId);
        List<EscalationTask> pending = tasks.findByIncidentIdAndStatus(incidentId, TaskStatus.PENDING);
        pending.forEach(EscalationTask::cancel);
        tasks.saveAll(pending);
        if (!pending.isEmpty()) {
            log.info("Canceled {} pending escalation task(s) for incident {} ({})", pending.size(), incidentId, reason);
        }
    }

    /**
     * Execute one due task: resolve targets, request a notification per recipient, mark executed.
     * Runs in its own transaction. If a target cannot be resolved because a dependency is down, the
     * exception propagates and the task stays PENDING for the next poll (at-least-once).
     */
    @Transactional
    public void fireDueTask(UUID taskId) {
        // Claim the task under a row lock (FOR UPDATE SKIP LOCKED): empty means another worker/replica
        // already fired it or is firing it now, so skip — this is what makes multi-replica firing safe
        // and prevents a duplicate notification.requested (double page). The lock is held until this tx
        // commits the EXECUTED mark below.
        EscalationTask task = tasks.findPendingForUpdate(taskId).orElse(null);
        if (task == null) {
            return;
        }
        EscalationIncident incident = incidents.findById(task.getIncidentId()).orElse(null);
        String title = incident != null ? incident.getTitle() : null;
        var severity = incident != null ? incident.getSeverity() : null;

        // Resolve each target to a recipient, tracking how each user was found (USER vs on-call/team).
        List<EscalationRuleTarget> ruleTargets = targets.findByRuleId(task.getRuleId());
        Map<UUID, String> recipients = new LinkedHashMap<>();
        for (EscalationRuleTarget t : ruleTargets) {
            switch (t.getTargetType()) {
                case USER -> recipients.putIfAbsent(t.getTargetId(), "USER");
                case SCHEDULE -> schedule.onCallUser(task.getOrganizationId(), t.getTargetId())
                        .ifPresent(u -> recipients.putIfAbsent(u, "SCHEDULE"));
                case TEAM -> identity.teamMemberIds(task.getOrganizationId(), t.getTargetId())
                        .forEach(u -> recipients.putIfAbsent(u, "TEAM"));
            }
        }

        Instant now = Instant.now();
        recipients.forEach((userId, source) -> {
            NotificationRequestedEvent payload = new NotificationRequestedEvent(UUID.randomUUID(), now,
                    task.getOrganizationId(), task.getIncidentId(), task.getPolicyId(), task.getLevel(),
                    userId, source, title, severity);
            outbox.append("escalation", task.getIncidentId().toString(), Topics.NOTIFICATION_REQUESTED,
                    task.getIncidentId().toString(), payload);
        });
        task.markExecuted(now);
        tasks.save(task);
        taskExecuted.increment();
        log.info("Fired escalation task {} (incident {} level {}) -> {} recipient(s)",
                taskId, task.getIncidentId(), task.getLevel(), recipients.size());
    }

    private boolean alreadyProcessed(UUID eventId) {
        if (eventId != null && processedEvents.existsById(eventId)) {
            log.debug("Skipping already-processed event {}", eventId);
            return true;
        }
        return false;
    }

    private void recordProcessed(UUID eventId) {
        if (eventId != null) {
            processedEvents.save(new ProcessedEvent(eventId, Instant.now()));
        }
    }
}
