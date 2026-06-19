package com.urunsiyabend.heimcall.notification;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 14 T2: per-(incident, user, channel) notification cooldown backed by Redis.
 *
 * <p>Escalation can request a notification at multiple levels/rounds for one incident+user; without a
 * cooldown each becomes a separate page. {@link #tryReserve} atomically reserves the window
 * ({@code SET key <ts> EX <window> NX}) — the first caller wins (proceed), a repeat inside the window is
 * suppressed.
 *
 * <p><b>Never the source of truth</b> (engineering rule §3.2): any Redis error fails <i>open</i>
 * (returns {@code true} → the page proceeds), so a Redis outage cannot drop real notifications.
 */
@Service
public class CooldownService {

    private static final Logger log = LoggerFactory.getLogger(CooldownService.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration window;

    public CooldownService(StringRedisTemplate redis,
                           @Value("${notification.cooldown.enabled:true}") boolean enabled,
                           @Value("${notification.cooldown.window-seconds:60}") long windowSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * @return {@code true} if the window was reserved (the caller should send), {@code false} if a page for
     *         this (incident, user, channel) is already cooling down (suppress). Fails open on Redis errors.
     */
    public boolean tryReserve(UUID incidentId, UUID userId, NotificationChannel channel) {
        if (!enabled) {
            return true;
        }
        String key = "notification-cooldown:" + incidentId + ":" + userId + ":" + channel;
        try {
            Boolean reserved = redis.opsForValue().setIfAbsent(key, Instant.now().toString(), window);
            return reserved == null || reserved;
        } catch (RuntimeException e) {
            // Fail-open: a cooldown is a suppression optimization, not a gate on real pages.
            log.warn("Cooldown check failed for {} (Redis error); proceeding (fail-open): {}", key, e.toString());
            return true;
        }
    }
}
