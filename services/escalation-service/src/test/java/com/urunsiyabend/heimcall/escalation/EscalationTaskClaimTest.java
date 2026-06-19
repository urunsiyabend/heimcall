package com.urunsiyabend.heimcall.escalation;

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
 * Phase 13 T3: proves the {@code EscalationTaskRepository.findPendingForUpdate} claim
 * ({@code ... WHERE id=? AND status='PENDING' FOR UPDATE SKIP LOCKED}) gives exactly-one-claimer
 * semantics across replicas (plan §3.2, Phase 11 T1) against a real PostgreSQL with two concurrent
 * connections standing in for two workers. The Phase 11 claim was previously only verified by a manual
 * psql session; this is its first automated proof, mirroring {@code OutboxRelayOrderingTest}.
 *
 * <p>Runs against the local docker-compose escalation DB by default (overridable via
 * {@code ESCALATION_TEST_JDBC_URL} / {@code _USER} / {@code _PASSWORD}); work is isolated in a dedicated
 * schema so the real {@code escalation_task} table is untouched. Skips (JUnit assumption) if no PG.
 */
class EscalationTaskClaimTest {

    private static final String URL = envOr("ESCALATION_TEST_JDBC_URL", "jdbc:postgresql://localhost:5433/escalation");
    private static final String USER = envOr("ESCALATION_TEST_JDBC_USER", "escalation");
    private static final String PASS = envOr("ESCALATION_TEST_JDBC_PASSWORD", "escalation");
    private static final String SCHEMA = "escalation_claim_test";

    // Mirrors EscalationTaskRepository.findPendingForUpdate (positional id instead of the :id named param).
    private static final String CLAIM_SQL =
            "SELECT * FROM escalation_task WHERE id = ? AND status = 'PENDING' FOR UPDATE SKIP LOCKED";

    private static final UUID TASK = UUID.randomUUID();

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
            assumeTrue(false, "No PostgreSQL at " + URL + " (set ESCALATION_TEST_JDBC_URL); skipping");
            return;
        }
        try (Connection c = probe; Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("CREATE TABLE " + SCHEMA + ".escalation_task (id UUID PRIMARY KEY, status TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".escalation_task (id, status) VALUES (?, 'PENDING')")) {
                ps.setObject(1, TASK);
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
            ps.setObject(1, TASK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void exactlyOneWorkerClaimsTheTask() throws Exception {
        try (Connection a = conn(); Connection b = conn()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // Worker A claims the PENDING row and holds the row lock until it commits.
            assertThat(claims(a)).isTrue();

            // Worker B, concurrently: the row is locked -> SKIP LOCKED -> no row. B does NOT double-fire.
            assertThat(claims(b)).isFalse();

            // A fires (marks EXECUTED) and commits, releasing the lock.
            try (Statement st = a.createStatement()) {
                st.executeUpdate("UPDATE " + SCHEMA + ".escalation_task SET status = 'EXECUTED' WHERE id = '" + TASK + "'");
            }
            a.commit();

            // B retries after the lock is free, but the row is no longer PENDING -> still no claim.
            assertThat(claims(b)).isFalse();
            b.commit();
        }
    }
}
