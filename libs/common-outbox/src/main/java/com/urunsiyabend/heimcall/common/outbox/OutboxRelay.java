package com.urunsiyabend.heimcall.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
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

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> relayTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long publishTimeoutMs;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> relayTemplate,
                       ObjectMapper objectMapper, int batchSize, long publishTimeoutMs) {
        this.jdbc = jdbc;
        this.relayTemplate = relayTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${heimcall.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, topic, msg_key, payload, headers FROM outbox WHERE status = 'PENDING' "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                batchSize);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String topic = (String) row.get("topic");
            String key = (String) row.get("msg_key");
            byte[] payload = (byte[]) row.get("payload");
            String headersJson = (String) row.get("headers");
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
                for (Map.Entry<String, String> h : parseHeaders(headersJson).entrySet()) {
                    record.headers().add(h.getKey(), OutboxAppender.utf8(h.getValue()));
                }
                relayTemplate.send(record).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
                jdbc.update("UPDATE outbox SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(Instant.now()), id);
                log.debug("Relayed outbox row {} to {}", id, topic);
            } catch (Exception e) {
                // Leave the row PENDING for the next poll; record the failure for visibility. A broker
                // outage fails the whole batch, so stop this round instead of spinning on the rest.
                jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        truncate(e.getMessage()), id);
                log.error("Failed to relay outbox row {} to {}: {}", id, topic, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
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
