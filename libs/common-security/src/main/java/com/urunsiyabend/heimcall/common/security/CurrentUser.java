package com.urunsiyabend.heimcall.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Convenience access to the authenticated caller from the security context. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated principal, or {@code null} if the request is unauthenticated. */
    public static AuthPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        return null;
    }

    /** The authenticated caller's user id, or {@code null} if unauthenticated. */
    public static UUID id() {
        AuthPrincipal principal = get();
        return principal != null ? principal.userId() : null;
    }
}
