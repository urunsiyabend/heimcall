package com.urunsiyabend.heimcall.identity.web;

/** Small set of API-level exceptions mapped to HTTP statuses in {@link GlobalExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404 - referenced aggregate does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409 - uniqueness or state conflict (duplicate slug/email/name, etc.). */
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

    /** 401 - unknown / inactive integration key. */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
