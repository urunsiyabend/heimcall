package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.domain.MonitoredService;
import com.urunsiyabend.heimcall.catalog.domain.MonitoredServiceRepository;
import com.urunsiyabend.heimcall.catalog.domain.OrgRoutingDefaultRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Service-to-service routing resolution. incident-service calls this at trigger time to turn an
 * alert routingKey into the escalation policy that should page. Not for external callers.
 *
 * <p>Phase 10 T2: routing is <b>total</b> (Alertmanager-style catch-all). Resolution order:
 * <ol>
 *   <li>a specific service carries the routingKey <b>and</b> has an escalation policy -> that policy;</li>
 *   <li>else (no service, or a matched service with no policy) -> the org-default catch-all policy if
 *       configured;</li>
 *   <li>else 404 -- a definitive no-match, which incident-service turns into a deliberate, observable
 *       UNROUTED outcome (T3) rather than a silent drop.</li>
 * </ol>
 * The resolved {@code escalationPolicyId} is therefore never null on a 200.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final MonitoredServiceRepository services;
    private final OrgRoutingDefaultRepository defaults;

    public InternalController(MonitoredServiceRepository services, OrgRoutingDefaultRepository defaults) {
        this.services = services;
        this.defaults = defaults;
    }

    public record RoutingResolution(UUID serviceId, UUID escalationPolicyId, UUID ownerTeamId) {
    }

    /** Resolve a routingKey to its escalation policy. 404 only on a definitive no-match (no specific
     *  service with a policy AND no org default). */
    @GetMapping("/organizations/{orgId}/routing")
    @PreAuthorize("hasAuthority('SCOPE_catalog.routing.resolve')")
    public RoutingResolution resolve(@PathVariable UUID orgId, @RequestParam String routingKey) {
        Optional<MonitoredService> match = services.findByOrganizationIdAndRoutingKey(orgId, routingKey);

        // 1. Specific service with a policy -> that policy.
        if (match.isPresent() && match.get().getEscalationPolicyId() != null) {
            MonitoredService s = match.get();
            return new RoutingResolution(s.getId(), s.getEscalationPolicyId(), s.getOwnerTeamId());
        }

        // 2. Fall back to the org default catch-all if configured. Keep the matched service's context
        //    (id/owner team) when a service matched but simply had no policy assigned.
        UUID defaultPolicy = defaults.findById(orgId)
                .map(d -> d.getDefaultEscalationPolicyId())
                .orElse(null);
        if (defaultPolicy != null) {
            UUID serviceId = match.map(MonitoredService::getId).orElse(null);
            UUID ownerTeamId = match.map(MonitoredService::getOwnerTeamId).orElse(null);
            return new RoutingResolution(serviceId, defaultPolicy, ownerTeamId);
        }

        // 3. Definitive no-match: no specific policy and no org default.
        throw new ApiExceptions.NotFoundException("no routing for key: " + routingKey + " (no service policy, no org default)");
    }
}
