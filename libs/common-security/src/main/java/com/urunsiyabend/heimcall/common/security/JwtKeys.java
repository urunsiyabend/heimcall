package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.security.Jwks;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the signer's RSA keys (identity-service only): the active private key + its {@code kid}, the
 * derived active public key, and an optional retired public key kept published during a rotation overlap.
 *
 * <p>Serves two roles: it feeds {@link JwtIssuer} (sign with the active private key) and the JWKS endpoint
 * (publish the active + retired public keys), and it backs the signer's own in-process token verification
 * (so identity does not call its own JWKS over HTTP).
 */
public class JwtKeys {

    private final RSAPrivateCrtKey privateKey;
    private final String activeKid;
    private final Map<String, PublicKey> publicByKid = new LinkedHashMap<>();

    public JwtKeys(JwtProperties props, ResourceLoader resourceLoader) {
        String pem = props.getPrivateKey();
        if (pem == null || pem.isBlank()) {
            pem = readResource(props.getPrivateKeyLocation(), resourceLoader);
        }
        this.privateKey = PemKeys.privateKey(pem);
        this.activeKid = props.getKeyId();
        if (activeKid == null || activeKid.isBlank()) {
            throw new IllegalStateException("heimcall.jwt.key-id must be set on the signer");
        }
        publicByKid.put(activeKid, PemKeys.publicFrom(privateKey));

        String retiredPem = props.getRetiredPublicKey();
        if (retiredPem != null && !retiredPem.isBlank()) {
            if (props.getRetiredKeyId() == null || props.getRetiredKeyId().isBlank()) {
                throw new IllegalStateException("heimcall.jwt.retired-key-id required when a retired key is set");
            }
            publicByKid.put(props.getRetiredKeyId(), PemKeys.publicKey(retiredPem));
        }
    }

    public RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    public String activeKid() {
        return activeKid;
    }

    /** Public key for a {@code kid}, or {@code null} if unknown. Used by the signer's local verifier. */
    public PublicKey publicKey(String kid) {
        return publicByKid.get(kid);
    }

    /** The JWKS document ({@code {"keys":[...]}}) for the {@code .well-known/jwks.json} endpoint. */
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = publicByKid.entrySet().stream()
                .map(e -> jwk(e.getKey(), (RSAPublicKey) e.getValue()))
                .toList();
        return Map.of("keys", keys);
    }

    private static Map<String, Object> jwk(String kid, RSAPublicKey key) {
        // jjwt's Jwk is itself a Map; copy it so we can add the advisory use/alg members deterministically.
        Map<String, Object> jwk = new LinkedHashMap<>(Jwks.builder().key(key).id(kid).build());
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        return jwk;
    }

    private static String readResource(String location, ResourceLoader loader) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("no signer key: set heimcall.jwt.private-key or private-key-location");
        }
        Resource resource = loader.getResource(location);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read signer key from " + location, e);
        }
    }
}
