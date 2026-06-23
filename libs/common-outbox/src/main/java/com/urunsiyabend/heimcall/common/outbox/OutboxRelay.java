package com.urunsiyabend.heimcall.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@code PENDING} outbox rows and publishes them to Kafka with confirm, marking each
 * {@code PUBLISHED}. Rows are claimed with {@code FOR UPDATE SKIP LOCKED} ordered by id, so the relay
 * is lock-safe across multiple instances (each claims a disjoint batch) and publishes in insertion
 * order (per-aggregate ordering preserved). The whole claim-publish-mark runs in one transaction, so
 * the row locks are held until the marks commit.
 *
 * <p>The producer is a non-bean {@link KafkaTemplate} (built in the auto-config, not exposed) so the
 * observability {@code KafkaTracing} BeanPostProcessor cannot flip observation on it — that would have
 * the relay inject its own {@code traceparent} and break the link to the original request. Instead the
 * relay re-emits the stored headers verbatim (including the original {@code traceparent}), and the
 * downstream consumer's observation continues the original trace.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final TypeReference<Map<String, String>> HEADER_MAP = new TypeReference<>() {
    };

    /**
     * Claims PENDING rows oldest-first, but only an aggregate's <b>lowest-id</b> PENDING row is eligible
     * (the {@code NOT EXISTS} guard, Phase 12). This preserves per-aggregate publish order even across
     * <b>multiple relay instances</b>: while instance A holds a row's lock, a later same-aggregate row is
     * not yet PUBLISHED, so the guard makes it unclaimable by instance B until A commits — B cannot
     * overtake it. {@code FOR UPDATE SKIP LOCKED} still lets different aggregates publish in parallel.
     */
    static final String CLAIM_SQL =
            "SELECT id, topic, msg_key, payload, headers FROM outbox o WHERE status = 'PENDING' "
                    + "AND NOT EXISTS (SELECT 1 FROM outbox e WHERE e.aggregate_id = o.aggregate_id "
                    + "AND e.status = 'PENDING' AND e.id < o.id) "
                    + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> relayTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long publishTimeoutMs;
    private final int maxAttempts;
    private final Counter deadCounter;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> relayTemplate,
                       ObjectMapper objectMapper, int batchSize, long publishTimeoutMs,
                       int maxAttempts, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.relayTemplate = relayTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.publishTimeoutMs = publishTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.deadCounter = meterRegistry != null ? meterRegistry.counter("outbox_dead_total") : null;
    }

    @Scheduled(fixedDelayString = "${heimcall.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = jdbc.queryForList(CLAIM_SQL, batchSize);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String topic = (String) row.get("topic");
            String key = (String) row.get("msg_key");
            byte[] payload = (byte[]) row.get("payload");
            String headersJson = (String) row.get("headers");

            // A corrupt-headers row can never be relayed (parse fails before any send). Dead-letter it
            // and move on so it never blocks the rows behind it. (Phase 15: poison-row head-of-line fix.)
            Map<String, String> headers;
            try {
                headers = parseHeaders(headersJson);
            } catch (RuntimeException parseError) {
                deadLetter(id, topic, "corrupt headers: " + parseError.getMessage());
                continue;
            }

            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    record.headers().add(h.getKey(), OutboxAppender.utf8(h.getValue()));
                }
                relayTemplate.send(record).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
                jdbc.update("UPDATE outbox SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(Instant.now()), id);
                log.debug("Relayed outbox row {} to {}", id, topic);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // A deterministically permanent broker rejection (too-large, unknown topic, bad
                // serialization) will fail every retry — dead-letter it and keep draining the batch.
                if (isPermanent(e)) {
                    deadLetter(id, topic, e.getMessage());
                    continue;
                }
                // Transient (broker outage / timeout): bump attempts and stop the round — the rest of
                // the batch almost certainly fails the same way, so spinning wastes the poll. The row
                // stays PENDING and retries next poll. A backstop dead-letters it once it has burned
                // through maxAttempts, so an unforeseen always-failing row can never stall forever.
                int attempts = bumpAttempts(id, e.getMessage());
                if (attempts >= maxAttempts) {
                    markDead(id);
                    log.error("Dead-lettered outbox row {} to {} after {} failed attempts: {}",
                            id, topic, attempts, e.getMessage());
                    continue;
                }
                log.error("Failed to relay outbox row {} to {} (attempt {}): {}", id, topic, attempts, e.getMessage());
                break;
            }
        }
    }

    /** Flags a row {@code DEAD} (bumping attempts), increments the dead counter, logs loudly. */
    private void deadLetter(long id, String topic, String error) {
        jdbc.update("UPDATE outbox SET status = 'DEAD', attempts = attempts + 1, last_error = ? WHERE id = ?",
                truncate(error), id);
        if (deadCounter != null) {
            deadCounter.increment();
        }
        log.error("Dead-lettered outbox row {} to {}: {}", id, topic, error);
    }

    /** Bumps a transient-failure row's attempt count and records the error, returning the new count. */
    private int bumpAttempts(long id, String error) {
        Integer attempts = jdbc.queryForObject(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ? RETURNING attempts",
                Integer.class, truncate(error), id);
        return attempts != null ? attempts : 0;
    }

    /** Flags an already-attempt-bumped row {@code DEAD} and counts it (backstop path). */
    private void markDead(long id) {
        jdbc.update("UPDATE outbox SET status = 'DEAD' WHERE id = ?", id);
        if (deadCounter != null) {
            deadCounter.increment();
        }
    }

    /** True for broker errors that no retry can fix — the message itself is unsendable. */
    private static boolean isPermanent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RecordTooLargeException
                    || t instanceof SerializationException
                    || t instanceof InvalidTopicException
                    || t instanceof UnknownTopicOrPartitionException) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> parseHeaders(String headersJson) {
        try {
            return objectMapper.readValue(headersJson, HEADER_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt outbox headers: " + headersJson, e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
