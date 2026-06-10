package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 JWTs. Access tokens carry the caller identity ({@code sub}=userId, plus
 * email/name); refresh tokens carry only the subject. The {@code type} claim distinguishes them so a
 * refresh token can never be used as an access token.
 */
public class JwtSupport {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtSupport(JwtProperties props) {
        this.props = props;
        if (props.getSecret() == null || props.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("heimcall.jwt.secret must be set and at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccess(UUID userId, String email, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("type", TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getAccessTtl())))
                .signWith(key)
                .compact();
    }

    public String issueRefresh(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(userId.toString())
                .claim("type", TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getRefreshTtl())))
                .signWith(key)
                .compact();
    }

    /** Verifies signature, issuer and expiry; returns the claims. Throws {@code JwtException} if invalid. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
