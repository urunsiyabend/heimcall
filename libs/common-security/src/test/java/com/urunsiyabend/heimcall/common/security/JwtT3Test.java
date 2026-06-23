package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 T3 — the verifier-side enforcement seam in {@link JwtAuthenticationFilter}. The filter is what
 * turns a service token addressed to <i>this</i> service into authentication carrying {@code SCOPE_*}
 * authorities (so {@code @PreAuthorize} on an internal endpoint can pin the exact scope), and is what refuses
 * a token minted for another audience. Proven here without Spring/HTTP: a {@link MockFilterChain} captures
 * whatever the filter put in the {@link SecurityContextHolder}.
 */
class JwtT3Test {

    private static final String ISSUER = "https://identity.heimcall.internal";
    private static final String KID = "active-1";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JwtProperties props(KeyPair active, String serviceName) {
        JwtProperties p = new JwtProperties();
        p.setIssuer(ISSUER);
        p.setAudience("heimcall-api");
        p.setPrivateKey("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(active.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        p.setKeyId(KID);
        p.setServiceName(serviceName);
        return p;
    }

    private static String serviceToken(JwtKeys keys, String aud, String scope) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .subject("incident")
                .audience().add(aud).and()
                .claim(JwtClaims.TOKEN_USE, JwtClaims.SERVICE)
                .claim(JwtClaims.SCOPE, scope)
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private static Authentication runFilter(JwtProperties props, JwtKeys keys, String bearer) throws Exception {
        JwtVerifier verifier = new JwtVerifier(props, new LocalKeyResolver(keys));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(verifier, props.getServiceName());
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        request.addHeader("X-User-Id", "spoofed-user-id"); // must never survive
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        // The X-User-Id seen downstream is taken from the (wrapped) request the filter forwarded.
        lastForwardedUserIdHeader = ((HttpServletRequest) chain.getRequest()).getHeader("X-User-Id");
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static String lastForwardedUserIdHeader;

    @Test
    void serviceTokenForThisServiceYieldsScopeAuthoritiesAndNoUser() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = props(active, "identity");
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());

        Authentication auth = runFilter(props, keys,
                serviceToken(keys, "identity", "identity.membership.read identity.team.read"));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("incident"); // caller service (sub), not a user
        assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("SCOPE_identity.membership.read", "SCOPE_identity.team.read");
        // A service token is never a user: the spoofed X-User-Id is stripped, none injected.
        assertThat(lastForwardedUserIdHeader).isNull();
    }

    @Test
    void serviceTokenForAnotherServiceIsNotAuthenticated() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = props(active, "identity"); // we are identity
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());

        // Token minted for catalog must not authenticate here -> stays unauthenticated (chain resolves 401).
        Authentication auth = runFilter(props, keys, serviceToken(keys, "catalog", "catalog.routing.resolve"));

        assertThat(auth).isNull();
    }

    @Test
    void callerOnlyServiceNeverAcceptsServiceTokens() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = props(active, ""); // blank service-name: this service exposes no internal API
        JwtKeys keys = new JwtKeys(props, new DefaultResourceLoader());

        Authentication auth = runFilter(props, keys, serviceToken(keys, "identity", "identity.membership.read"));

        assertThat(auth).isNull();
    }
}
