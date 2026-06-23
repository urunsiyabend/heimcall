package com.urunsiyabend.heimcall.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT configuration shared by every service. Phase 16 T1: tokens are signed with <b>RS256</b> by a single
 * issuer ({@code identity-service}, the only holder of the private key) and verified everywhere via JWKS.
 * There is no longer a shared symmetric secret — a service that can verify a token can no longer forge one.
 *
 * <p>Signer side (identity only): {@code private-key} / {@code private-key-location} + {@code key-id}, plus an
 * optional retired key ({@code retired-public-key} / {@code retired-key-id}) kept in the JWKS during a
 * rotation overlap. Verifier side (every service): {@code jwks-uri} (or, on the signer, the in-process keys).
 */
@ConfigurationProperties("heimcall.jwt")
public class JwtProperties {

    /** Token issuer claim; set on issue, required on verify. Same value for user and service tokens. */
    private String issuer = "heimcall";

    /** Audience for user tokens; verified on the resource side. */
    private String audience = "heimcall-api";

    /** Access-token lifetime. */
    private Duration accessTtl = Duration.ofHours(1);

    /** Refresh-token lifetime. */
    private Duration refreshTtl = Duration.ofDays(30);

    // --- Signer side (identity-service only) ---

    /** Active RSA private key, PEM (PKCS#8). Literal {@code \n} escapes are accepted. Empty → use location. */
    private String privateKey = "";

    /** Spring resource location of the active private key PEM, used when {@link #privateKey} is empty. */
    private String privateKeyLocation = "";

    /** Key id ({@code kid}) stamped on the JWS header of every issued token and published in the JWKS. */
    private String keyId = "heimcall-dev-1";

    /** Previous public key, PEM, kept in the JWKS so tokens it signed verify until they expire. Optional. */
    private String retiredPublicKey = "";

    /** Key id of the retired public key. Required iff {@link #retiredPublicKey} is set. */
    private String retiredKeyId = "";

    // --- Verifier side (every service) ---

    /** JWKS endpoint of the issuer; verifiers fetch public keys here. Unused on the signer (uses local keys). */
    private String jwksUri = "";

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
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

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPrivateKeyLocation() {
        return privateKeyLocation;
    }

    public void setPrivateKeyLocation(String privateKeyLocation) {
        this.privateKeyLocation = privateKeyLocation;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getRetiredPublicKey() {
        return retiredPublicKey;
    }

    public void setRetiredPublicKey(String retiredPublicKey) {
        this.retiredPublicKey = retiredPublicKey;
    }

    public String getRetiredKeyId() {
        return retiredKeyId;
    }

    public void setRetiredKeyId(String retiredKeyId) {
        this.retiredKeyId = retiredKeyId;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    /** True when this service is configured as the signer (holds a private key). */
    public boolean isSigner() {
        return (privateKey != null && !privateKey.isBlank())
                || (privateKeyLocation != null && !privateKeyLocation.isBlank());
    }
}
