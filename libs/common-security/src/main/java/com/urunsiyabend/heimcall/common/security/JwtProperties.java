package com.urunsiyabend.heimcall.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT configuration shared by every service. The {@code secret} must be the same across services
 * (HS256 symmetric key); it must be at least 256 bits (32 bytes) of entropy.
 */
@ConfigurationProperties("heimcall.jwt")
public class JwtProperties {

    /** HS256 signing secret. Same value on every service. Min 32 bytes. */
    private String secret;

    /** Access-token lifetime. */
    private Duration accessTtl = Duration.ofHours(1);

    /** Refresh-token lifetime. */
    private Duration refreshTtl = Duration.ofDays(30);

    /** Token issuer claim, validated on parse. */
    private String issuer = "heimcall";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
