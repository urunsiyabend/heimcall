package com.urunsiyabend.heimcall.integration;

import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.integration.web.WebhookRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a raw {@link WebhookRequest} into a normalized {@link AlertReceivedEvent}
 * and hands it to the transactional outbox. The dedup key collapses repeated signals from the same
 * source + entity onto one incident downstream and is returned to the caller as the correlation handle.
 *
 * <p>Reliability (Phase 9 T3): the raw payload audit row and the normalized event are written in one
 * transaction — the {@code outbox} INSERT joins the same tx as the {@code raw_inbound_event} row, so a
 * rollback writes neither (no ghost) and a commit durably accepts the event (never lost). The
 * {@code OutboxRelay} publishes the row to Kafka asynchronously with confirm; ingestion no longer blocks
 * on the broker. A 202 therefore means "durably accepted", not "published to Kafka".
 *
 * <p>Phase 1a: the integration key is resolved against identity-service to obtain the real
 * organization + integration id. An invalid key is rejected (401) before anything is stored.
 *
 * <p>Phase 10 (load-driven): the identity-service resolve hop is a ~25ms synchronous HTTP call that does
 * not belong inside the persistence transaction — holding a pooled DB connection (HikariCP max 10) across
 * a network call caps ingest throughput far more than the call's own latency. Resolution therefore runs
 * here, outside any transaction; only the two writes are handed to {@link AlertEventWriter} under one tx.
 */
@Service
public class AlertNormalizer {

    private final IdentityClient identityClient;
    private final AlertEventWriter writer;

    public AlertNormalizer(IdentityClient identityClient, AlertEventWriter writer) {
        this.identityClient = identityClient;
        this.writer = writer;
    }

    /** Outcome of a successful ingestion: the per-request event id plus the alert correlation key. */
    public record Accepted(UUID eventId, String dedupKey) {
    }

    public Accepted normalizeAndPublish(String integrationKey, String routingKey, WebhookRequest request) {
        // Validate + resolve the key first, OUTSIDE the tx; an invalid key is rejected (401) before we
        // store anything, and no DB connection is held during the identity-service network call.
        IdentityClient.Resolution tenant = identityClient.resolve(integrationKey);

        UUID eventId = UUID.randomUUID();
        String dedupKey = request.source() + ":" + request.entityId();

        AlertReceivedEvent event = new AlertReceivedEvent(
                eventId,
                Instant.now(),
                tenant.organizationId(),
                tenant.integrationId(),
                routingKey,
                request.source(),
                request.messageType(),
                request.entityId(),
                dedupKey,
                request.entityDisplayName() != null ? request.entityDisplayName() : request.entityId(),
                request.stateMessage(),
                request.severity(),
                request.metadata() != null ? request.metadata() : Map.of()
        );

        // Raw audit row + outbox row, atomically (no ghost / never lost). Resolution already done.
        writer.persist(eventId, integrationKey, routingKey, request, dedupKey, event);

        return new Accepted(eventId, dedupKey);
    }
}
