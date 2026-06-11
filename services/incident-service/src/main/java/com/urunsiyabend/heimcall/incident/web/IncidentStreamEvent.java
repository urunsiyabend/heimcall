package com.urunsiyabend.heimcall.incident.web;

import com.urunsiyabend.heimcall.common.domain.IncidentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal SSE payload for a live incident lifecycle change. Carries just enough for the UI to react
 * (which incident, what state, when); the client refetches the incident/timeline for full detail.
 */
public record IncidentStreamEvent(UUID incidentId, IncidentStatus status, Instant at) {
}
