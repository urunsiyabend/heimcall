package com.urunsiyabend.heimcall.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes {@code PUBLISHED} outbox rows older than the retention window. Published rows are kept (not
 * deleted on publish) so the table doubles as an audit/replay trail; this scheduled prune keeps it
 * from growing unbounded.
 */
public class OutboxPrune {

    private static final Logger log = LoggerFactory.getLogger(OutboxPrune.class);

    private final JdbcTemplate jdbc;
    private final Duration retention;

    public OutboxPrune(JdbcTemplate jdbc, Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${heimcall.outbox.prune.interval-ms:3600000}")
    @Transactional
    public void prune() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retention));
        int deleted = jdbc.update("DELETE FROM outbox WHERE status = 'PUBLISHED' AND published_at < ?", cutoff);
        if (deleted > 0) {
            log.info("Pruned {} published outbox rows older than {}", deleted, retention);
        }
    }
}
