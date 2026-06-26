package com.urunsiyabend.heimcall.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    private final MeterRegistry meterRegistry;
    private final Counter deadCounter;
    private final Timer publishTimer;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> relayTemplate,
                       ObjectMapper objectMapper, int batchSize, long publishTimeoutMs,
                       int maxAttempts, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.relayTemplate = relayTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.publishTimeoutMs = publishTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.meterRegistry = meterRegistry;
        // Phase 19 T1: the relay template is a non-bean (see class javadoc), so Spring Kafka's auto
        // `spring.kafka.template` timer never attaches — instrument the publish path by hand here.
        // `outbox_published_total` is tagged per topic and looked up on the fly (Micrometer caches by
        // name+tags); the publish-latency timer wraps the batch await; `outbox_pending` is a gauge that
        // counts PENDING rows on each Prometheus scrape (the relay backlog depth).
        this.deadCounter = meterRegistry != null ? meterRegistry.counter("outbox_dead_total") : null;
        this.publishTimer = meterRegistry != null ? meterRegistry.timer("outbox_publish_seconds") : null;
        if (meterRegistry != null) {
            Gauge.builder("outbox_pending", jdbc, OutboxRelay::pendingDepth).register(meterRegistry);
        }
    }

    /** PENDING backlog depth, polled by the {@code outbox_pending} gauge on each scrape. */
    private static double pendingDepth(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PENDING'", Long.class);
        return count != null ? count : 0d;
    }

    @Scheduled(fixedDelayString = "${heimcall.outbox.poll-interval-ms:200}")
    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = jdbc.queryForList(CLAIM_SQL, batchSize);
        if (rows.isEmpty()) {
            return;
        }

        // Fire every send first, then await — the producer (idempotent, acks=all) pipelines the in-flight
        // records into a few broker round-trips instead of one blocking ack per row, which is the relay's
        // throughput ceiling. Safe because the CLAIM_SQL NOT EXISTS guard claims at most one row per
        // aggregate, so every row in this batch is a distinct aggregate with no ordering relationship: they
        // can be sent concurrently and marked independently without breaking per-aggregate order. The whole
        // claim -> send -> mark still runs in one transaction, so a crash before commit leaves every row
        // PENDING and re-sent next poll (at-least-once; consumers dedupe on eventId) — no row is ever lost.
        Map<Long, CompletableFuture<?>> inFlight = new LinkedHashMap<>();
        Map<Long, String> topicById = new LinkedHashMap<>();
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

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                record.headers().add(h.getKey(), OutboxAppender.utf8(h.getValue()));
            }
            topicById.put(id, topic);
            try {
                inFlight.put(id, relayTemplate.send(record));
            } catch (RuntimeException sendError) {
                // Synchronous send failure (e.g. producer buffer exhausted) — treat as transient and let
                // the await pass leave the row PENDING for the next poll.
                inFlight.put(id, CompletableFuture.failedFuture(sendError));
            }
        }

        // Await all acks. Each successful row is collected for a single bulk PUBLISHED mark; failures are
        // classified after the whole batch is in, so we can tell a broker-wide outage from a poison row.
        List<Long> published = new ArrayList<>();
        Map<Long, Throwable> failures = new LinkedHashMap<>();
        Timer.Sample sample = publishTimer != null ? Timer.start(meterRegistry) : null;
        for (Map.Entry<Long, CompletableFuture<?>> e : inFlight.entrySet()) {
            try {
                e.getValue().get(publishTimeoutMs, TimeUnit.MILLISECONDS);
                published.add(e.getKey());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                failures.put(e.getKey(), ie);
            } catch (Exception ex) {
                failures.put(e.getKey(), ex);
            }
        }
        if (sample != null) {
            sample.stop(publishTimer);
        }

        markPublished(published);
        countPublished(published, topicById);

        // A row that failed while others in the same batch succeeded is row-specific — count it toward the
        // dead-letter backstop. If NOTHING in the batch succeeded, treat it as a broker-wide outage and
        // leave the rows PENDING untouched (no attempt bump, no DEAD) so a transient outage can never bleed
        // the whole backlog into the dead-letter state — it simply retries next poll.
        boolean brokerReachable = !published.isEmpty();
        for (Map.Entry<Long, Throwable> f : failures.entrySet()) {
            long id = f.getKey();
            String topic = topicById.get(id);
            Throwable ex = f.getValue();
            if (isPermanent(ex)) {
                deadLetter(id, topic, ex.getMessage());
            } else if (brokerReachable) {
                int attempts = bumpAttempts(id, ex.getMessage());
                if (attempts >= maxAttempts) {
                    markDead(id);
                    log.error("Dead-lettered outbox row {} to {} after {} failed attempts: {}",
                            id, topic, attempts, ex.getMessage());
                } else {
                    log.error("Failed to relay outbox row {} to {} (attempt {}): {}",
                            id, topic, attempts, ex.getMessage());
                }
            } else {
                log.warn("Outbox row {} to {} not relayed (broker unreachable this poll), staying PENDING: {}",
                        id, topic, ex.getMessage());
            }
        }
    }

    /** Marks a batch of successfully-published rows PUBLISHED in one statement (bumping each attempt). */
    private void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = Timestamp.from(Instant.now());
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        jdbc.update("UPDATE outbox SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1 "
                + "WHERE id IN (" + placeholders + ")", args);
        log.debug("Relayed {} outbox rows", ids.size());
    }

    /** Bumps {@code outbox_published_total} per topic for the rows just marked PUBLISHED (no-op if no registry). */
    private void countPublished(List<Long> ids, Map<Long, String> topicById) {
        if (meterRegistry == null || ids.isEmpty()) {
            return;
        }
        Map<String, Long> perTopic = new LinkedHashMap<>();
        for (Long id : ids) {
            perTopic.merge(topicById.get(id), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : perTopic.entrySet()) {
            meterRegistry.counter("outbox_published_total", "topic", e.getKey()).increment(e.getValue());
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
