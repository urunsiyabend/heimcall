package com.urunsiyabend.heimcall.catalog;

import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import com.urunsiyabend.heimcall.common.outbox.OutboxAppender;
import com.urunsiyabend.heimcall.routing.Ruleset;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes the full routing ruleset snapshot for an org (Phase 17 T2) by appending it to the
 * transactional outbox in the caller's transaction — so it commits atomically with the rule write
 * (never a ghost, never lost) and the common-outbox relay forwards it to Kafka. Keyed by
 * organizationId so the topic holds the latest snapshot per org and per-org ordering is preserved.
 */
@Component
public class RoutingSnapshotPublisher {

    private static final String AGGREGATE_TYPE = "routing-ruleset";

    private final OutboxAppender outbox;

    public RoutingSnapshotPublisher(OutboxAppender outbox) {
        this.outbox = outbox;
    }

    public void publish(UUID organizationId, Ruleset ruleset) {
        RoutingRulesetSnapshotEvent event = new RoutingRulesetSnapshotEvent(
                UUID.randomUUID(), organizationId, ruleset.version(), ruleset, Instant.now());
        outbox.append(AGGREGATE_TYPE, organizationId.toString(), Topics.ROUTING_RULESET_PUBLISHED,
                organizationId.toString(), event);
    }
}
