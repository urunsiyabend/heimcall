package com.urunsiyabend.heimcall.common.security;

import java.security.PublicKey;

/** Resolves the verification public key for a token's {@code kid}. Returns {@code null} if unknown. */
public interface PublicKeyResolver {

    PublicKey resolve(String kid);
}
