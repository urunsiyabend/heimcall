package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.notification.domain.DeliveryStatus;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls for PENDING deliveries whose {@code nextAttemptAt} has passed and fires each one. Each fires
 * in its own transaction ({@link DeliveryService#fireDelivery}); an unexpected error firing one is
 * logged and the loop continues so one bad delivery never stalls the rest.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final NotificationDeliveryRepository deliveries;
    private final DeliveryService deliveryService;

    public DeliveryWorker(NotificationDeliveryRepository deliveries, DeliveryService deliveryService) {
        this.deliveries = deliveries;
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval-ms:5000}")
    public void fireDueDeliveries() {
        List<NotificationDelivery> due = deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                DeliveryStatus.PENDING, Instant.now());
        for (NotificationDelivery delivery : due) {
            try {
                deliveryService.fireDelivery(delivery.getId());
            } catch (RuntimeException e) {
                log.warn("Delivery {} could not be fired, will retry: {}", delivery.getId(), e.getMessage());
            }
        }
    }
}
