package com.urunsiyabend.heimcall.notification.domain;

/** Lifecycle of one delivery attempt-track. */
public enum DeliveryStatus {
    /** Not yet delivered; eligible for the worker once next_attempt_at passes. */
    PENDING,
    /** Successfully delivered through the channel. Terminal. */
    DELIVERED,
    /** Gave up after exhausting bounded retries. Terminal. */
    FAILED
}
