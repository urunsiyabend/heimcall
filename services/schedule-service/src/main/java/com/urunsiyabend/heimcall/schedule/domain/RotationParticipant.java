package com.urunsiyabend.heimcall.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A user in a rotation, at a fixed cycle position. */
@Entity
@Table(name = "rotation_participant")
public class RotationParticipant {

    @Id
    private UUID id;

    @Column(name = "rotation_id", nullable = false)
    private UUID rotationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int position;

    protected RotationParticipant() {
    }

    public static RotationParticipant create(UUID rotationId, UUID userId, int position) {
        RotationParticipant p = new RotationParticipant();
        p.id = UUID.randomUUID();
        p.rotationId = rotationId;
        p.userId = userId;
        p.position = position;
        return p;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRotationId() {
        return rotationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getPosition() {
        return position;
    }
}
