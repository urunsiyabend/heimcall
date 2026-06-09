package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.escalation.domain.EscalationTask;
import com.urunsiyabend.heimcall.escalation.domain.EscalationTaskRepository;
import com.urunsiyabend.heimcall.escalation.domain.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls for escalation tasks whose scheduled time has passed and fires each one. Each task fires in
 * its own transaction ({@link EscalationService#fireDueTask}); a failure to fire one task (e.g. a
 * dependency is down) leaves it PENDING and is logged, so the loop continues and it retries next poll.
 */
@Component
public class EscalationWorker {

    private static final Logger log = LoggerFactory.getLogger(EscalationWorker.class);

    private final EscalationTaskRepository tasks;
    private final EscalationService escalationService;

    public EscalationWorker(EscalationTaskRepository tasks, EscalationService escalationService) {
        this.tasks = tasks;
        this.escalationService = escalationService;
    }

    @Scheduled(fixedDelayString = "${escalation.worker.poll-interval-ms:5000}")
    public void fireDueTasks() {
        List<EscalationTask> due = tasks.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                TaskStatus.PENDING, Instant.now());
        for (EscalationTask task : due) {
            try {
                escalationService.fireDueTask(task.getId());
            } catch (RuntimeException e) {
                log.warn("Escalation task {} could not fire, will retry: {}", task.getId(), e.getMessage());
            }
        }
    }
}
