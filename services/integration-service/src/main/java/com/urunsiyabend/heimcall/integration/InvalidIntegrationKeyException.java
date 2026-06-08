package com.urunsiyabend.heimcall.integration;

/** Raised when identity-service rejects the inbound integration key. Maps to HTTP 401. */
public class InvalidIntegrationKeyException extends RuntimeException {

    public InvalidIntegrationKeyException(String message) {
        super(message);
    }
}
