package com.urunsiyabend.heimcall.common.security;

import java.security.PublicKey;

/** Resolves keys from the signer's in-process {@link JwtKeys} — the issuer never calls its own JWKS. */
public class LocalKeyResolver implements PublicKeyResolver {

    private final JwtKeys keys;

    public LocalKeyResolver(JwtKeys keys) {
        this.keys = keys;
    }

    @Override
    public PublicKey resolve(String kid) {
        return keys.publicKey(kid);
    }
}
