package com.urunsiyabend.heimcall.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification delivery: consumes {@code notification.requested.v1}, fans out to the recipient's
 * contact methods, and delivers over email / webhook with bounded retry. Publishes
 * {@code notification.delivered.v1} / {@code notification.failed.v1}.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
