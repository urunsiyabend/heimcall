package com.urunsiyabend.heimcall.incident;

/**
 * Routing resolution against service-catalog failed for a reason that is NOT a definitive no-match
 * (catalog unreachable, 5xx, timeout, or any non-404 error). This is an infra failure, not a routing
 * decision — it must never be collapsed into "no policy / nobody paged". Thrown out of the
 * {@code alert.received.v1} listener so the existing retry + DLT machinery re-attempts the event when
 * catalog recovers; the {@code @Transactional} handler rolls back, leaving no orphan incident.
 *
 * <p>Contrast with a real 404, which {@link CatalogClient#resolve} returns as {@code Optional.empty()} —
 * a genuine no-match that the catch-all / UNROUTED path handles deliberately (Phase 10).
 */
public class RoutingUnavailableException extends RuntimeException {

    public RoutingUnavailableException(String routingKey, Throwable cause) {
        super("routing resolution unavailable for routingKey=" + routingKey, cause);
    }
}
