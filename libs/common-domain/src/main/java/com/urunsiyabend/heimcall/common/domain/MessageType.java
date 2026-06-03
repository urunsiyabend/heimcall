package com.urunsiyabend.heimcall.common.domain;

/**
 * Type of an inbound message from an external monitoring system.
 * Drives whether an incident is created, updated, acknowledged, or resolved.
 */
public enum MessageType {
    CRITICAL,
    WARNING,
    INFO,
    ACKNOWLEDGEMENT,
    RECOVERY
}
