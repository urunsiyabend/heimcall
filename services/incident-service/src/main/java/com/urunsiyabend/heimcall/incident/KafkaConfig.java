package com.urunsiyabend.heimcall.incident;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer resilience (Phase 3.5): bounded retry then dead-letter.
 *
 * <p>A processing failure is retried a fixed number of times; once exhausted (or immediately
 * for a poison-pill that fails deserialization) the record is routed to
 * {@code alert.received.v1.DLT} so the partition is never blocked. The DLT producer uses a
 * delegating serializer: JSON for live events, raw bytes for the original payload of a
 * record that failed deserialization.
 *
 * <p>The delegating serializer matches by {@code isAssignableFrom} (assignable=true) over an ordered
 * map (byte[] first), so a record that failed <em>application</em> processing — its value already
 * deserialized to an event object (e.g. a routing-unavailable retry exhaustion, Phase 10) — is
 * JSON-serialized via the {@code Object} delegate, while a poison-pill (raw byte[], deserialization
 * failure) still takes the {@code byte[]} delegate. Exact-match (the prior default) only handled the
 * poison-pill case and threw {@code SerializationException} on any deserialized-object value, which
 * silently broke dead-lettering for every application exception.
 */
@Configuration
public class KafkaConfig {

    // 3 total delivery attempts (1 initial + 2 retries), 1s apart.
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 2L;

    @Bean
    public ProducerFactory<String, Object> dltProducerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildProducerProperties(null);
        // Ordered (byte[] checked before Object) + assignable matching: a deserialized event object
        // matches Object -> JSON; a raw poison-pill byte[] matches byte[] -> raw. Map.of order is
        // undefined, so a LinkedHashMap is required to keep byte[] ahead of the Object catch-all.
        Map<Class<?>, org.apache.kafka.common.serialization.Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());
        return new DefaultKafkaProducerFactory<>(
                config,
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

    // Outbound incident lifecycle events (incident.triggered/acknowledged/resolved/canceled) now go
    // through the transactional outbox (Phase 9): IncidentEventPublisher appends them in the same tx as
    // the incident change, and common-outbox's relay publishes them. No outbound KafkaTemplate here.

    // The default (Boot-autoconfigured) consumer pins every record to AlertReceivedEvent
    // (use.type.headers=false). notification.delivered/failed carry two distinct types on two
    // topics, so this consumer takes the concrete type from the producer's type header instead.
    @Bean
    public ConsumerFactory<String, Object> notificationConsumerFactory(KafkaProperties properties) {
        Map<String, Object> props = properties.buildConsumerProperties(null);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "incident-service.notification-consumer");
        // The deserializer instance below is configured via setters; drop the yaml-inherited
        // spring.json/spring.deserializer config props so it isn't configured "both" ways
        // (JsonDeserializer rejects that combination).
        props.keySet().removeIf(k -> k.startsWith("spring.json.") || k.startsWith("spring.deserializer."));
        JsonDeserializer<Object> delegate = new JsonDeserializer<>();
        delegate.addTrustedPackages(
                "com.urunsiyabend.heimcall.common.events",
                "com.urunsiyabend.heimcall.common.domain");
        // Poison-pill safe: a malformed payload surfaces as a DeserializationException -> DLT.
        ErrorHandlingDeserializer<Object> value = new ErrorHandlingDeserializer<>(delegate);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), value);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> notificationListenerContainerFactory(
            ConsumerFactory<String, Object> notificationConsumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationConsumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
