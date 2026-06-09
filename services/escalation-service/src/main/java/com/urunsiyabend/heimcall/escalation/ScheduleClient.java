package com.urunsiyabend.heimcall.escalation;

import com.urunsiyabend.heimcall.escalation.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the current on-call user of a schedule via schedule-service, used to turn a SCHEDULE
 * rule target into a concrete recipient when a level fires.
 */
@Component
public class ScheduleClient {

    private static final Logger log = LoggerFactory.getLogger(ScheduleClient.class);

    private final RestClient restClient;

    public ScheduleClient(@Value("${schedule.base-url:http://localhost:8085}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    private record OnCall(UUID userId, String source, UUID rotationId) {
    }

    /**
     * Current on-call user of the schedule, or empty if no one is on call (empty rotation / no override).
     * A 404 (unknown schedule) resolves to empty; a 5xx / unreachable schedule-service throws 503 so
     * the firing task is retried on the next poll rather than silently dropping the target.
     */
    public Optional<UUID> onCallUser(UUID organizationId, UUID scheduleId) {
        try {
            OnCall onCall = restClient.get()
                    .uri("/v1/internal/organizations/{org}/schedules/{id}/on-call", organizationId, scheduleId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ApiExceptions.DependencyUnavailableException("schedule-service error");
                    })
                    .body(OnCall.class);
            return Optional.ofNullable(onCall).map(OnCall::userId);
        } catch (ApiExceptions.DependencyUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ApiExceptions.DependencyUnavailableException("schedule-service unreachable");
        } catch (RuntimeException e) {
            // 4xx (e.g. 404 unknown schedule) -> no on-call user.
            log.warn("On-call resolution returned no user for schedule={} org={}: {}",
                    scheduleId, organizationId, e.getMessage());
            return Optional.empty();
        }
    }
}
