package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Jwts;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Signs JWTs with RS256 using the single issuer's private key (identity-service only). Access tokens carry
 * the caller identity ({@code sub}=userId + email/name); refresh tokens carry only the subject. The
 * {@code token_use} claim distinguishes them and the {@code aud} claim targets the resource API. Every token
 * carries the active {@code kid} in its header so verifiers can select the right public key from the JWKS.
 */
public class JwtIssuer {

    private final JwtProperties props;
    private final JwtKeys keys;

    public JwtIssuer(JwtProperties props, JwtKeys keys) {
        this.props = props;
        this.keys = keys;
    }

    public String issueAccess(UUID userId, String email, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(keys.activeKid()).and()
                .issuer(props.getIssuer())
                .audience().add(props.getAudience()).and()
                .subject(userId.toString())
                .claim(JwtClaims.EMAIL, email)
                .claim(JwtClaims.NAME, name)
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getAccessTtl())))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public String issueRefresh(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(keys.activeKid()).and()
                .issuer(props.getIssuer())
                .audience().add(props.getAudience()).and()
                .subject(userId.toString())
                .claim(JwtClaims.TOKEN_USE, JwtClaims.USER_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getRefreshTtl())))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();
    }
}
