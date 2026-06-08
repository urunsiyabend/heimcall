package com.urunsiyabend.heimcall.integration;

/** Raised when a normalized alert could not be confirmed onto Kafka. Maps to HTTP 503. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
