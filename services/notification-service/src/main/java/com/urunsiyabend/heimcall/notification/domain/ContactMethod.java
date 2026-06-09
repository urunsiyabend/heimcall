package com.urunsiyabend.heimcall.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A way to reach a user on a channel. A notification request fans out to the recipient's enabled
 * contact methods; {@code enabled} doubles as a basic per-channel preference.
 */
@Entity
@Table(name = "contact_method")
public class ContactMethod {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String destination;

    @Column
    private String label;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContactMethod() {
    }

    public static ContactMethod create(UUID organizationId, UUID userId, NotificationChannel channel,
                                       String destination, String label, Instant now) {
        ContactMethod c = new ContactMethod();
        c.id = UUID.randomUUID();
        c.organizationId = organizationId;
        c.userId = userId;
        c.channel = channel;
        c.destination = destination;
        c.label = label;
        c.enabled = true;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public void setEnabled(boolean enabled, Instant now) {
        this.enabled = enabled;
        this.updatedAt = now;
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

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getDestination() {
        return destination;
    }

    public String getLabel() {
        return label;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
