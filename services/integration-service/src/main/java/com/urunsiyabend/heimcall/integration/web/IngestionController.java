package com.urunsiyabend.heimcall.integration.web;

import com.urunsiyabend.heimcall.integration.AlertNormalizer;
import com.urunsiyabend.heimcall.integration.InvalidIntegrationKeyException;
import com.urunsiyabend.heimcall.integration.KeyResolutionUnavailableException;
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

/**
 * Generic webhook ingestion endpoint.
 *
 * <p>A 202 means the event was durably accepted into the outbox (it will be published to Kafka by the
 * relay), not that it has reached the broker or been processed downstream. The response carries the
 * per-request {@code eventId} (idempotency / request handle) and the {@code dedupKey} (the alert
 * correlation key the caller reuses for follow-up ACK/RECOVERY signals, à la PagerDuty's dedup_key).
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

        AlertNormalizer.Accepted accepted = normalizer.normalizeAndPublish(integrationKey, routingKey, request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "accepted",
                        "eventId", accepted.eventId().toString(),
                        "dedupKey", accepted.dedupKey()));
    }

    /** Unknown / inactive integration key. */
    @ExceptionHandler(InvalidIntegrationKeyException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> onInvalidKey(InvalidIntegrationKeyException e) {
        return Map.of("status", "rejected", "reason", "invalid integration key");
    }

    /** Key could not be validated because identity-service is unreachable. */
    @ExceptionHandler(KeyResolutionUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onResolutionUnavailable(KeyResolutionUnavailableException e) {
        return Map.of("status", "rejected", "reason", "cannot validate integration key, retry");
    }
}
