package com.urunsiyabend.heimcall.common.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@link OutboxRelay#CLAIM_SQL per-aggregate ordering guard} (Phase 12) against a real
 * PostgreSQL with two concurrent connections standing in for two relay instances. The invariant: an
 * aggregate's events publish in id order even across instances — a later same-aggregate row is not
 * claimable until the earlier one is PUBLISHED, so one instance cannot overtake another. Different
 * aggregates still claim in parallel ({@code FOR UPDATE SKIP LOCKED}).
 *
 * <p>Runs against the local docker-compose PostgreSQL by default (overridable via the
 * {@code OUTBOX_TEST_JDBC_URL} / {@code OUTBOX_TEST_JDBC_USER} / {@code OUTBOX_TEST_JDBC_PASSWORD}
 * env vars); the work is isolated in a dedicated schema so the real {@code outbox} table is untouched.
 * If no PostgreSQL is reachable the test skips (JUnit assumption) rather than failing.
 */
class OutboxRelayOrderingTest {

    private static final String URL = envOr("OUTBOX_TEST_JDBC_URL", "jdbc:postgresql://localhost:5433/incident");
    private static final String USER = envOr("OUTBOX_TEST_JDBC_USER", "incident");
    private static final String PASS = envOr("OUTBOX_TEST_JDBC_PASSWORD", "incident");
    private static final String SCHEMA = "outbox_guard_test";

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
            assumeTrue(false, "No PostgreSQL at " + URL + " (set OUTBOX_TEST_JDBC_URL); skipping");
            return;
        }
        try (Connection c = probe; Statement st = c.createStatement()) {
            // Dedicated schema so we never touch a real public.outbox.
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TABLE " + SCHEMA + ".outbox (id BIGINT PRIMARY KEY, aggregate_id TEXT NOT NULL, "
                    + "status TEXT NOT NULL, topic TEXT, msg_key TEXT, payload BYTEA, headers TEXT)");
            // aggregate X: id 1 (e.g. TRIGGERED) then id 2 (e.g. ACK); aggregate Y: id 3 (independent).
            insert(c, 1, "X");
            insert(c, 2, "X");
            insert(c, 3, "Y");
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

    private void insert(Connection c, long id, String agg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + SCHEMA + ".outbox (id, aggregate_id, status, topic, msg_key, payload, headers) "
                        + "VALUES (?, ?, 'PENDING', 'incident.lifecycle.v1', ?, ?, '{}')")) {
            ps.setLong(1, id);
            ps.setString(2, agg);
            ps.setString(3, agg);
            ps.setBytes(4, new byte[0]);
            ps.executeUpdate();
        }
    }

    private List<Long> claim(Connection c, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(OutboxRelay.CLAIM_SQL)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    @Test
    void guardKeepsPerAggregateOrderAcrossConcurrentClaimers() throws Exception {
        try (Connection a = conn(); Connection b = conn()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // Instance A claims: X's lowest (1) + Y's lowest (3). NOT X's id 2 — its earlier row 1 is
            // still PENDING, so the guard hides it. A now holds row locks on 1 and 3.
            assertThat(claim(a, 10)).containsExactly(1L, 3L);

            // Instance B, concurrently: 1 and 3 are locked (SKIP LOCKED), and 2 is guarded by the still
            // PENDING 1 -> B gets nothing. It cannot overtake A on aggregate X.
            assertThat(claim(b, 10)).isEmpty();

            // A publishes X's row 1 and commits (releasing both locks).
            try (Statement st = a.createStatement()) {
                st.executeUpdate("UPDATE " + SCHEMA + ".outbox SET status = 'PUBLISHED' WHERE id = 1");
            }
            a.commit();

            // Now X's row 2 is the lowest PENDING for X (1 is PUBLISHED) -> eligible; Y's 3 lock released.
            assertThat(claim(b, 10)).containsExactlyInAnyOrder(2L, 3L);
            b.commit();
        }
    }
}
