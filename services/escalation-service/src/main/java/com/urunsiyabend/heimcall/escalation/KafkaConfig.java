package com.urunsiyabend.heimcall.escalation;

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

import java.util.Map;

/**
 * Consumer resilience: bounded retry then dead-letter, mirroring incident-service (Phase 3.5).
 * A failed incident event is retried, then routed to {@code <topic>.DLT} so the partition is never
 * blocked. Outbound {@code notification.requested.v1} events go through the transactional outbox
 * (Phase 9 T2), not a producer bean here.
 */
@Configuration
public class KafkaConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 2L;

    /**
     * Phase 20 T2: provision {@code incident.lifecycle.v1} with N partitions up front so the incident
     * consumer group can scale past one active thread. Keyed by incidentId (Phase 12), so all four
     * lifecycle events for one incident stay ordered within a partition (TRIGGERED before the ACK/RESOLVE
     * that cancels it) while distinct incidents fan out across the group. Provisioned at creation, never
     * ALTERed live: adding partitions to a keyed topic rehashes keys and breaks that ordering — a fresh
     * environment gets N from the start (an existing 1-partition dev topic must be recreated).
     */
    @Bean
    public NewTopic incidentLifecycleTopic(
            @Value("${heimcall.incident-lifecycle-topic.partitions:4}") int partitions,
            @Value("${heimcall.incident-lifecycle-topic.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.INCIDENT_LIFECYCLE)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

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
}
