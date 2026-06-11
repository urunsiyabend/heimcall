package com.urunsiyabend.heimcall.common.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Shared constants + helpers for the correlation id that ties one logical request together across
 * HTTP hops and Kafka messages. The id lives in the SLF4J {@link MDC} so the logback JSON encoder
 * emits it on every log line of the handling thread.
 */
public final class CorrelationContext {

    /** Inbound/outbound HTTP header. */
    public static final String HEADER = "X-Correlation-Id";
    /** Kafka record header. */
    public static final String KAFKA_HEADER = "X-Correlation-Id";
    /** MDC key (becomes a field in the JSON log). */
    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static String getOrNull() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /** Returns the given id if non-blank, else a fresh random one. */
    public static String orNew(String maybe) {
        return (maybe == null || maybe.isBlank()) ? UUID.randomUUID().toString() : maybe;
    }
}
