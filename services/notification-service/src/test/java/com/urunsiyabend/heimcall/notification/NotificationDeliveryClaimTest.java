package com.urunsiyabend.heimcall.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 20 T1: proves the {@code NotificationDeliveryRepository.claimDue} two-phase claim (due PENDING,
 * <b>or</b> a due SENDING row whose lease expired, {@code FOR UPDATE SKIP LOCKED}) gives exactly-one-claimer
 * semantics across replicas (plan §3.2) <b>and</b> that an expired lease makes a SENDING row re-claimable —
 * the crash-recovery path that keeps delivery at-least-once. Two concurrent connections stand in for two
 * delivery workers, against a real PostgreSQL. Mirrors {@code EscalationTaskClaimTest}.
 *
 * <p>Runs against the local docker-compose notification DB by default (overridable via
 * {@code NOTIFICATION_TEST_JDBC_URL} / {@code _USER} / {@code _PASSWORD}); isolated in a dedicated schema.
 * Skips (JUnit assumption) if no PG.
 */
class NotificationDeliveryClaimTest {

    private static final String URL = envOr("NOTIFICATION_TEST_JDBC_URL", "jdbc:postgresql://localhost:5433/notification");
    private static final String USER = envOr("NOTIFICATION_TEST_JDBC_USER", "notification");
    private static final String PASS = envOr("NOTIFICATION_TEST_JDBC_PASSWORD", "notification");
    private static final String SCHEMA = "notification_claim_test";

    // Mirrors NotificationDeliveryRepository.claimDue (DB now() in place of the :now param, LIMIT 1).
    private static final String CLAIM_SQL =
            "SELECT * FROM notification_delivery WHERE next_attempt_at <= now() "
                    + "AND (status = 'PENDING' OR (status = 'SENDING' AND lease_expires_at < now())) "
                    + "ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1";

    private static final UUID DELIVERY = UUID.randomUUID();

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v != null ? v : fallback;
    }

    private Connection conn() throws SQLException {
        Connection c = DriverManager.getConnection(URL, USER, PASS);
        try (Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
        }
        return c;
    }

    @BeforeEach
    void setUp() throws Exception {
        Connection probe;
        try {
            probe = DriverManager.getConnection(URL, USER, PASS);
        } catch (SQLException unreachable) {
            assumeTrue(false, "No PostgreSQL at " + URL + " (set NOTIFICATION_TEST_JDBC_URL); skipping");
            return;
        }
        try (Connection c = probe; Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TABLE " + SCHEMA + ".notification_delivery ("
                    + "id UUID PRIMARY KEY, status TEXT NOT NULL, "
                    + "next_attempt_at TIMESTAMPTZ NOT NULL, lease_expires_at TIMESTAMPTZ)");
            // A due PENDING row (next_attempt_at in the past, no lease).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".notification_delivery (id, status, next_attempt_at) "
                            + "VALUES (?, 'PENDING', now() - interval '1 second')")) {
                ps.setObject(1, DELIVERY);
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private boolean claims(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CLAIM_SQL)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Phase 1 of the claim: flip the row to SENDING with a future lease, mirroring DeliveryTx.claimDue. */
    private void markSending(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE " + SCHEMA + ".notification_delivery "
                    + "SET status = 'SENDING', lease_expires_at = now() + interval '60 seconds' WHERE id = '" + DELIVERY + "'");
        }
    }

    @Test
    void exactlyOneWorkerClaimsTheDelivery() throws Exception {
        try (Connection a = conn(); Connection b = conn()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // Worker A claims the due PENDING delivery and holds the row lock until it commits.
            assertThat(claims(a)).isTrue();

            // Worker B, concurrently: the row is locked -> SKIP LOCKED -> no row. No duplicate send.
            assertThat(claims(b)).isFalse();

            // A flips it to SENDING with a fresh 60s lease and commits, releasing the lock.
            markSending(a);
            a.commit();

            // B retries after the lock is free, but the row is SENDING with an un-expired lease -> no claim.
            assertThat(claims(b)).isFalse();
            b.commit();
        }
    }

    @Test
    void expiredLeaseMakesSendingRowReclaimable() throws Exception {
        // Simulate a worker that claimed the row then crashed: SENDING with a lease already in the past.
        try (Connection setup = conn(); Statement st = setup.createStatement()) {
            st.executeUpdate("UPDATE " + SCHEMA + ".notification_delivery "
                    + "SET status = 'SENDING', lease_expires_at = now() - interval '1 second' WHERE id = '" + DELIVERY + "'");
        }

        try (Connection w = conn()) {
            w.setAutoCommit(false);
            // The expired-lease SENDING row is due again -> a new worker re-claims it (at-least-once recovery).
            assertThat(claims(w)).isTrue();
            w.commit();
        }
    }
}
