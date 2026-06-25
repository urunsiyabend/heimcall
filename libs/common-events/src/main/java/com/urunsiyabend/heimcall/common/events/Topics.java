package com.urunsiyabend.heimcall.common.events;

/**
 * Kafka topic names. Convention: {@code <context>.<event-name>.v<version>}.
 */
public final class Topics {

    private Topics() {
    }

    public static final String ALERT_RECEIVED = "alert.received.v1";
    public static final String INCIDENT_LIFECYCLE = "incident.lifecycle.v1";
    public static final String ESCALATION_REQUESTED = "escalation.requested.v1";
    public static final String NOTIFICATION_REQUESTED = "notification.requested.v1";
    public static final String NOTIFICATION_DELIVERED = "notification.delivered.v1";
    public static final String NOTIFICATION_FAILED = "notification.failed.v1";
    // Phase 17 T2: full routing ruleset snapshot, keyed by organizationId.
    public static final String ROUTING_RULESET_PUBLISHED = "routing.ruleset-published.v1";
}
