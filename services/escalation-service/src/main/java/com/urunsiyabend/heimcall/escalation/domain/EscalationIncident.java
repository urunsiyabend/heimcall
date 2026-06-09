package com.urunsiyabend.heimcall.escalation.domain;

import com.urunsiyabend.heimcall.common.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Incident context captured at trigger time so a task firing later can build a notification without
 * calling back into incident-service. One row per escalated incident.
 */
@Entity
@Table(name = "escalation_incident")
public class EscalationIncident {

    @Id
    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "title")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private Severity severity;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EscalationIncident() {
    }

    public static EscalationIncident of(UUID incidentId, UUID organizationId, UUID policyId, String title,
                                        Severity severity, Instant triggeredAt, Instant createdAt) {
        EscalationIncident i = new EscalationIncident();
        i.incidentId = incidentId;
        i.organizationId = organizationId;
        i.policyId = policyId;
        i.title = title;
        i.severity = severity;
        i.triggeredAt = triggeredAt;
        i.createdAt = createdAt;
        return i;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public String getTitle() {
        return title;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
