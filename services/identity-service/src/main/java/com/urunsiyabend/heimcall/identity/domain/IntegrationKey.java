package com.urunsiyabend.heimcall.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued integration key. The plaintext is never stored — only a SHA-256 hash and a short
 * non-secret prefix for display. {@code integrationId} is the stable id integration-service
 * stamps onto normalized events.
 */
@Entity
@Table(name = "integration_key")
public class IntegrationKey {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "integration_id", nullable = false)
    private UUID integrationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IntegrationKey() {
    }

    public static IntegrationKey issue(UUID organizationId, String name, String keyPrefix,
                                       String keyHash, Instant at) {
        IntegrationKey k = new IntegrationKey();
        k.id = UUID.randomUUID();
        k.organizationId = organizationId;
        k.integrationId = UUID.randomUUID();
        k.name = name;
        k.keyPrefix = keyPrefix;
        k.keyHash = keyHash;
        k.active = true;
        k.createdAt = at;
        return k;
    }

    public void revoke() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIntegrationId() {
        return integrationId;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
