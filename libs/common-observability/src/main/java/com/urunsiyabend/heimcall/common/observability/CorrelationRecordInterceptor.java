package com.urunsiyabend.heimcall.common.observability;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Per-record correlation id for Kafka listeners. Runs immediately before each listener invocation on the
 * container thread: lifts {@code X-Correlation-Id} from the record header (or mints one) into the MDC, and
 * clears it after the record is handled — so listener logs carry the id from the producing request.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(CorrelationContext.KAFKA_HEADER);
        String id = header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        CorrelationContext.set(CorrelationContext.orNew(id));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        CorrelationContext.clear();
    }
}
