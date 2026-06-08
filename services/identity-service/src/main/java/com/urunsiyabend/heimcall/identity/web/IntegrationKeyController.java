package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.IntegrationKeyService;
import com.urunsiyabend.heimcall.identity.domain.IntegrationKey;
import com.urunsiyabend.heimcall.identity.domain.IntegrationKeyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/integration-keys")
public class IntegrationKeyController {

    private final IntegrationKeyService keyService;
    private final IntegrationKeyRepository keys;
    private final TenantGuard guard;

    public IntegrationKeyController(IntegrationKeyService keyService, IntegrationKeyRepository keys, TenantGuard guard) {
        this.keyService = keyService;
        this.keys = keys;
        this.guard = guard;
    }

    public record CreateRequest(@NotBlank String name) {
    }

    /** Returned once at creation; {@code key} is the plaintext and is never recoverable later. */
    public record IssuedResponse(UUID id, UUID integrationId, String name, String keyPrefix, String key) {
    }

    public record KeyResponse(UUID id, UUID integrationId, String name, String keyPrefix, boolean active, Instant createdAt) {
        static KeyResponse of(IntegrationKey k) {
            return new KeyResponse(k.getId(), k.getIntegrationId(), k.getName(), k.getKeyPrefix(), k.isActive(), k.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedResponse issue(@PathVariable UUID orgId,
                                @RequestHeader("X-User-Id") UUID callerId,
                                @Valid @RequestBody CreateRequest req) {
        guard.requireMember(orgId, callerId);
        IntegrationKeyService.Issued issued = keyService.issue(orgId, req.name());
        IntegrationKey k = issued.key();
        return new IssuedResponse(k.getId(), k.getIntegrationId(), k.getName(), k.getKeyPrefix(), issued.plaintext());
    }

    @GetMapping
    public List<KeyResponse> list(@PathVariable UUID orgId,
                                  @RequestHeader("X-User-Id") UUID callerId) {
        guard.requireMember(orgId, callerId);
        return keys.findByOrganizationId(orgId).stream().map(KeyResponse::of).toList();
    }
}
