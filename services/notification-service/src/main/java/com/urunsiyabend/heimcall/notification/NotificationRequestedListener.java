package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.common.events.NotificationRequestedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code notification.requested.v1} and hands it to the notification service. */
@Component
public class NotificationRequestedListener {

    private final NotificationService notificationService;

    public NotificationRequestedListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED,
            groupId = "notification-service.notification-requested-consumer")
    public void onRequested(NotificationRequestedEvent event) {
        notificationService.onNotificationRequested(event);
    }
}
