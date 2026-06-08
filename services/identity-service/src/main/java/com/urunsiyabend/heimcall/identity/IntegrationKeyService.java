package com.urunsiyabend.heimcall.identity;

import com.urunsiyabend.heimcall.identity.domain.IntegrationKey;
import com.urunsiyabend.heimcall.identity.domain.IntegrationKeyRepository;
import com.urunsiyabend.heimcall.identity.domain.OrganizationRepository;
import com.urunsiyabend.heimcall.identity.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Issues and resolves integration keys. Plaintext is generated here, shown once, never stored. */
@Service
public class IntegrationKeyService {

    private static final String PREFIX = "hc_";
    private static final int SECRET_BYTES = 24;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

    private final IntegrationKeyRepository keys;
    private final OrganizationRepository organizations;

    public IntegrationKeyService(IntegrationKeyRepository keys, OrganizationRepository organizations) {
        this.keys = keys;
        this.organizations = organizations;
    }

    /** Result of issuing a key: the plaintext is only available here, at creation time. */
    public record Issued(IntegrationKey key, String plaintext) {
    }

    @Transactional
    public Issued issue(UUID organizationId, String name) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiExceptions.NotFoundException("organization not found: " + organizationId);
        }
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String plaintext = PREFIX + urlEncoder.encodeToString(secret);
        String prefix = plaintext.substring(0, Math.min(11, plaintext.length()));

        IntegrationKey key = IntegrationKey.issue(organizationId, name, prefix, hash(plaintext), Instant.now());
        keys.save(key);
        return new Issued(key, plaintext);
    }

    /** Resolve an inbound plaintext key to its active record, or 401 if unknown/inactive. */
    @Transactional(readOnly = true)
    public IntegrationKey resolve(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new ApiExceptions.UnauthorizedException("integration key is missing");
        }
        return keys.findByKeyHashAndActiveTrue(hash(plaintext))
                .orElseThrow(() -> new ApiExceptions.UnauthorizedException("invalid integration key"));
    }

    private String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
