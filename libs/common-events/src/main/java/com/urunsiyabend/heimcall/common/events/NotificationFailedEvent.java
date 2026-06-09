package com.urunsiyabend.heimcall.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by notification-service when a delivery exhausts its bounded retries and is given up on.
 *
 * @param eventId         unique id for this event
 * @param failedAt        when the delivery was marked failed (last attempt)
 * @param organizationId  tenant boundary
 * @param incidentId      incident the notification was about
 * @param recipientUserId user that could not be notified
 * @param channel         channel attempted (EMAIL, WEBHOOK)
 * @param destination     concrete address that failed (email / url)
 * @param attempts        number of attempts made before giving up
 * @param reason          last failure reason
 * @param requestEventId  the {@code notification.requested.v1} eventId this delivery belonged to
 */
public record NotificationFailedEvent(
        UUID eventId,
        Instant failedAt,
        UUID organizationId,
        UUID incidentId,
        UUID recipientUserId,
        String channel,
        String destination,
        int attempts,
        String reason,
        UUID requestEventId
) {
}
