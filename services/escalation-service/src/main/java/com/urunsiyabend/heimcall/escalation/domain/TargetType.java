package com.urunsiyabend.heimcall.escalation.domain;

/** What a rule target points at. Resolved to concrete user ids when the level fires. */
public enum TargetType {
    USER,
    SCHEDULE,
    TEAM
}
