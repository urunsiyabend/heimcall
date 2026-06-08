package com.urunsiyabend.heimcall.catalog.web;

/** API-level exceptions mapped to HTTP statuses in {@link GlobalExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404 - referenced aggregate does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409 - uniqueness or state conflict. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 403 - caller is not a member of the target organization. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** 503 - identity-service could not be reached to enforce a tenant rule. */
    public static class DependencyUnavailableException extends RuntimeException {
        public DependencyUnavailableException(String message) {
            super(message);
        }
    }
}
