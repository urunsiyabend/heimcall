package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Consumes the routing ruleset snapshot stream (Phase 17 T2) into the local read-model. Versioned
 * at-least-once: the {@link RoutingProjectionStore} version-gates the upsert (older/duplicate snapshots
 * are no-ops) and commits the DB write before this method returns, so the Kafka offset advances only
 * after the projection is durably stored. A poison payload is dead-lettered by the container's error
 * handler; a newer snapshot logically supersedes any older one stuck in the DLT.
 */
@Component
public class RoutingSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(RoutingSnapshotConsumer.class);

    private final RoutingProjectionStore projections;
    private final MeterRegistry registry;

    public RoutingSnapshotConsumer(RoutingProjectionStore projections, MeterRegistry registry) {
        this.projections = projections;
        this.registry = registry;
    }

    @KafkaListener(topics = Topics.ROUTING_RULESET_PUBLISHED,
            containerFactory = "rulesetSnapshotListenerContainerFactory")
    public void onSnapshot(RoutingRulesetSnapshotEvent snapshot) {
        Instant now = Instant.now();
        boolean applied = projections.apply(snapshot, now);
        // Propagation lag from catalog publish to local apply (observability of the read-model freshness).
        registry.timer("routing.projection.apply_lag")
                .record(Duration.between(snapshot.publishedAt(), now));
        if (applied) {
            log.info("Routing projection updated org={} version={}",
                    snapshot.organizationId(), snapshot.rulesetVersion());
        }
    }
}
