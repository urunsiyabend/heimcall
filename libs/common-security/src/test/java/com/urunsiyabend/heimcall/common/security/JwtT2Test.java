package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16 T2: the verifier side of service (machine) tokens. The authorization server (Spring Authorization
 * Server, identity-service) mints these; here we prove {@link JwtVerifier#verifyService} enforces the trust
 * boundary callers depend on in T3 — a service token is honored only at the audience it was minted for, and
 * the user/service token classes are never interchangeable. Tokens are hand-signed with the signer's key
 * (same RS256 path SAS uses), no HTTP.
 */
class JwtT2Test {

    private static final String ISSUER = "https://identity.heimcall.internal";
    private static final UUID CALLER = UUID.randomUUID();

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String pkcs8Pem(KeyPair kp) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static JwtProperties props(KeyPair active) {
        JwtProperties p = new JwtProperties();
        p.setIssuer(ISSUER);
        p.setAudience("heimcall-api");
        p.setPrivateKey(pkcs8Pem(active));
        p.setKeyId("active-1");
        return p;
    }

    /** A service token addressed to {@code aud}, signed RS256 with the active key (mirrors SAS output). */
    private static String serviceToken(JwtKeys keys, String aud, String scope) {
        return Jwts.builder()
                .header().keyId("active-1").and()
                .issuer(ISSUER)
                .subject("incident")
                .audience().add(aud).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.SERVICE)
                .claim(JwtClaims.SCOPE, scope)
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void verifyServiceAcceptsTokenForItsAudience() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = new JwtVerifier(props, new LocalKeyResolver(keys));

        Claims claims = verifier.verifyService(serviceToken(keys, "catalog", "catalog.routing.resolve"), "catalog");

        assertThat(claims.getSubject()).isEqualTo("incident");
        assertThat(claims.get(JwtClaims.TOKEN_USE, String.class)).isEqualTo(JwtClaims.SERVICE);
        assertThat(claims.get(JwtClaims.SCOPE, String.class)).isEqualTo("catalog.routing.resolve");
    }

    @Test
    void verifyServiceRejectsTokenMintedForAnotherAudience() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = new JwtVerifier(props, new LocalKeyResolver(keys));

        String forCatalog = serviceToken(keys, "catalog", "catalog.routing.resolve");

        // A token minted for catalog must not authenticate a call to identity.
        assertThatThrownBy(() -> verifier.verifyService(forCatalog, "identity")).isInstanceOf(JwtException.class);
    }

    @Test
    void userTokenIsNotAcceptedAsService_andViceVersa() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtIssuer issuer = new JwtIssuer(props, keys);
        JwtVerifier verifier = new JwtVerifier(props, new LocalKeyResolver(keys));

        String userAccess = issuer.issueAccess(CALLER, "a@b.c", "Ada");
        String service = serviceToken(keys, "heimcall-api", "identity.membership.read");

        // token_use is the wall between the two classes, in both directions.
        assertThatThrownBy(() -> verifier.verifyService(userAccess, "heimcall-api")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verifyAccess(service)).isInstanceOf(JwtException.class);
    }
}
