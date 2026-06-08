package com.urunsiyabend.heimcall.identity.domain;

/** Org-level membership role. RBAC enforcement beyond membership existence is deferred. */
public enum Role {
    OWNER, ADMIN, MEMBER
}
