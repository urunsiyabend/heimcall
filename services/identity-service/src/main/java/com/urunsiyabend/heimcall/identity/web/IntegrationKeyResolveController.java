package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.IntegrationKeyService;
import com.urunsiyabend.heimcall.identity.domain.IntegrationKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal resolution endpoint consumed by integration-service to turn an inbound integration
 * key into its organization + integration id. Not org-scoped (the caller holds only the key).
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
    public ResolveResponse resolve(@Valid @RequestBody ResolveRequest req) {
        IntegrationKey key = keyService.resolve(req.key());
        return new ResolveResponse(key.getOrganizationId(), key.getIntegrationId(), key.getName());
    }
}
