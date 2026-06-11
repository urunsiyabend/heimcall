package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.web.IncidentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of open {@link SseEmitter}s, keyed by organization id so an event reaches only
 * subscribers of its tenant. Single-instance only: emitters live in this JVM's heap, so a multi-replica
 * deployment needs a shared fan-out (Redis pub/sub) — deferred.
 *
 * <p>Dead emitters are pruned on the first failed send and on completion/timeout. A periodic heartbeat
 * keeps idle connections alive through proxies and surfaces broken pipes promptly.
 */
@Component
public class IncidentStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(IncidentStreamRegistry.class);

    /** No server-side timeout; rely on the client's EventSource auto-reconnect and the heartbeat. */
    private static final long NO_TIMEOUT = 0L;

    private final Map<UUID, Set<SseEmitter>> emittersByOrg = new ConcurrentHashMap<>();

    /** Opens a new stream for the org and wires removal on completion, timeout, and error. */
    public SseEmitter register(UUID organizationId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        Set<SseEmitter> set = emittersByOrg.computeIfAbsent(organizationId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        emitter.onCompletion(() -> remove(organizationId, emitter));
        emitter.onTimeout(() -> remove(organizationId, emitter));
        emitter.onError(e -> remove(organizationId, emitter));
        log.debug("SSE subscriber registered for org {} (now {})", organizationId, set.size());
        return emitter;
    }

    /** Fan a lifecycle change out to every open stream of the owning org. */
    public void publish(UUID organizationId, IncidentStreamEvent event) {
        Set<SseEmitter> set = emittersByOrg.get(organizationId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("incident").data(event));
            } catch (IOException | RuntimeException e) {
                // Client gone or pipe broken: drop it. completeWithError triggers the onError prune.
                emitter.completeWithError(e);
            }
        }
    }

    /** Comment-only ping every 20s so idle proxies don't sever the connection. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (Set<SseEmitter> set : emittersByOrg.values()) {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | RuntimeException e) {
                    emitter.completeWithError(e);
                }
            }
        }
    }

    private void remove(UUID organizationId, SseEmitter emitter) {
        Set<SseEmitter> set = emittersByOrg.get(organizationId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                emittersByOrg.remove(organizationId, set);
            }
        }
    }
}
