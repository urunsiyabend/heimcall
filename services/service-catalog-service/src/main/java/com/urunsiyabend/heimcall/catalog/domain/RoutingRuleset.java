package com.urunsiyabend.heimcall.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-org routing ruleset header (Phase 17). Holds the monotonic {@code version} (bumped on every rule
 * write, so T2's snapshot events have something to gate on), the IANA {@code timezone} used to evaluate
 * rule time restrictions, and the pinned fallback action. A null {@code fallbackPolicyId} means the
 * fallback is {@code UNROUTED} (visible, counted — Phase 10 T3). The ordered rules live in
 * {@link RoutingRule}; this is just the header. Absence of a row means the org has no rules and no
 * fallback (UNROUTED for everything).
 */
@Entity
@Table(name = "routing_ruleset")
public class RoutingRuleset {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "fallback_service_id")
    private UUID fallbackServiceId;

    @Column(name = "fallback_policy_id")
    private UUID fallbackPolicyId;

    protected RoutingRuleset() {
    }

    public static RoutingRuleset create(UUID organizationId, Instant at) {
        RoutingRuleset r = new RoutingRuleset();
        r.organizationId = organizationId;
        r.version = 1;
        r.timezone = "UTC";
        r.publishedAt = at;
        return r;
    }

    /** Bump the version and publish timestamp — call on every rule insert/update/delete/reorder. */
    public void bump(Instant at) {
        this.version++;
        this.publishedAt = at;
    }

    public void setTimezone(String timezone, Instant at) {
        this.timezone = timezone;
        bump(at);
    }

    public void setFallback(UUID serviceId, UUID policyId, Instant at) {
        this.fallbackServiceId = serviceId;
        this.fallbackPolicyId = policyId;
        bump(at);
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public long getVersion() {
        return version;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getFallbackServiceId() {
        return fallbackServiceId;
    }

    public UUID getFallbackPolicyId() {
        return fallbackPolicyId;
    }
}
