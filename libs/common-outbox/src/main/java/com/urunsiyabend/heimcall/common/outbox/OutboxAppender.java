package com.urunsiyabend.heimcall.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends a domain event to the {@code outbox} table inside the caller's transaction (plain
 * {@link JdbcTemplate} INSERT on the transaction-bound connection). If the surrounding transaction
 * rolls back, the row rolls back with it (no ghost event); if it commits, the row is committed
 * atomically with the domain change and {@link OutboxRelay} publishes it later (never lost).
 *
 * <p>The serialized value plus the {@code __TypeId__} header reproduce exactly what Spring's
 * {@code JsonSerializer} would emit, so existing type-header-based consumers deserialize unchanged.
 * {@code X-Correlation-Id} and {@code traceparent} are captured from the appending thread so the
 * correlation id and distributed trace survive the asynchronous relay hop.
 */
public class OutboxAppender {

    // Mirror common-observability's CorrelationContext without depending on it.
    private static final String MDC_CORRELATION_KEY = "correlationId";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String TRACEPARENT_HEADER = "traceparent";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Tracer> tracerProvider;

    public OutboxAppender(JdbcTemplate jdbc, ObjectMapper objectMapper, ObjectProvider<Tracer> tracerProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tracerProvider = tracerProvider;
    }

    /**
     * @param aggregateType logical aggregate name (e.g. {@code "incident"}), for audit/replay.
     * @param aggregateId   aggregate id, for audit/replay.
     * @param topic         destination Kafka topic.
     * @param key           Kafka message key (preserves per-aggregate partition ordering); may be null.
     * @param payload       event object, serialized to JSON bytes.
     */
    public void append(String aggregateType, String aggregateId, String topic, String key, Object payload) {
        byte[] value;
        String headersJson;
        try {
            value = objectMapper.writeValueAsBytes(payload);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(TYPE_ID_HEADER, payload.getClass().getName());
            String correlationId = MDC.get(MDC_CORRELATION_KEY);
            if (correlationId != null) {
                headers.put(CORRELATION_HEADER, correlationId);
            }
            String traceparent = currentTraceparent();
            if (traceparent != null) {
                headers.put(TRACEPARENT_HEADER, traceparent);
            }
            headersJson = objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + topic, e);
        }
        jdbc.update(
                "INSERT INTO outbox (aggregate_type, aggregate_id, topic, msg_key, payload, headers, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                aggregateType, aggregateId, topic, key, value, headersJson, Timestamp.from(Instant.now()));
    }

    /** Current span as a W3C {@code traceparent} (sampled), or null if no tracer / no active span. */
    private String currentTraceparent() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        return "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
