package com.urunsiyabend.heimcall.notification.sender;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;

/**
 * Sends one notification over a concrete channel. Implementations throw on any failure (provider
 * error, timeout, bad destination); the caller turns a thrown exception into a retry / FAILED.
 */
public interface NotificationSender {

    NotificationChannel channel();

    void send(NotificationDelivery delivery, NotificationRequest request) throws Exception;
}
