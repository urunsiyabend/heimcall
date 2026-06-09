package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.domain.MonitoredService;
import com.urunsiyabend.heimcall.catalog.domain.MonitoredServiceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service routing resolution. incident-service calls this at trigger time to turn an
 * alert routingKey into the owning service + its escalation policy. Not for external callers.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final MonitoredServiceRepository services;

    public InternalController(MonitoredServiceRepository services) {
        this.services = services;
    }

    public record RoutingResolution(UUID serviceId, UUID escalationPolicyId, UUID ownerTeamId) {
    }

    /** Resolve a routingKey to its service. 404 if no service in the org carries that routingKey. */
    @GetMapping("/organizations/{orgId}/routing")
    public RoutingResolution resolve(@PathVariable UUID orgId, @RequestParam String routingKey) {
        MonitoredService s = services.findByOrganizationIdAndRoutingKey(orgId, routingKey)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("no service for routing key: " + routingKey));
        return new RoutingResolution(s.getId(), s.getEscalationPolicyId(), s.getOwnerTeamId());
    }
}
