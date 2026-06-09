package com.urunsiyabend.heimcall.incident.web;

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

    /** 409 - the requested lifecycle transition is not legal from the current state. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 403 - caller is not a member of the incident's organization. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** 503 - a dependency (identity) could not be reached to enforce a tenant rule. */
    public static class DependencyUnavailableException extends RuntimeException {
        public DependencyUnavailableException(String message) {
            super(message);
        }
    }
}
