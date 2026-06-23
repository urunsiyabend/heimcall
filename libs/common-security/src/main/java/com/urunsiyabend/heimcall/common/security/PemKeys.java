package com.urunsiyabend.heimcall.common.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Parses RSA keys from PEM text. Tolerates literal {@code \n} escapes so keys can live in env vars. */
final class PemKeys {

    private PemKeys() {
    }

    /** Parse a PKCS#8 RSA private key from PEM. */
    static RSAPrivateCrtKey privateKey(String pem) {
        byte[] der = der(pem, "PRIVATE KEY");
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("invalid RSA private key PEM", e);
        }
    }

    /** Parse an X.509 (SubjectPublicKeyInfo) RSA public key from PEM. */
    static RSAPublicKey publicKey(String pem) {
        byte[] der = der(pem, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("invalid RSA public key PEM", e);
        }
    }

    /** Derive the public key from an RSA CRT private key (so the signer needs only one PEM). */
    static RSAPublicKey publicFrom(RSAPrivateCrtKey priv) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot derive public key from private key", e);
        }
    }

    private static byte[] der(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("empty " + type + " PEM");
        }
        String normalized = pem.replace("\\n", "\n");
        String base64 = normalized
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
