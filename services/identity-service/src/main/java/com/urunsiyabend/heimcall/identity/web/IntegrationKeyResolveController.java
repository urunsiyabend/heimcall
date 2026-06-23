package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.IntegrationKeyService;
import com.urunsiyabend.heimcall.identity.domain.IntegrationKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal resolution endpoint consumed by integration-service to turn an inbound integration
 * key into its organization + integration id. Not org-scoped (the caller holds only the key).
 *
 * <p>Phase 16 T3: requires a service token addressed to {@code identity} with scope
 * {@code identity.integration-key.resolve}. This endpoint is reachable through the gateway
 * ({@code /v1/integration-keys/**}); the scope gate closes that external exposure.
 */
@RestController
@RequestMapping("/v1/integration-keys")
public class IntegrationKeyResolveController {

    private final IntegrationKeyService keyService;

    public IntegrationKeyResolveController(IntegrationKeyService keyService) {
        this.keyService = keyService;
    }

    public record ResolveRequest(@NotBlank String key) {
    }

    public record ResolveResponse(UUID organizationId, UUID integrationId, String name) {
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasAuthority('SCOPE_identity.integration-key.resolve')")
    public ResolveResponse resolve(@Valid @RequestBody ResolveRequest req) {
        IntegrationKey key = keyService.resolve(req.key());
        return new ResolveResponse(key.getOrganizationId(), key.getIntegrationId(), key.getName());
    }
}
