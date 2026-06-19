package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Last-known-good routing cache (Phase 10 T4). Backed by the {@code routing_cache} table in the
 * incident database, so it survives pod restarts (the in-heap cold-start gap is exactly when an outage
 * may coincide with a deploy) and joins the caller's transaction via {@link JdbcTemplate} on the shared
 * datasource — no cross-resource write.
 *
 * <p>Only ROUTED answers are stored (see {@code RoutingAvailabilityResolver}). A catalog 404 (definitive
 * no-match) {@link #evict tombstones} the row so a dead route can never page on a later outage. No TTL:
 * freshness is maintained by write-through on every up resolve, not by expiry.
 */
@Component
public class RoutingCacheStore {

    private final JdbcTemplate jdbc;

    public RoutingCacheStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Write-through: upsert the last-known-good routing for {@code (org, routingKey)}. */
    public void put(UUID organizationId, String routingKey, Routing routing, Instant now) {
        jdbc.update(
                "INSERT INTO routing_cache (organization_id, routing_key, service_id, escalation_policy_id, "
                        + "owner_team_id, last_refreshed_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (organization_id, routing_key) DO UPDATE SET "
                        + "service_id = EXCLUDED.service_id, escalation_policy_id = EXCLUDED.escalation_policy_id, "
                        + "owner_team_id = EXCLUDED.owner_team_id, last_refreshed_at = EXCLUDED.last_refreshed_at",
                organizationId, routingKey, routing.serviceId(), routing.escalationPolicyId(),
                routing.ownerTeamId(), Timestamp.from(now));
    }

    /** Last-known-good lookup, used only when catalog is unavailable. */
    public Optional<Routing> find(UUID organizationId, String routingKey) {
        List<Routing> rows = jdbc.query(
                "SELECT service_id, escalation_policy_id, owner_team_id FROM routing_cache "
                        + "WHERE organization_id = ? AND routing_key = ?",
                (rs, n) -> new Routing(
                        rs.getObject("service_id", UUID.class),
                        rs.getObject("escalation_policy_id", UUID.class),
                        rs.getObject("owner_team_id", UUID.class)),
                organizationId, routingKey);
        return rows.stream().findFirst();
    }

    /** Tombstone: a catalog-confirmed no-match (404) must not survive as a positive last-known route. */
    public void evict(UUID organizationId, String routingKey) {
        jdbc.update("DELETE FROM routing_cache WHERE organization_id = ? AND routing_key = ?",
                organizationId, routingKey);
    }
}
