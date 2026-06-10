package com.urunsiyabend.heimcall.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public static AppUser create(String email, String displayName, Instant at) {
        AppUser u = new AppUser();
        u.id = UUID.randomUUID();
        u.email = email;
        u.displayName = displayName;
        u.createdAt = at;
        return u;
    }

    /** Create a user with credentials (registration). {@code passwordHash} is already BCrypt-hashed. */
    public static AppUser register(String email, String displayName, String passwordHash, Instant at) {
        AppUser u = create(email, displayName, at);
        u.passwordHash = passwordHash;
        return u;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
