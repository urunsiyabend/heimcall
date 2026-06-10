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
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final JwtSupport jwt;

    public JwtAuthenticationFilter(JwtSupport jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String verifiedUserId = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwt.parse(header.substring(7));
                if (JwtSupport.TYPE_ACCESS.equals(claims.get("type", String.class))) {
                    UUID userId = UUID.fromString(claims.getSubject());
                    AuthPrincipal principal = new AuthPrincipal(
                            userId, claims.get("email", String.class), claims.get("name", String.class));
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    verifiedUserId = userId.toString();
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid/expired token: stay unauthenticated; protected routes resolve to 401.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(new UserIdHeaderRequest(request, verifiedUserId), response);
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
