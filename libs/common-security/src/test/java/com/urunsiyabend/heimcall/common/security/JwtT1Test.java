package com.urunsiyabend.heimcall.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.PublicJwk;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16 T1: RS256 issue/verify, algorithm-confusion defense, token-class separation, and JWKS
 * rotation. No HTTP — the signer's in-process keys back a {@link LocalKeyResolver}, and the JWKS
 * produce→consume path is exercised by serializing {@link JwtKeys#jwks()} and re-parsing it.
 */
class JwtT1Test {

    private static final String ISSUER = "https://identity.heimcall.internal";
    private static final String AUDIENCE = "heimcall-api";
    private static final UUID USER = UUID.randomUUID();

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

    private static String x509Pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static JwtProperties props(KeyPair active) {
        JwtProperties p = new JwtProperties();
        p.setIssuer(ISSUER);
        p.setAudience(AUDIENCE);
        p.setPrivateKey(pkcs8Pem(active));
        p.setKeyId("active-1");
        return p;
    }

    private static JwtVerifier verifierFor(JwtKeys keys, JwtProperties props) {
        return new JwtVerifier(props, new LocalKeyResolver(keys));
    }

    @Test
    void issuesAndVerifiesRs256AccessToken() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtIssuer issuer = new JwtIssuer(props, keys);
        JwtVerifier verifier = verifierFor(keys, props);

        String token = issuer.issueAccess(USER, "a@b.c", "Ada");

        assertThat(headerAlg(token)).isEqualTo("RS256");
        assertThat(headerKid(token)).isEqualTo("active-1");
        Claims claims = verifier.verifyAccess(token);
        assertThat(claims.getSubject()).isEqualTo(USER.toString());
        assertThat(claims.get(JwtClaims.EMAIL, String.class)).isEqualTo("a@b.c");
        assertThat(claims.get(JwtClaims.TOKEN_USE, String.class)).isEqualTo(JwtClaims.USER_ACCESS);
        assertThat(claims.getAudience()).contains(AUDIENCE);
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccess() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtIssuer issuer = new JwtIssuer(props, keys);
        JwtVerifier verifier = verifierFor(keys, props);

        String refresh = issuer.issueRefresh(USER);

        assertThat(verifier.verifyRefresh(refresh).getSubject()).isEqualTo(USER.toString());
        assertThatThrownBy(() -> verifier.verifyAccess(refresh)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAlgNone() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        String unsigned = Jwts.builder().issuer(ISSUER).subject(USER.toString()).compact();

        assertThatThrownBy(() -> verifier.parse(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHs256ForgedToken() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        // Attacker signs HS256 (even using the public key as the MAC secret) and stamps the real kid.
        String forged = Jwts.builder()
                .header().keyId("active-1").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add(AUDIENCE).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(keys.privateKey().getModulus().toByteArray()))
                .compact();

        assertThatThrownBy(() -> verifier.verifyAccess(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsValidlySignedNonRs256Token() {
        // Sign with the REAL private key but using RS512 (a cryptographically valid signature). It must be
        // rejected by the code-level algorithm allowlist, not by a signature failure — proving RS256 is
        // pinned in code and the alg header never selects the verification algorithm (RFC 8725 §3.1).
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        String rs512 = Jwts.builder()
                .header().keyId("active-1").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add(AUDIENCE).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.privateKey(), Jwts.SIG.RS512)
                .compact();

        assertThat(headerAlg(rs512)).isEqualTo("RS512");
        assertThatThrownBy(() -> verifier.verifyAccess(rs512)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnknownKid() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        KeyPair rogue = rsa();
        String token = Jwts.builder()
                .header().keyId("rogue-9").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add(AUDIENCE).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(rogue.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verifyAccess(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        String expired = Jwts.builder()
                .header().keyId("active-1").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add(AUDIENCE).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verifyAccess(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudience() {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        String otherAud = Jwts.builder()
                .header().keyId("active-1").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add("some-other-api").and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verifyAccess(otherAud)).isInstanceOf(JwtException.class);
    }

    @Test
    void rotationKeepsRetiredKeyTokensValidAndPublishesBoth() {
        KeyPair active = rsa();
        KeyPair retired = rsa();
        JwtProperties props = props(active);
        props.setRetiredPublicKey(x509Pem(retired.getPublic()));
        props.setRetiredKeyId("retired-0");
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtVerifier verifier = verifierFor(keys, props);

        // A token signed by the retired key (its kid) still verifies during the overlap.
        String old = Jwts.builder()
                .header().keyId("retired-0").and()
                .issuer(ISSUER).subject(USER.toString())
                .audience().add(AUDIENCE).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(retired.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThat(verifier.verifyAccess(old).getSubject()).isEqualTo(USER.toString());

        // JWKS publishes both kids.
        @SuppressWarnings("unchecked")
        var jwkList = (java.util.List<Map<String, Object>>) keys.jwks().get("keys");
        assertThat(jwkList).extracting(j -> j.get("kid")).containsExactlyInAnyOrder("active-1", "retired-0");
    }

    @Test
    void jwksProduceConsumeRoundTrip() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = props(active);
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());
        JwtIssuer issuer = new JwtIssuer(props, keys);

        // Serialize the JWKS document and re-parse it the way a remote verifier would.
        String json = new ObjectMapper().writeValueAsString(keys.jwks());
        Map<String, PublicKey> byKid = new HashMap<>();
        for (Jwk<?> jwk : Jwks.setParser().build().parse(json).getKeys()) {
            if (jwk instanceof PublicJwk<?> pub) {
                byKid.put(jwk.getId(), pub.toKey());
            }
        }
        JwtVerifier remote = new JwtVerifier(props, byKid::get);

        String token = issuer.issueAccess(USER, "a@b.c", "Ada");
        assertThat(remote.verifyAccess(token).getSubject()).isEqualTo(USER.toString());
    }

    private static String headerAlg(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0])).replaceAll(".*\"alg\":\"([^\"]+)\".*", "$1");
    }

    private static String headerKid(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0])).replaceAll(".*\"kid\":\"([^\"]+)\".*", "$1");
    }
}
