package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.NotificationDeliveredEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 13 T2: incident-service Kafka resilience over an in-JVM {@link EmbeddedKafka} broker (no Docker,
 * no DB). Proves the infra behavior unit tests can't reach: bounded-retry → dead-letter routing and the
 * type-header notification-feedback dispatch. {@link IncidentService} is mocked, so the slice loads the
 * real {@link KafkaConfig} (error handler, DLT recoverer, delegating serializer, type-header factory) and
 * Boot's Kafka auto-config, but no JPA/datasource.
 *
 * <p>The DLT regression guard: a record that fails <em>application</em> processing has a value already
 * deserialized to an {@link AlertReceivedEvent} object. The Phase 10 T1 fix made the DLT serializer match
 * by {@code isAssignableFrom} so that object is JSON-serialized; the prior exact-match threw
 * {@code SerializationException} on publish and silently broke dead-lettering for every application
 * exception. This test fails if that regresses.
 */
@SpringBootTest(
        classes = {KafkaConfig.class, AlertReceivedListener.class, NotificationEventListener.class,
                AlertReceivedResilienceTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {
        Topics.ALERT_RECEIVED, Topics.ALERT_RECEIVED + ".DLT",
        Topics.NOTIFICATION_DELIVERED, Topics.NOTIFICATION_FAILED})
class AlertReceivedResilienceTest {

    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @TestConfiguration
    static class TestConfig {
    }

    @MockBean
    IncidentService incidentService;

    @Autowired
    EmbeddedKafkaBroker broker;

    private static final String DLT = Topics.ALERT_RECEIVED + ".DLT";

    // ---- a poison-pill (un-deserializable payload) goes straight to the DLT (byte[] delegate) ----

    @Test
    void poisonPill_isDeadLetteredAsRawBytes() {
        String marker = "not-json-" + UUID.randomUUID();
        send(Topics.ALERT_RECEIVED, marker.getBytes(StandardCharsets.UTF_8), null);

        assertThat(dltContains(marker)).isTrue();
    }

    // ---- an APPLICATION exception (value already deserialized) is dead-lettered after retries ----
    // Guards the Phase 10 T1 assignable=true DLT-serializer fix (the deserialized event takes the
    // Object -> JSON delegate instead of throwing SerializationException on publish).

    @Test
    void applicationException_isDeadLetteredAfterRetries() {
        UUID marker = UUID.randomUUID();
        doThrow(new RoutingUnavailableException("backend-critical", new RuntimeException("catalog down")))
                .when(incidentService).handle(any());

        send(Topics.ALERT_RECEIVED, jsonBytes(criticalEvent(marker)), null);

        // 3 attempts (1 + 2 retries, 1s apart) then the recoverer publishes the deserialized event to DLT.
        assertThat(dltContains(marker.toString())).isTrue();
    }

    // ---- a successfully-handled event is NOT dead-lettered ----

    @Test
    void handledEvent_isNotDeadLettered() {
        UUID marker = UUID.randomUUID();
        send(Topics.ALERT_RECEIVED, jsonBytes(criticalEvent(marker)), null);

        // The engine is invoked...
        verify(incidentService, timeout(10_000)).handle(any(AlertReceivedEvent.class));
        // ...and nothing lands on the DLT for this event.
        assertThat(dltContains(marker.toString())).isFalse();
    }

    // ---- notification.delivered.v1 routes to the type-header feedback listener ----

    @Test
    void notificationDelivered_dispatchesToFeedbackListener() {
        NotificationDeliveredEvent ev = new NotificationDeliveredEvent(UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "EMAIL", "a@acme.io", UUID.randomUUID());
        // The type-header consumer factory selects the concrete type from __TypeId__.
        send(Topics.NOTIFICATION_DELIVERED, jsonBytes(ev),
                NotificationDeliveredEvent.class.getName());

        verify(incidentService, timeout(10_000)).recordDelivered(any(NotificationDeliveredEvent.class));
    }

    // ---- helpers ----

    private AlertReceivedEvent criticalEvent(UUID eventId) {
        return new AlertReceivedEvent(eventId, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "backend-critical", "grafana", MessageType.CRITICAL, "payment-5xx",
                "grafana:payment-5xx", "Payment API 5xx", "error rate high", Severity.CRITICAL, null);
    }

    private byte[] jsonBytes(Object event) {
        try (JsonSerializer<Object> json = new JsonSerializer<>()) {
            return json.serialize("t", event);
        }
    }

    private void send(String topic, byte[] value, String typeIdHeader) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", ByteArraySerializer.class);
        try (Producer<String, byte[]> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, "k", value);
            if (typeIdHeader != null) {
                record.headers().add(new RecordHeader("__TypeId__", typeIdHeader.getBytes(StandardCharsets.UTF_8)));
            }
            producer.send(record);
            producer.flush();
        }
    }

    /** Poll the DLT (fresh group, from the beginning) and report whether a value carries the marker. */
    private boolean dltContains(String marker) {
        Map<String, Object> props = new HashMap<>(
                KafkaTestUtils.consumerProps("dlt-probe-" + UUID.randomUUID(), "true", broker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", ByteArrayDeserializer.class);
        try (Consumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(java.util.List.of(DLT));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> r : records) {
                    if (r.value() != null && new String(r.value(), StandardCharsets.UTF_8).contains(marker)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
