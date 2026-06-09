package com.urunsiyabend.heimcall.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by notification-service when a notification is successfully delivered through a channel.
 * One per delivery (a request may fan out to several contact methods).
 *
 * @param eventId         unique id for this event
 * @param deliveredAt     when delivery succeeded
 * @param organizationId  tenant boundary
 * @param incidentId      incident the notification was about
 * @param recipientUserId user that was notified
 * @param channel         channel used (EMAIL, WEBHOOK)
 * @param destination     concrete address the message went to (email / url)
 * @param requestEventId  the {@code notification.requested.v1} eventId this delivery fulfills
 */
public record NotificationDeliveredEvent(
        UUID eventId,
        Instant deliveredAt,
        UUID organizationId,
        UUID incidentId,
        UUID recipientUserId,
        String channel,
        String destination,
        UUID requestEventId
) {
}
