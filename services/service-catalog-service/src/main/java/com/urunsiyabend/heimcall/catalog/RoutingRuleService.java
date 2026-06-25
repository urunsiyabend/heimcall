package com.urunsiyabend.heimcall.catalog;

import com.urunsiyabend.heimcall.catalog.domain.MonitoredServiceRepository;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRule;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRuleRepository;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRuleset;
import com.urunsiyabend.heimcall.catalog.domain.RoutingRulesetRepository;
import com.urunsiyabend.heimcall.routing.ConditionNode;
import com.urunsiyabend.heimcall.routing.Rule;
import com.urunsiyabend.heimcall.routing.RoutingAction;
import com.urunsiyabend.heimcall.routing.RoutingContext;
import com.urunsiyabend.heimcall.routing.RoutingDecision;
import com.urunsiyabend.heimcall.routing.Ruleset;
import com.urunsiyabend.heimcall.routing.TimeRestriction;
import com.urunsiyabend.heimcall.routing.TreeRoutingEvaluator;
import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authoring + evaluation control plane for routing rules (Phase 17 T1). Owns rule CRUD (each write
 * bumps the org's ruleset version), assembles the evaluable {@link Ruleset} from storage, and runs the
 * {@link TreeRoutingEvaluator} for the internal resolve and dry-run preview. Authoring is always
 * catalog's job; incident-service only consumes the resolve result.
 */
@Service
public class RoutingRuleService {

    private final RoutingRuleRepository rules;
    private final RoutingRulesetRepository rulesets;
    private final MonitoredServiceRepository services;
    private final EscalationClient escalation;
    private final RoutingJsonCodec codec;
    private final RoutingRuleValidator validator;
    private final RoutingSnapshotPublisher snapshots;
    private final TreeRoutingEvaluator evaluator = new TreeRoutingEvaluator();

    public RoutingRuleService(RoutingRuleRepository rules, RoutingRulesetRepository rulesets,
                              MonitoredServiceRepository services, EscalationClient escalation,
                              RoutingJsonCodec codec, RoutingRuleValidator validator,
                              RoutingSnapshotPublisher snapshots) {
        this.rules = rules;
        this.rulesets = rulesets;
        this.services = services;
        this.escalation = escalation;
        this.codec = codec;
        this.validator = validator;
        this.snapshots = snapshots;
    }

    /** A validated rule definition coming from the API (already parsed off the wire). */
    public record RuleSpec(String name, boolean enabled, ConditionNode condition,
                           RoutingAction action, TimeRestriction timeRestriction) {
    }

    // --- queries ---

    public List<RoutingRule> list(UUID orgId) {
        return rules.findByOrganizationIdOrderByPositionAsc(orgId);
    }

    public RoutingRule get(UUID orgId, UUID ruleId) {
        return rules.findByIdAndOrganizationId(ruleId, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("routing rule not found: " + ruleId));
    }

    /** Rules whose evaluation is unreachable because an earlier enabled, unconditional, always-on rule
     *  short-circuits the table (a non-fatal authoring warning). */
    public Set<UUID> shadowedRuleIds(UUID orgId) {
        Set<UUID> shadowed = new LinkedHashSet<>();
        boolean blocked = false;
        for (RoutingRule r : list(orgId)) {
            if (blocked) {
                shadowed.add(r.getId());
            } else if (r.isEnabled() && r.getTimeRestrictionJson() == null
                    && isAlwaysTrue(codec.readCondition(r.getConditionJson()))) {
                blocked = true;
            }
        }
        return shadowed;
    }

    // --- mutations (each bumps the ruleset version) ---

    @Transactional
    public RoutingRule create(UUID orgId, RuleSpec spec) {
        validateSpec(orgId, spec);
        int position = (int) rules.countByOrganizationId(orgId);
        RoutingRule entity = RoutingRule.create(orgId, position, spec.name(), spec.enabled(),
                codec.toJson(spec.condition()), spec.action().type().name(),
                spec.action().serviceId(), spec.action().escalationPolicyId(),
                codec.toJson(spec.timeRestriction()), Instant.now());
        RoutingRule saved = rules.save(entity);
        bumpVersion(orgId);
        return saved;
    }

    @Transactional
    public RoutingRule update(UUID orgId, UUID ruleId, RuleSpec spec) {
        validateSpec(orgId, spec);
        RoutingRule entity = get(orgId, ruleId);
        entity.update(spec.name(), spec.enabled(), codec.toJson(spec.condition()),
                spec.action().type().name(), spec.action().serviceId(),
                spec.action().escalationPolicyId(), codec.toJson(spec.timeRestriction()));
        bumpVersion(orgId);
        return entity;
    }

    @Transactional
    public void delete(UUID orgId, UUID ruleId) {
        RoutingRule entity = get(orgId, ruleId);
        rules.delete(entity);
        // Compact positions so they stay a dense 0..n-1 sequence (the position UNIQUE constraint holds).
        List<RoutingRule> remaining = list(orgId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }
        bumpVersion(orgId);
    }

    @Transactional
    public List<RoutingRule> reorder(UUID orgId, List<UUID> orderedIds) {
        List<RoutingRule> current = list(orgId);
        if (orderedIds.size() != current.size()
                || !current.stream().map(RoutingRule::getId).allMatch(orderedIds::contains)) {
            throw new ApiExceptions.BadRequestException(
                    "order must be a permutation of all " + current.size() + " rule ids");
        }
        // Two-phase to dodge the (org, position) UNIQUE constraint mid-update: park at high offsets first.
        for (RoutingRule r : current) {
            r.setPosition(r.getPosition() + current.size() + 1);
        }
        rules.flush();
        for (int i = 0; i < orderedIds.size(); i++) {
            get(orgId, orderedIds.get(i)).setPosition(i);
        }
        bumpVersion(orgId);
        return list(orgId);
    }

    // --- fallback + timezone (ruleset header) ---

    public RoutingRuleset header(UUID orgId) {
        return rulesets.findById(orgId).orElse(null);
    }

    @Transactional
    public RoutingRuleset setFallback(UUID orgId, UUID serviceId, UUID policyId, String timezone) {
        if (policyId != null) {
            if (serviceId != null) {
                requireServiceInOrg(orgId, serviceId);
            }
            escalation.requirePolicyInOrg(orgId, policyId);
        }
        if (timezone != null) {
            validator.validateTimezone(timezone);
        }
        RoutingRuleset rs = ensureRuleset(orgId);
        if (timezone != null) {
            rs.setTimezone(timezone, Instant.now());
        }
        rs.setFallback(serviceId, policyId, Instant.now());
        publishSnapshot(orgId);
        return rs;
    }

    @Transactional
    public void clearFallback(UUID orgId) {
        RoutingRuleset rs = header(orgId);
        if (rs != null) {
            rs.setFallback(null, null, Instant.now());
            publishSnapshot(orgId);
        }
    }

    // --- evaluation ---

    public RoutingDecision resolve(UUID orgId, RoutingContext context) {
        return evaluator.evaluate(context, assemble(orgId), false);
    }

    public RoutingDecision preview(UUID orgId, RoutingContext context) {
        return evaluator.evaluate(context, assemble(orgId), true);
    }

    /** Full snapshot for the pull-based hydration/reconciliation path (Phase 17 T2). Mirrors what the
     *  outbox publishes on a write, so a pull and a stream deliver the same versioned payload. */
    public com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent snapshot(UUID orgId) {
        RoutingRuleset header = header(orgId);
        Ruleset ruleset = assemble(orgId);
        Instant publishedAt = header == null ? Instant.now() : header.getPublishedAt();
        return new com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent(
                UUID.randomUUID(), orgId, ruleset.version(), ruleset, publishedAt);
    }

    /** Build the evaluable ruleset from storage. Absent header => empty, UTC, UNROUTED fallback. */
    public Ruleset assemble(UUID orgId) {
        RoutingRuleset header = header(orgId);
        long version = header == null ? 0 : header.getVersion();
        ZoneId zone = header == null ? ZoneId.of("UTC") : ZoneId.of(header.getTimezone());
        RoutingAction fallback = (header == null || header.getFallbackPolicyId() == null)
                ? RoutingAction.unrouted()
                : RoutingAction.route(header.getFallbackServiceId(), header.getFallbackPolicyId());

        List<Rule> model = new ArrayList<>();
        for (RoutingRule r : list(orgId)) {
            RoutingAction action = "UNROUTED".equals(r.getActionType())
                    ? RoutingAction.unrouted()
                    : RoutingAction.route(r.getActionServiceId(), r.getActionPolicyId());
            model.add(new Rule(r.getId(), r.getName(), r.isEnabled(),
                    codec.readCondition(r.getConditionJson()), action,
                    codec.readTimeRestriction(r.getTimeRestrictionJson())));
        }
        return new Ruleset(version, zone, model, fallback);
    }

    // --- internals ---

    private void validateSpec(UUID orgId, RuleSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new ApiExceptions.BadRequestException("rule name required");
        }
        validator.validateCondition(spec.condition());
        RoutingAction action = spec.action();
        if (action.type() == RoutingAction.Type.ROUTE) {
            if (action.serviceId() != null) {
                requireServiceInOrg(orgId, action.serviceId());
            }
            escalation.requirePolicyInOrg(orgId, action.escalationPolicyId());
        }
    }

    private void requireServiceInOrg(UUID orgId, UUID serviceId) {
        services.findByIdAndOrganizationId(serviceId, orgId)
                .orElseThrow(() -> new ApiExceptions.ConflictException(
                        "service does not belong to this organization: " + serviceId));
    }

    private RoutingRuleset ensureRuleset(UUID orgId) {
        return rulesets.findById(orgId).orElseGet(() -> rulesets.save(RoutingRuleset.create(orgId, Instant.now())));
    }

    private void bumpVersion(UUID orgId) {
        ensureRuleset(orgId).bump(Instant.now());
        publishSnapshot(orgId);
    }

    /** Append the new full ruleset snapshot to the outbox in this same transaction (Phase 17 T2) so the
     *  publish never ghosts on rollback and never drops on commit. Reads the just-mutated state (JPA
     *  flushes before the assemble queries), so the snapshot carries the bumped version. */
    private void publishSnapshot(UUID orgId) {
        snapshots.publish(orgId, assemble(orgId));
    }

    private static boolean isAlwaysTrue(ConditionNode node) {
        return node instanceof ConditionNode.Group g
                && g.op() == ConditionNode.BoolOp.ALL
                && g.children().isEmpty();
    }
}
