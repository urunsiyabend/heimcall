package com.urunsiyabend.heimcall.common.observability;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Stamps the current correlation id (from the producing thread's MDC) onto every outbound Kafka record
 * as the {@code X-Correlation-Id} header, so consumers can continue the same logical trace.
 *
 * <p>Registered via {@code spring.kafka.producer.properties.interceptor.classes} so it applies to every
 * producer built from {@code KafkaProperties} without touching each service's Kafka config.
 */
public class CorrelationProducerInterceptor implements ProducerInterceptor<Object, Object> {

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        String id = CorrelationContext.getOrNull();
        if (id != null && record.headers().lastHeader(CorrelationContext.KAFKA_HEADER) == null) {
            record.headers().add(CorrelationContext.KAFKA_HEADER, id.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
