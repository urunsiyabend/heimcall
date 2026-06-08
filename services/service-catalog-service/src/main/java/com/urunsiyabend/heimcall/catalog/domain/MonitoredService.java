package com.urunsiyabend.heimcall.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A monitored service within an organization. {@code ownerTeamId} references a team in
 * identity-service; {@code escalationPolicyId} is a placeholder until escalation-service exists.
 */
@Entity
@Table(name = "monitored_service")
public class MonitoredService {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "owner_team_id")
    private UUID ownerTeamId;

    @Column(name = "escalation_policy_id")
    private UUID escalationPolicyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoredService() {
    }

    public static MonitoredService create(UUID organizationId, String name, String slug,
                                          String description, Instant at) {
        MonitoredService s = new MonitoredService();
        s.id = UUID.randomUUID();
        s.organizationId = organizationId;
        s.name = name;
        s.slug = slug;
        s.description = description;
        s.createdAt = at;
        s.updatedAt = at;
        return s;
    }

    public void update(String name, String description, Instant at) {
        this.name = name;
        this.description = description;
        this.updatedAt = at;
    }

    public void assignOwner(UUID ownerTeamId, Instant at) {
        this.ownerTeamId = ownerTeamId;
        this.updatedAt = at;
    }

    public void assignEscalationPolicy(UUID escalationPolicyId, Instant at) {
        this.escalationPolicyId = escalationPolicyId;
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public UUID getOwnerTeamId() {
        return ownerTeamId;
    }

    public UUID getEscalationPolicyId() {
        return escalationPolicyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
