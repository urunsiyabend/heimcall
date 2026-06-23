package com.urunsiyabend.heimcall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Validates the {@code Authorization: Bearer <access-token>} header. On a valid access token the
 * caller identity is set as the security principal and the verified userId is injected as the
 * {@code X-User-Id} request header — so existing controllers that read {@code X-User-Id} keep working,
 * but the value is now derived from a checked signature rather than trusted from the client.
 *
 * <p>A client-supplied {@code X-User-Id} is always overwritten (with the token subject, or removed
 * when there is no valid token) so it can never be spoofed.
 *
 * <p>The token is read from {@code Authorization: Bearer}. As a narrow fallback — only on the SSE stream
 * endpoint, where a browser {@code EventSource} cannot set request headers — it is also accepted from the
 * {@code access_token} query parameter. The fallback is path-scoped so URL-borne tokens are not honored
 * elsewhere.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final JwtVerifier verifier;

    public JwtAuthenticationFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String verifiedUserId = null;
        String token = bearerToken(request);
        if (token != null) {
            try {
                Claims claims = verifier.verifyAccess(token);
                UUID userId = UUID.fromString(claims.getSubject());
                AuthPrincipal principal = new AuthPrincipal(
                        userId, claims.get(JwtClaims.EMAIL, String.class), claims.get(JwtClaims.NAME, String.class));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
                verifiedUserId = userId.toString();
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid/expired/wrong-type token: stay unauthenticated; protected routes resolve to 401.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(new UserIdHeaderRequest(request, verifiedUserId), response);
    }

    /**
     * Bearer header if present, else — only for the SSE stream endpoint — the {@code access_token} query
     * param. The query-param fallback is path-scoped on purpose: a token in the URL leaks via access logs,
     * browser history, and {@code Referer}, so it is accepted only where {@code EventSource} forces it.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (request.getRequestURI().endsWith("/incidents/stream")) {
            return request.getParameter("access_token");
        }
        return null;
    }

    /** Forces {@code X-User-Id} to the verified value (or hides it entirely when {@code null}). */
    private static final class UserIdHeaderRequest extends HttpServletRequestWrapper {
        private final String userId;

        UserIdHeaderRequest(HttpServletRequest request, String userId) {
            super(request);
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return userId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return userId != null ? Collections.enumeration(List.of(userId)) : Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(USER_ID_HEADER::equalsIgnoreCase);
            if (userId != null) {
                names.add(USER_ID_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
