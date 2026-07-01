package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Phase 20 T1: concurrent delivery dispatcher. A single loop thread continuously claims due deliveries
 * (each flipped to SENDING + lease, lock released) and hands each to a virtual-thread executor for the
 * actual send — bounded by a semaphore of {@code notification.delivery.concurrency} permits. This is the
 * throughput fix: the previous worker iterated due rows in a serial {@code for} loop with the SMTP/webhook
 * send inside the row-lock transaction, capping delivery at ~87/s while every upstream stage sustains
 * ~670/s.
 *
 * <p>Claim-on-demand, not batch-then-queue: it only claims as many rows as there are free permits, so a
 * claimed row never waits in a queue past its lease (which would cause a needless reclaim → duplicate).
 * In-flight count never exceeds the permit count. On shutdown, in-flight rows are left SENDING; their
 * leases expire and the next start (or a replica) re-claims them — at-least-once is preserved.
 *
 * <p>Runs on a dedicated thread (not the shared {@code @Scheduled} TaskScheduler) so blocking on a free
 * permit cannot stall the outbox relay / prune jobs.
 */
@Component
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final DeliveryService deliveryService;
    private final int concurrency;
    private final long idleSleepMs;
    private final Semaphore permits;

    private volatile boolean running;
    private Thread loop;
    private ExecutorService sendExecutor;

    public DeliveryDispatcher(DeliveryService deliveryService,
                              @Value("${notification.delivery.concurrency:16}") int concurrency,
                              @Value("${notification.delivery.idle-sleep-ms:500}") long idleSleepMs) {
        this.deliveryService = deliveryService;
        this.concurrency = concurrency;
        this.idleSleepMs = idleSleepMs;
        this.permits = new Semaphore(concurrency);
    }

    @PostConstruct
    public void start() {
        sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        loop = new Thread(this::runLoop, "delivery-dispatcher");
        loop.setDaemon(true);
        loop.start();
        log.info("DeliveryDispatcher started: concurrency={}, idleSleepMs={}", concurrency, idleSleepMs);
    }

    private void runLoop() {
        while (running) {
            int got;
            try {
                permits.acquire();                                   // block until at least one permit
                got = 1;
                while (got < concurrency && permits.tryAcquire()) {  // top up without blocking
                    got++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }

            List<NotificationDelivery> claimed;
            try {
                claimed = deliveryService.claim(got);
            } catch (RuntimeException e) {
                log.warn("Delivery claim failed, backing off: {}", e.toString());
                permits.release(got);
                sleepQuietly();
                continue;
            }

            int unused = got - claimed.size();
            if (unused > 0) {
                permits.release(unused);
            }
            if (claimed.isEmpty()) {
                sleepQuietly();                                      // nothing due; permits already returned
                continue;
            }

            for (NotificationDelivery delivery : claimed) {
                try {
                    sendExecutor.submit(() -> {
                        try {
                            deliveryService.sendClaimed(delivery);
                        } catch (RuntimeException e) {
                            log.warn("Unexpected error sending delivery {}: {}", delivery.getId(), e.toString());
                        } finally {
                            permits.release();
                        }
                    });
                } catch (RejectedExecutionException shuttingDown) {
                    // Executor stopped: leave the row SENDING (lease recovers it), return the permit.
                    permits.release();
                }
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(idleSleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        if (sendExecutor != null) {
            sendExecutor.shutdown();
            try {
                if (!sendExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    sendExecutor.shutdownNow();   // leftover in-flight rows recover via lease expiry
                }
            } catch (InterruptedException ie) {
                sendExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("DeliveryDispatcher stopped");
    }
}
