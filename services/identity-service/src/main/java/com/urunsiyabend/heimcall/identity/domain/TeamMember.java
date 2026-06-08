package com.urunsiyabend.heimcall.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Membership of a user in a team (within the team's organization). */
@Entity
@Table(name = "team_member")
public class TeamMember {

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TeamMember() {
    }

    public static TeamMember create(UUID teamId, UUID userId, Instant at) {
        TeamMember tm = new TeamMember();
        tm.id = UUID.randomUUID();
        tm.teamId = teamId;
        tm.userId = userId;
        tm.createdAt = at;
        return tm;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
