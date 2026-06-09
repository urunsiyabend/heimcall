package com.urunsiyabend.heimcall.escalation;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Consumer resilience: bounded retry then dead-letter, mirroring incident-service (Phase 3.5).
 * A failed incident event is retried, then routed to {@code <topic>.DLT} so the partition is never
 * blocked. Also configures the producer for outbound {@code notification.requested.v1} events.
 */
@Configuration
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 2L;

    @Bean
    public ProducerFactory<String, Object> dltProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null),
                new StringSerializer(),
                new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(),
                        Object.class, new JsonSerializer<>())));
    }

    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(ProducerFactory<String, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> dltKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    // Outbound notification.requested.v1 events. JSON with type-info headers.
    @Bean
    public ProducerFactory<String, Object> eventsProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null), new StringSerializer(), new JsonSerializer<>());
    }

    @Bean
    public KafkaTemplate<String, Object> eventsKafkaTemplate(ProducerFactory<String, Object> eventsProducerFactory) {
        return new KafkaTemplate<>(eventsProducerFactory);
    }
}
