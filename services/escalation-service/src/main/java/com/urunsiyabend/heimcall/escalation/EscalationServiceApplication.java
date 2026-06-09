package com.urunsiyabend.heimcall.escalation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Escalation engine: runs escalation policies for triggered incidents, schedules level tasks,
 * resolves targets to on-call users, and requests notifications. Cancels pending tasks on ACK/RESOLVE.
 */
@SpringBootApplication
@EnableScheduling
public class EscalationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscalationServiceApplication.class, args);
    }
}
