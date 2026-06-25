package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;
import com.urunsiyabend.heimcall.common.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident")
public class Incident {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String source;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Column(name = "external_entity_id")
    private String externalEntityId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Column(name = "routing_key")
    private String routingKey;

    @Column(name = "service_id")
    private UUID serviceId;

    @Column(name = "escalation_policy_id")
    private UUID escalationPolicyId;

    @Column(name = "matched_rule_id")
    private UUID matchedRuleId;

    @Column(name = "ruleset_version")
    private Long rulesetVersion;

    @Column(name = "unrouted", nullable = false)
    private boolean unrouted;

    @Column(name = "routed_from_cache", nullable = false)
    private boolean routedFromCache;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconcile_result")
    private ReconcileResult reconcileResult;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    protected Incident() {
    }

    public static Incident trigger(UUID organizationId, String source, String dedupKey,
                                   String externalEntityId, String title, String description,
                                   Severity severity, String routingKey, Instant occurredAt) {
        Incident incident = new Incident();
        incident.id = UUID.randomUUID();
        incident.organizationId = organizationId;
        incident.source = source;
        incident.dedupKey = dedupKey;
        incident.externalEntityId = externalEntityId;
        incident.title = title;
        incident.description = description;
        incident.severity = severity != null ? severity : Severity.WARNING;
        incident.status = IncidentStatus.TRIGGERED;
        incident.routingKey = routingKey;
        incident.createdAt = occurredAt;
        incident.updatedAt = occurredAt;
        incident.lastEventAt = occurredAt;
        return incident;
    }

    /** Stamp the routing decision from service-catalog's rule engine (Phase 17): the resolved service +
     *  policy, the rule that matched ({@code null} when the ruleset fallback supplied the policy), and
     *  the ruleset version evaluated — recorded for explainability. Any may be null if unresolved. */
    public void stampRouting(UUID serviceId, UUID escalationPolicyId, UUID matchedRuleId, Long rulesetVersion) {
        this.serviceId = serviceId;
        this.escalationPolicyId = escalationPolicyId;
        this.matchedRuleId = matchedRuleId;
        this.rulesetVersion = rulesetVersion;
    }

    /**
     * Mark a definitive no-match: service-catalog returned no service for this routingKey AND no
     * org-default escalation policy is configured (Phase 10 T3). Nobody will be paged — a deliberate,
     * visible decision, not a silent fallthrough. No policy is stamped, so escalation short-circuits.
     */
    public void markUnrouted() {
        this.unrouted = true;
    }

    /**
     * Mark that this incident was routed from the last-known-good cache during a catalog outage
     * (Phase 10 T4) — a deliberate, visible degraded page on possibly-stale routing, not a silent one.
     * A policy IS stamped (from cache), so escalation still fires.
     */
    public void markRoutedFromCache() {
        this.routedFromCache = true;
    }

    /** Record the audit-only outcome of reconciling a cache-routed incident after catalog recovery. */
    public void reconcile(ReconcileResult result, Instant at) {
        this.reconcileResult = result;
        this.reconciledAt = at;
    }

    /** Touch the incident on a duplicate signal (the count itself lives on the linked alert). */
    public void touch(Instant occurredAt) {
        this.lastEventAt = occurredAt;
        this.updatedAt = occurredAt;
    }

    public void acknowledge(Instant at) {
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.updatedAt = at;
        this.lastEventAt = at;
    }

    public void resolve(Instant at) {
        this.status = IncidentStatus.RESOLVED;
        this.updatedAt = at;
        this.lastEventAt = at;
    }

    public void cancel(Instant at) {
        this.status = IncidentStatus.CANCELED;
        this.updatedAt = at;
        this.lastEventAt = at;
    }

    public boolean isOpen() {
        return status == IncidentStatus.TRIGGERED || status == IncidentStatus.ACKNOWLEDGED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getSource() {
        return source;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getExternalEntityId() {
        return externalEntityId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public UUID getEscalationPolicyId() {
        return escalationPolicyId;
    }

    public UUID getMatchedRuleId() {
        return matchedRuleId;
    }

    public Long getRulesetVersion() {
        return rulesetVersion;
    }

    public boolean isUnrouted() {
        return unrouted;
    }

    public boolean isRoutedFromCache() {
        return routedFromCache;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public ReconcileResult getReconcileResult() {
        return reconcileResult;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
