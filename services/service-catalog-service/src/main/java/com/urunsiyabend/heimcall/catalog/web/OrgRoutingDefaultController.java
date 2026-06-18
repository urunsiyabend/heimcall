package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.EscalationClient;
import com.urunsiyabend.heimcall.catalog.IdentityClient;
import com.urunsiyabend.heimcall.catalog.domain.OrgRoutingDefault;
import com.urunsiyabend.heimcall.catalog.domain.OrgRoutingDefaultRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Org-level catch-all escalation policy (Phase 10 T2). When no specific service carries an inbound
 * routingKey (or the matched service has no policy), routing falls back to this default, making
 * routing resolution total -- an incident never silently fails to page for a routing reason.
 */
@RestController
@RequestMapping("/v1/organizations/{orgId}/routing-default")
public class OrgRoutingDefaultController {

    private final OrgRoutingDefaultRepository defaults;
    private final IdentityClient identity;
    private final EscalationClient escalation;

    public OrgRoutingDefaultController(OrgRoutingDefaultRepository defaults, IdentityClient identity,
                                       EscalationClient escalation) {
        this.defaults = defaults;
        this.identity = identity;
        this.escalation = escalation;
    }

    public record SetRequest(@NotNull UUID escalationPolicyId) {
    }

    public record RoutingDefaultResponse(UUID organizationId, UUID defaultEscalationPolicyId) {
        static RoutingDefaultResponse of(OrgRoutingDefault d) {
            return new RoutingDefaultResponse(d.getOrganizationId(), d.getDefaultEscalationPolicyId());
        }
    }

    /** The policy is validated against escalation-service: unknown/foreign policy -> 409. */
    @PutMapping
    public RoutingDefaultResponse set(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                      @Valid @RequestBody SetRequest req) {
        identity.requireMember(orgId, callerId);
        escalation.requirePolicyInOrg(orgId, req.escalationPolicyId());
        OrgRoutingDefault d = defaults.findById(orgId)
                .map(existing -> {
                    existing.setPolicy(req.escalationPolicyId(), Instant.now());
                    return existing;
                })
                .orElseGet(() -> OrgRoutingDefault.create(orgId, req.escalationPolicyId(), Instant.now()));
        return RoutingDefaultResponse.of(defaults.save(d));
    }

    /** 404 if no default is configured for the org. */
    @GetMapping
    public RoutingDefaultResponse get(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return defaults.findById(orgId)
                .map(RoutingDefaultResponse::of)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("no routing default configured for org: " + orgId));
    }

    /** Clear the org default. Idempotent: 204 whether or not one was set. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        defaults.deleteById(orgId);
    }
}
