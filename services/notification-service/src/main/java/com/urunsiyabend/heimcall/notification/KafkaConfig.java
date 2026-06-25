package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer resilience for {@code notification.requested.v1}: bounded retry then dead-letter, mirroring
 * the other consumers (Phase 3.5). A failed request event is retried, then routed to {@code <topic>.DLT}
 * so the partition is never blocked. Outbound {@code notification.delivered.v1} /
 * {@code notification.failed.v1} events go through the transactional outbox (Phase 9 T2), not a
 * producer bean here.
 */
@Configuration
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 2L;

    /**
     * Phase 18 T3: provision {@code notification.requested.v1} with N partitions up front so consumer
     * parallelism (the {@code spring.kafka.listener.concurrency} setting) has partitions to spread
     * across — a single-partition topic caps the consumer group at one active thread regardless of
     * concurrency. Keyed by incidentId (set by the escalation producer), so per-incident page ordering
     * is preserved within a partition while distinct incidents fan out across the group. Partitions are
     * provisioned at creation, never ALTERed live: adding partitions to a keyed topic rehashes keys and
     * breaks ordering, so a fresh environment gets N from the start.
     */
    @Bean
    public NewTopic notificationRequestedTopic(
            @Value("${heimcall.notification.requested-topic.partitions:4}") int partitions,
            @Value("${heimcall.notification.requested-topic.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.NOTIFICATION_REQUESTED)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> dltProducerFactory(KafkaProperties properties) {
        // Ordered (byte[] checked before Object) + assignable matching: a deserialized event object
        // matches Object -> JSON; a raw poison-pill byte[] matches byte[] -> raw. Map.of order is
        // undefined, so a LinkedHashMap is required to keep byte[] ahead of the Object catch-all.
        // Exact-match (the prior default) only handled the poison-pill case and threw
        // SerializationException ("No matching delegate for type: NotificationRequestedEvent") on any
        // deserialized-object value, which silently broke dead-lettering for every application
        // exception (e.g. a null-org request) -> permanent single-partition stall, delivered=0.
        Map<Class<?>, org.apache.kafka.common.serialization.Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null),
                new StringSerializer(),
                new DelegatingByTypeSerializer(delegates, true));
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
}
