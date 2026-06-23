package com.urunsiyabend.heimcall.escalation.web;

import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicy;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service lookup. service-catalog calls this to validate that an escalation policy it is
 * about to attach to a service really exists in the org. Not for external callers.
 */
@RestController
@RequestMapping("/v1/internal")
public class InternalController {

    private final EscalationPolicyRepository policies;

    public InternalController(EscalationPolicyRepository policies) {
        this.policies = policies;
    }

    /** 204 if the policy exists in the org, 404 otherwise. */
    @GetMapping("/organizations/{orgId}/escalation-policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_escalation.policy.read')")
    public void checkPolicy(@PathVariable UUID orgId, @PathVariable UUID policyId) {
        policies.findByIdAndOrganizationId(policyId, orgId)
                .map(EscalationPolicy::getId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("policy not found in organization"));
    }
}
