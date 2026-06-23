package com.urunsiyabend.heimcall.common.security;

/**
 * Claim names and {@code token_use} values shared by issuer and verifiers (RFC 9068 style). A single
 * issuer mints both user and service tokens with the same {@code iss}; verifiers tell them apart by
 * {@code token_use}, target them by {@code aud}, and authorize by {@code scope}.
 */
public final class JwtClaims {

    private JwtClaims() {
    }

    /** Claim distinguishing the token class. */
    public static final String TOKEN_USE = "token_use";

    /** User access token: authenticates a human caller against resource endpoints. */
    public static final String USER_ACCESS = "user_access";

    /** User refresh token: exchanged for a new access token; never valid on a resource endpoint. */
    public static final String USER_REFRESH = "user_refresh";

    /** Service token: a machine caller (Phase 16 T2). */
    public static final String SERVICE = "service";

    public static final String EMAIL = "email";
    public static final String NAME = "name";
    public static final String SCOPE = "scope";
}
