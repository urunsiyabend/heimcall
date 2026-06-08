package com.urunsiyabend.heimcall.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Organization() {
    }

    public static Organization create(String name, String slug, Instant at) {
        Organization o = new Organization();
        o.id = UUID.randomUUID();
        o.name = name;
        o.slug = slug;
        o.createdAt = at;
        return o;
    }

    public void rename(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
