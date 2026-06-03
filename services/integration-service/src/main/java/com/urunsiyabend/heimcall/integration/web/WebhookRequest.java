package com.urunsiyabend.heimcall.integration.web;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Generic inbound webhook payload sent by an external monitoring system.
 * Mirrors the public API draft in docs/02-prd.md section 12.1.
 */
public record WebhookRequest(
        @NotNull MessageType messageType,
        @NotBlank String entityId,
        String entityDisplayName,
        String stateMessage,
        String service,
        Severity severity,
        @NotBlank String source,
        Map<String, String> metadata
) {
}
