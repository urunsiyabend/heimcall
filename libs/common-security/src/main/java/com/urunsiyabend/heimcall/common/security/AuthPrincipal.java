package com.urunsiyabend.heimcall.common.security;

import java.util.UUID;

/** Authenticated caller, extracted from a verified access token and held as the security principal. */
public record AuthPrincipal(UUID userId, String email, String name) {
}
