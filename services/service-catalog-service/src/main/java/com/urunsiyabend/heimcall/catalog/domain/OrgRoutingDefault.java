package com.urunsiyabend.heimcall.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The org-level catch-all escalation policy (Phase 10 T2). When no specific service carries an
 * inbound routingKey (or the matched service has no policy), routing falls back to this default,
 * making routing resolution total. {@code defaultEscalationPolicyId} references a policy in
 * escalation-service (validated on set). One row per organization; absence means no default.
 */
@Entity
@Table(name = "org_routing_default")
public class OrgRoutingDefault {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "default_escalation_policy_id", nullable = false)
    private UUID defaultEscalationPolicyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgRoutingDefault() {
    }

    public static OrgRoutingDefault create(UUID organizationId, UUID defaultEscalationPolicyId, Instant at) {
        OrgRoutingDefault d = new OrgRoutingDefault();
        d.organizationId = organizationId;
        d.defaultEscalationPolicyId = defaultEscalationPolicyId;
        d.createdAt = at;
        d.updatedAt = at;
        return d;
    }

    public void setPolicy(UUID defaultEscalationPolicyId, Instant at) {
        this.defaultEscalationPolicyId = defaultEscalationPolicyId;
        this.updatedAt = at;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getDefaultEscalationPolicyId() {
        return defaultEscalationPolicyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
