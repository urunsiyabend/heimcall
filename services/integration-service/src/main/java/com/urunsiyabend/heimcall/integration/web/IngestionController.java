package com.urunsiyabend.heimcall.integration.web;

import com.urunsiyabend.heimcall.integration.AlertNormalizer;
import com.urunsiyabend.heimcall.integration.EventPublishException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Generic webhook ingestion endpoint.
 * Sprint 1: integration-key validation is stubbed (identity-service arrives in a later phase);
 * any key is accepted and the payload is normalized and published to Kafka.
 */
@RestController
@RequestMapping("/v1/integrations")
public class IngestionController {

    private final AlertNormalizer normalizer;

    public IngestionController(AlertNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @PostMapping("/{integrationKey}/events/{routingKey}")
    public ResponseEntity<Map<String, String>> ingest(
            @PathVariable String integrationKey,
            @PathVariable String routingKey,
            @Valid @RequestBody WebhookRequest request) {

        UUID eventId = normalizer.normalizeAndPublish(integrationKey, routingKey, request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "eventId", eventId.toString()));
    }

    /** A publish that the broker never confirmed is a server-side failure, not a client error. */
    @ExceptionHandler(EventPublishException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onPublishFailure(EventPublishException e) {
        return Map.of("status", "rejected", "reason", "event not published, retry");
    }
}
