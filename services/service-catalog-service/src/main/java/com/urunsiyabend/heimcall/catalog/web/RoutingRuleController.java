package com.urunsiyabend.heimcall.catalog.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.urunsiyabend.heimcall.catalog.IdentityClient;
import com.urunsiyabend.heimcall.catalog.RoutingJsonCodec;
import com.urunsiyabend.heimcall.catalog.RoutingRuleService;
import com.urunsiyabend.heimcall.catalog.RoutingRuleService.RuleSpec;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRule;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRuleset;
import com.urunsiyabend.heimcall.catalog.routing.RoutingAction;
import com.urunsiyabend.heimcall.catalog.routing.RoutingContext;
import com.urunsiyabend.heimcall.catalog.routing.RoutingDecision;
import jakarta.validation.Valid;
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
import java.util.Set;
import java.util.UUID;

/**
 * Member-gated CRUD + preview for an organization's routing rules (Phase 17 T1). Authoring lives in
 * service-catalog; this is the human/UI control plane. Evaluation for production routing is the
 * service-token-gated internal endpoint in {@link InternalController}.
 */
@RestController
@RequestMapping("/v1/organizations/{orgId}/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleService rulesService;
    private final IdentityClient identity;
    private final RoutingJsonCodec codec;

    public RoutingRuleController(RoutingRuleService rulesService, IdentityClient identity,
                                 RoutingJsonCodec codec) {
        this.rulesService = rulesService;
        this.identity = identity;
        this.codec = codec;
    }

    // --- request/response shapes ---

    public record ActionRequest(@NotNull RoutingAction.Type type, UUID serviceId, UUID escalationPolicyId) {
    }

    public record RuleRequest(@NotBlank String name, boolean enabled, @NotNull JsonNode condition,
                              @Valid @NotNull ActionRequest action, JsonNode timeRestriction) {
    }

    public record ReorderRequest(@NotNull List<UUID> orderedRuleIds) {
    }

    public record FallbackRequest(UUID serviceId, UUID escalationPolicyId, String timezone) {
    }

    public record RuleResponse(UUID id, int position, String name, boolean enabled, JsonNode condition,
                               ActionResponse action, JsonNode timeRestriction, boolean shadowed,
                               Instant createdAt) {
    }

    public record ActionResponse(String type, UUID serviceId, UUID escalationPolicyId) {
    }

    public record RulesetResponse(List<RuleResponse> rules, FallbackResponse fallback, long version,
                                  String timezone) {
    }

    public record FallbackResponse(String type, UUID serviceId, UUID escalationPolicyId) {
    }

    // --- CRUD ---

    @GetMapping
    public RulesetResponse list(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        Set<UUID> shadowed = rulesService.shadowedRuleIds(orgId);
        List<RuleResponse> rules = rulesService.list(orgId).stream()
                .map(r -> toResponse(r, shadowed.contains(r.getId()))).toList();
        RoutingRuleset header = rulesService.header(orgId);
        return new RulesetResponse(rules, fallbackResponse(header), header == null ? 0 : header.getVersion(),
                header == null ? "UTC" : header.getTimezone());
    }

    @GetMapping("/{ruleId}")
    public RuleResponse get(@PathVariable UUID orgId, @PathVariable UUID ruleId,
                            @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return toResponse(rulesService.get(orgId, ruleId), rulesService.shadowedRuleIds(orgId).contains(ruleId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                               @Valid @RequestBody RuleRequest req) {
        identity.requireMember(orgId, callerId);
        return toResponse(rulesService.create(orgId, toSpec(req)), false);
    }

    @PutMapping("/{ruleId}")
    public RuleResponse update(@PathVariable UUID orgId, @PathVariable UUID ruleId,
                               @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody RuleRequest req) {
        identity.requireMember(orgId, callerId);
        return toResponse(rulesService.update(orgId, ruleId, toSpec(req)),
                rulesService.shadowedRuleIds(orgId).contains(ruleId));
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID ruleId,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        rulesService.delete(orgId, ruleId);
    }

    @PutMapping("/order")
    public RulesetResponse reorder(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                   @Valid @RequestBody ReorderRequest req) {
        identity.requireMember(orgId, callerId);
        rulesService.reorder(orgId, req.orderedRuleIds());
        return list(orgId, callerId);
    }

    // --- fallback ---

    @GetMapping("/fallback")
    public FallbackResponse getFallback(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return fallbackResponse(rulesService.header(orgId));
    }

    @PutMapping("/fallback")
    public FallbackResponse setFallback(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                        @RequestBody FallbackRequest req) {
        identity.requireMember(orgId, callerId);
        return fallbackResponse(
                rulesService.setFallback(orgId, req.serviceId(), req.escalationPolicyId(), req.timezone()));
    }

    @DeleteMapping("/fallback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFallback(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        rulesService.clearFallback(orgId);
    }

    // --- dry-run preview ---

    @PostMapping("/preview")
    public RoutingDecision preview(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                   @RequestBody PreviewRequest req) {
        identity.requireMember(orgId, callerId);
        return rulesService.preview(orgId, req.toContext());
    }

    public record PreviewRequest(String routingKey, String source, String messageType, String severity,
                                 String externalEntityId, String title, String description,
                                 java.util.Map<String, String> metadata, Instant evaluatedAt) {
        RoutingContext toContext() {
            return new RoutingContext(routingKey, source, messageType, severity, externalEntityId, title,
                    description, metadata == null ? java.util.Map.of() : metadata,
                    evaluatedAt == null ? Instant.now() : evaluatedAt);
        }
    }

    // --- mapping ---

    private RuleSpec toSpec(RuleRequest req) {
        RoutingAction action = req.action().type() == RoutingAction.Type.ROUTE
                ? RoutingAction.route(req.action().serviceId(), req.action().escalationPolicyId())
                : RoutingAction.unrouted();
        return new RuleSpec(req.name(), req.enabled(), codec.parseCondition(req.condition()), action,
                codec.parseTimeRestriction(req.timeRestriction()));
    }

    private RuleResponse toResponse(RoutingRule r, boolean shadowed) {
        return new RuleResponse(r.getId(), r.getPosition(), r.getName(), r.isEnabled(),
                codec.rawNode(r.getConditionJson()),
                new ActionResponse(r.getActionType(), r.getActionServiceId(), r.getActionPolicyId()),
                codec.rawNode(r.getTimeRestrictionJson()), shadowed, r.getCreatedAt());
    }

    private FallbackResponse fallbackResponse(RoutingRuleset header) {
        if (header == null || header.getFallbackPolicyId() == null) {
            return new FallbackResponse("UNROUTED", null, null);
        }
        return new FallbackResponse("ROUTE", header.getFallbackServiceId(), header.getFallbackPolicyId());
    }
}
