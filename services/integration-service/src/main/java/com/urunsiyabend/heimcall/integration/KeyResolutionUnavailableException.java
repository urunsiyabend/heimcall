package com.urunsiyabend.heimcall.integration;

/** Raised when identity-service cannot be reached to validate the key. Maps to HTTP 503. */
public class KeyResolutionUnavailableException extends RuntimeException {

    public KeyResolutionUnavailableException(String message) {
        super(message);
    }
}
