package com.urunsiyabend.heimcall.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Local read-model of one org's routing ruleset (Phase 17 T2), populated from the
 * {@code routing.ruleset-published.v1} snapshot stream (and the pull-based hydration/reconciliation
 * fallbacks). {@code payloadJson} is the serialized routing-core {@code Ruleset}; the hot path
 * deserializes it and evaluates locally. Stored in incident's own database so a restart inherits it —
 * no process-level cold start. Only {@link ProjectionState#READY} / {@link ProjectionState#ABSENT_CONFIRMED}
 * are persisted here.
 */
@Entity
@Table(name = "routing_ruleset_projection")
public class RoutingRulesetProjection {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectionState state;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected RoutingRulesetProjection() {
    }

    public static RoutingRulesetProjection of(UUID organizationId, long version, String payloadJson,
                                              ProjectionState state, Instant observedAt) {
        RoutingRulesetProjection p = new RoutingRulesetProjection();
        p.organizationId = organizationId;
        p.version = version;
        p.payloadJson = payloadJson;
        p.state = state;
        p.observedAt = observedAt;
        return p;
    }

    public void update(long version, String payloadJson, ProjectionState state, Instant observedAt) {
        this.version = version;
        this.payloadJson = payloadJson;
        this.state = state;
        this.observedAt = observedAt;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public long getVersion() {
        return version;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public ProjectionState getState() {
        return state;
    }

    public Instant getObservedAt() {
        return observedAt;
    }
}
