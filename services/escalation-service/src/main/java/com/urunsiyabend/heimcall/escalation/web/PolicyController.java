package com.urunsiyabend.heimcall.escalation.web;

import com.urunsiyabend.heimcall.escalation.IdentityClient;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicy;
import com.urunsiyabend.heimcall.escalation.domain.EscalationPolicyRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRule;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleRepository;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTarget;
import com.urunsiyabend.heimcall.escalation.domain.EscalationRuleTargetRepository;
import com.urunsiyabend.heimcall.escalation.domain.TargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for escalation policies, their levels (rules), and rule targets. All endpoints are
 * org-scoped and require the caller to be a member of the org (header-context stub, JWT deferred).
 */
@RestController
@RequestMapping("/v1/organizations/{orgId}/escalation-policies")
public class PolicyController {

    private final EscalationPolicyRepository policies;
    private final EscalationRuleRepository rules;
    private final EscalationRuleTargetRepository targets;
    private final IdentityClient identity;

    public PolicyController(EscalationPolicyRepository policies, EscalationRuleRepository rules,
                            EscalationRuleTargetRepository targets, IdentityClient identity) {
        this.policies = policies;
        this.rules = rules;
        this.targets = targets;
        this.identity = identity;
    }

    public record CreatePolicyRequest(@NotBlank String name, @Min(0) Integer repeatCount) {
    }

    public record UpdatePolicyRequest(@NotBlank String name, @Min(0) Integer repeatCount) {
    }

    public record CreateRuleRequest(@NotNull @Min(1) Integer level, @NotNull @Min(0) Integer delaySeconds) {
    }

    public record CreateTargetRequest(@NotNull TargetType targetType, @NotNull UUID targetId) {
    }

    public record PolicyResponse(UUID id, UUID organizationId, String name, int repeatCount,
                                 Instant createdAt, Instant updatedAt) {
        static PolicyResponse of(EscalationPolicy p) {
            return new PolicyResponse(p.getId(), p.getOrganizationId(), p.getName(), p.getRepeatCount(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record TargetResponse(UUID id, TargetType targetType, UUID targetId) {
        static TargetResponse of(EscalationRuleTarget t) {
            return new TargetResponse(t.getId(), t.getTargetType(), t.getTargetId());
        }
    }

    public record RuleResponse(UUID id, UUID policyId, int level, int delaySeconds, List<TargetResponse> targets) {
    }

    // --- Policy ---

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse create(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                 @Valid @RequestBody CreatePolicyRequest req) {
        identity.requireMember(orgId, callerId);
        int repeat = req.repeatCount() != null ? req.repeatCount() : 0;
        EscalationPolicy saved = policies.save(EscalationPolicy.create(orgId, req.name(), repeat, Instant.now()));
        return PolicyResponse.of(saved);
    }

    @GetMapping
    public List<PolicyResponse> list(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return policies.findByOrganizationId(orgId).stream().map(PolicyResponse::of).toList();
    }

    @GetMapping("/{id}")
    public PolicyResponse get(@PathVariable UUID orgId, @PathVariable UUID id,
                              @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return PolicyResponse.of(loadPolicy(orgId, id));
    }

    @PutMapping("/{id}")
    public PolicyResponse update(@PathVariable UUID orgId, @PathVariable UUID id,
                                 @RequestHeader("X-User-Id") UUID callerId,
                                 @Valid @RequestBody UpdatePolicyRequest req) {
        identity.requireMember(orgId, callerId);
        EscalationPolicy p = loadPolicy(orgId, id);
        p.update(req.name(), req.repeatCount() != null ? req.repeatCount() : 0, Instant.now());
        return PolicyResponse.of(policies.save(p));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID id,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        policies.delete(loadPolicy(orgId, id));
    }

    // --- Rules ---

    @PostMapping("/{id}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse addRule(@PathVariable UUID orgId, @PathVariable UUID id,
                                @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody CreateRuleRequest req) {
        identity.requireMember(orgId, callerId);
        loadPolicy(orgId, id);
        if (rules.existsByPolicyIdAndLevel(id, req.level())) {
            throw new ApiExceptions.ConflictException("level already defined: " + req.level());
        }
        EscalationRule saved = rules.save(EscalationRule.create(id, req.level(), req.delaySeconds(), Instant.now()));
        return ruleResponse(saved);
    }

    @GetMapping("/{id}/rules")
    public List<RuleResponse> listRules(@PathVariable UUID orgId, @PathVariable UUID id,
                                        @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        loadPolicy(orgId, id);
        return rules.findByPolicyIdOrderByLevelAsc(id).stream().map(this::ruleResponse).toList();
    }

    @DeleteMapping("/{id}/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID ruleId,
                           @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        loadPolicy(orgId, id);
        rules.delete(loadRule(id, ruleId));
    }

    // --- Targets ---

    @PostMapping("/{id}/rules/{ruleId}/targets")
    @ResponseStatus(HttpStatus.CREATED)
    public TargetResponse addTarget(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID ruleId,
                                    @RequestHeader("X-User-Id") UUID callerId,
                                    @Valid @RequestBody CreateTargetRequest req) {
        identity.requireMember(orgId, callerId);
        loadPolicy(orgId, id);
        loadRule(id, ruleId);
        // Validate the target belongs to the org. SCHEDULE existence is validated at fire time.
        switch (req.targetType()) {
            case USER -> identity.requireOrgUser(orgId, req.targetId());
            case TEAM -> identity.requireTeamInOrg(orgId, req.targetId());
            case SCHEDULE -> { /* resolved + validated when the level fires */ }
        }
        EscalationRuleTarget saved = targets.save(
                EscalationRuleTarget.create(ruleId, req.targetType(), req.targetId()));
        return TargetResponse.of(saved);
    }

    @DeleteMapping("/{id}/rules/{ruleId}/targets/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTarget(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID ruleId,
                             @PathVariable UUID targetId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        loadPolicy(orgId, id);
        loadRule(id, ruleId);
        EscalationRuleTarget t = targets.findById(targetId)
                .filter(x -> x.getRuleId().equals(ruleId))
                .orElseThrow(() -> new ApiExceptions.NotFoundException("target not found on rule"));
        targets.delete(t);
    }

    private RuleResponse ruleResponse(EscalationRule r) {
        List<TargetResponse> ts = targets.findByRuleId(r.getId()).stream().map(TargetResponse::of).toList();
        return new RuleResponse(r.getId(), r.getPolicyId(), r.getLevel(), r.getDelaySeconds(), ts);
    }

    private EscalationPolicy loadPolicy(UUID orgId, UUID id) {
        return policies.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("policy not found in organization: " + id));
    }

    private EscalationRule loadRule(UUID policyId, UUID ruleId) {
        return rules.findByIdAndPolicyId(ruleId, policyId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("rule not found on policy: " + ruleId));
    }
}
