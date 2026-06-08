package com.urunsiyabend.heimcall.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A user's membership in an organization, with a role. */
@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Membership() {
    }

    public static Membership create(UUID organizationId, UUID userId, Role role, Instant at) {
        Membership m = new Membership();
        m.id = UUID.randomUUID();
        m.organizationId = organizationId;
        m.userId = userId;
        m.role = role;
        m.createdAt = at;
        return m;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
