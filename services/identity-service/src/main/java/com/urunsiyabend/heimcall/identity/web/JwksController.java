package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.common.security.JwtKeys;
import com.urunsiyabend.heimcall.common.security.JwtProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the issuer's public keys (Phase 16 T1). Every other service fetches {@code jwks.json} to verify
 * RS256 tokens; the set carries the active key and any retired-but-not-yet-expired key, each tagged by
 * {@code kid}, so a key rotation is picked up without redeploying the verifiers. The authorization-server
 * metadata document points discovery clients at the issuer and this JWKS URI.
 */
@RestController
public class JwksController {

    private final JwtKeys keys;
    private final JwtProperties props;

    public JwksController(JwtKeys keys, JwtProperties props) {
        this.keys = keys;
        this.props = props;
    }

    @GetMapping("/v1/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.jwks();
    }

    @GetMapping("/v1/.well-known/oauth-authorization-server")
    public Map<String, Object> metadata() {
        return Map.of(
                "issuer", props.getIssuer(),
                "jwks_uri", "/v1/.well-known/jwks.json");
    }
}
