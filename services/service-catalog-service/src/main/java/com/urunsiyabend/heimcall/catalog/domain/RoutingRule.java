package com.urunsiyabend.heimcall.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted routing rule (Phase 17). {@code conditionJson} is the serialized condition tree
 * (the routing-core {@code ConditionNode}); {@code timeRestrictionJson} is the optional time gate. The
 * action is stored in columns (not JSON) so the referenced service/policy stays queryable and
 * validatable. Evaluation order is {@code position} (0-based) within the org. This is the storage
 * shape; the {@code com.urunsiyabend.heimcall.catalog.routing} model is the evaluable shape, assembled
 * from it by the service layer.
 */
@Entity
@Table(name = "routing_rule")
public class RoutingRule {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json", nullable = false, columnDefinition = "jsonb")
    private String conditionJson;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "action_service_id")
    private UUID actionServiceId;

    @Column(name = "action_policy_id")
    private UUID actionPolicyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_restriction_json", columnDefinition = "jsonb")
    private String timeRestrictionJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoutingRule() {
    }

    public static RoutingRule create(UUID organizationId, int position, String name, boolean enabled,
                                     String conditionJson, String actionType, UUID actionServiceId,
                                     UUID actionPolicyId, String timeRestrictionJson, Instant at) {
        RoutingRule r = new RoutingRule();
        r.id = UUID.randomUUID();
        r.organizationId = organizationId;
        r.position = position;
        r.name = name;
        r.enabled = enabled;
        r.conditionJson = conditionJson;
        r.actionType = actionType;
        r.actionServiceId = actionServiceId;
        r.actionPolicyId = actionPolicyId;
        r.timeRestrictionJson = timeRestrictionJson;
        r.createdAt = at;
        return r;
    }

    public void update(String name, boolean enabled, String conditionJson, String actionType,
                       UUID actionServiceId, UUID actionPolicyId, String timeRestrictionJson) {
        this.name = name;
        this.enabled = enabled;
        this.conditionJson = conditionJson;
        this.actionType = actionType;
        this.actionServiceId = actionServiceId;
        this.actionPolicyId = actionPolicyId;
        this.timeRestrictionJson = timeRestrictionJson;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public int getPosition() {
        return position;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getConditionJson() {
        return conditionJson;
    }

    public String getActionType() {
        return actionType;
    }

    public UUID getActionServiceId() {
        return actionServiceId;
    }

    public UUID getActionPolicyId() {
        return actionPolicyId;
    }

    public String getTimeRestrictionJson() {
        return timeRestrictionJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
