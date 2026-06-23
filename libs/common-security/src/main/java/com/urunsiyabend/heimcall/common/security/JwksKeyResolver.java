package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves verification keys from the issuer's JWKS endpoint over HTTP. The key set is cached in memory and
 * refreshed lazily: a token whose {@code kid} is not cached triggers one refetch (so key rotation is picked
 * up without a restart), throttled to {@link #minRefetchInterval} to bound the load on the issuer when an
 * unknown {@code kid} is bogus or the endpoint is down.
 */
public class JwksKeyResolver implements PublicKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyResolver.class);

    private final String jwksUri;
    private final HttpClient http;
    private final Duration minRefetchInterval;

    private volatile Map<String, PublicKey> cache = Map.of();
    private volatile Instant lastFetch = Instant.EPOCH;

    public JwksKeyResolver(String jwksUri) {
        this(jwksUri, Duration.ofSeconds(30));
    }

    public JwksKeyResolver(String jwksUri, Duration minRefetchInterval) {
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException("heimcall.jwt.jwks-uri must be set on a verifier service");
        }
        this.jwksUri = jwksUri;
        this.minRefetchInterval = minRefetchInterval;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public PublicKey resolve(String kid) {
        PublicKey key = cache.get(kid);
        if (key != null) {
            return key;
        }
        refreshIfDue();
        return cache.get(kid);
    }

    private synchronized void refreshIfDue() {
        // Re-check under the lock; a concurrent caller may have just refreshed (single-flight).
        if (Instant.now().isBefore(lastFetch.plus(minRefetchInterval))) {
            return;
        }
        lastFetch = Instant.now();
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(jwksUri)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("JWKS fetch from {} returned {}", jwksUri, resp.statusCode());
                return;
            }
            Map<String, PublicKey> next = new HashMap<>();
            for (Jwk<?> jwk : Jwks.setParser().build().parse(resp.body()).getKeys()) {
                if (jwk instanceof PublicJwk<?> pub && jwk.getId() != null) {
                    next.put(jwk.getId(), pub.toKey());
                }
            }
            cache = Map.copyOf(next);
        } catch (Exception e) {
            log.warn("JWKS fetch from {} failed: {}", jwksUri, e.toString());
        }
    }
}
