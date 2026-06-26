package com.urunsiyabend.heimcall.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.Map;

/**
 * Wires the transactional outbox into any service that has this lib plus spring-kafka and a
 * {@link JdbcTemplate} on the classpath. The relay's Kafka template is built here as a plain object
 * (not a bean) so the observability {@code KafkaTracing} BeanPostProcessor never enables observation
 * on it — see {@link OutboxRelay}.
 */
@AutoConfiguration
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
@EnableScheduling
public class HeimcallOutboxAutoConfiguration {

    @Bean
    public OutboxAppender outboxAppender(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                         ObjectProvider<Tracer> tracerProvider) {
        return new OutboxAppender(jdbcTemplate, objectMapper, tracerProvider);
    }

    @Bean
    public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, KafkaProperties kafkaProperties,
                                   ObjectMapper objectMapper, ObjectProvider<MeterRegistry> meterRegistryProvider,
                                   @Value("${heimcall.outbox.batch-size:200}") int batchSize,
                                   @Value("${heimcall.outbox.publish-timeout-ms:10000}") long publishTimeoutMs,
                                   @Value("${heimcall.outbox.max-attempts:10}") int maxAttempts) {
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties(null);
        // Never-lose on the relay leg: confirmed, idempotent writes; raw byte[] value (the appender
        // already serialized the payload, and the type lives in the __TypeId__ header).
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        KafkaTemplate<String, byte[]> relayTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerProps, new StringSerializer(), new ByteArraySerializer()));
        return new OutboxRelay(jdbcTemplate, relayTemplate, objectMapper, batchSize, publishTimeoutMs,
                maxAttempts, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public OutboxPrune outboxPrune(JdbcTemplate jdbcTemplate,
                                   @Value("${heimcall.outbox.prune.retention:P7D}") String retention) {
        return new OutboxPrune(jdbcTemplate, Duration.parse(retention));
    }
}
