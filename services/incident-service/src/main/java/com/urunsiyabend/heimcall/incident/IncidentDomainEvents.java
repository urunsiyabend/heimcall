package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process domain events published by {@link IncidentService} inside the processing transaction.
 * {@link IncidentEventPublisher} forwards them to Kafka only after the transaction commits, so a
 * rolled-back change never emits a ghost event.
 */
final class IncidentDomainEvents {

    private IncidentDomainEvents() {
    }

    record Triggered(UUID incidentId, UUID organizationId, String dedupKey, String title,
                     Severity severity, UUID escalationPolicyId, boolean unrouted, boolean routedFromCache,
                     Instant at) {
    }

    record Acknowledged(UUID incidentId, UUID organizationId, Instant at) {
    }

    record Resolved(UUID incidentId, UUID organizationId, Instant at) {
    }

    record Canceled(UUID incidentId, UUID organizationId, Instant at) {
    }
}
