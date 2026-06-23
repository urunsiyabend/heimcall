package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.UnsupportedJwtException;

import java.security.Key;
import java.util.Set;

/**
 * Verifies RS256 JWTs against the issuer's public keys (resolved by {@code kid} from a {@link
 * PublicKeyResolver}). Hardened per RFC 8725: the algorithm is pinned to RS256 and anything else —
 * {@code none}, HS256, or a header-chosen alg — is rejected before signature checking, closing the
 * algorithm-confusion attack where an attacker submits the public key as an HMAC secret.
 *
 * <p>Beyond the signature this validates {@code iss}, {@code exp}/{@code nbf} (via jjwt) and, on the typed
 * helpers, {@code aud} and {@code token_use}.
 */
public class JwtVerifier {

    private final JwtProperties props;
    private final io.jsonwebtoken.JwtParser parser;

    public JwtVerifier(JwtProperties props, PublicKeyResolver resolver) {
        this.props = props;
        this.parser = Jwts.parser()
                .keyLocator(new Rs256KeyLocator(resolver))
                .requireIssuer(props.getIssuer())
                .build();
    }

    /** Signature + {@code iss} + time validated. Throws {@link JwtException} if invalid. */
    public Claims parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    /** A valid user access token: adds {@code aud} + {@code token_use=user_access} checks. */
    public Claims verifyAccess(String token) {
        Claims claims = parse(token);
        requireAudience(claims, props.getAudience());
        requireTokenUse(claims, JwtClaims.USER_ACCESS);
        return claims;
    }

    /** A valid user refresh token: adds {@code aud} + {@code token_use=user_refresh} checks. */
    public Claims verifyRefresh(String token) {
        Claims claims = parse(token);
        requireAudience(claims, props.getAudience());
        requireTokenUse(claims, JwtClaims.USER_REFRESH);
        return claims;
    }

    private static void requireTokenUse(Claims claims, String expected) {
        if (!expected.equals(claims.get(JwtClaims.TOKEN_USE, String.class))) {
            throw new JwtException("unexpected token_use; required " + expected);
        }
    }

    private static void requireAudience(Claims claims, String expected) {
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(expected)) {
            throw new JwtException("token audience does not include " + expected);
        }
    }

    /**
     * Selects the public key by {@code kid} and enforces the algorithm allowlist. The accepted algorithm is
     * fixed in code to {@link #ALLOWED_ALG} (RFC 8725 §3.1): the {@code alg} header is only compared against
     * that constant and rejected if it differs — it never <em>selects</em> the verification algorithm — so a
     * token signed with any other algorithm (e.g. {@code none}, HS256, or even a validly RS384/RS512-signed
     * token) is refused before signature checking. The JWK {@code alg}/{@code use} members are advisory and
     * are never consulted here.
     */
    private static final class Rs256KeyLocator implements Locator<Key> {
        private static final String ALLOWED_ALG = "RS256";

        private final PublicKeyResolver resolver;

        Rs256KeyLocator(PublicKeyResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public Key locate(Header header) {
            if (!(header instanceof JwsHeader jws)) {
                throw new UnsupportedJwtException("unsigned JWT rejected");
            }
            if (!ALLOWED_ALG.equals(jws.getAlgorithm())) {
                throw new UnsupportedJwtException("algorithm not allowed: " + jws.getAlgorithm());
            }
            String kid = jws.getKeyId();
            if (kid == null) {
                throw new JwtException("missing kid");
            }
            Key key = resolver.resolve(kid);
            if (key == null) {
                throw new JwtException("unknown kid: " + kid);
            }
            return key;
        }
    }
}
