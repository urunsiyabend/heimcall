package com.urunsiyabend.heimcall.catalog.routing;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreeRoutingEvaluatorTest {

    private final TreeRoutingEvaluator evaluator = new TreeRoutingEvaluator();

    private static final UUID PAY_SVC = UUID.randomUUID();
    private static final UUID PAY_CRIT = UUID.randomUUID();
    private static final UUID PAY_WARN = UUID.randomUUID();
    private static final UUID FALLBACK_POLICY = UUID.randomUUID();

    private RoutingContext ctx(String severity, Map<String, String> metadata) {
        return new RoutingContext("payments", "grafana", severity, severity, "ext-1",
                "Disk full", "node down", metadata, Instant.parse("2026-06-25T12:00:00Z"));
    }

    private Rule rule(String name, ConditionNode cond, RoutingAction action) {
        return new Rule(UUID.randomUUID(), name, true, cond, action, null);
    }

    private ConditionNode leaf(FieldRef f, Operator op, String... values) {
        return new ConditionNode.Leaf(f, op, List.of(values));
    }

    private ConditionNode all(ConditionNode... children) {
        return new ConditionNode.Group(ConditionNode.BoolOp.ALL, List.of(children));
    }

    // --- first-match-wins: same routingKey, different severity -> different policy ---

    @Test
    void sameRoutingKeyDifferentSeverityRoutesToDifferentPolicies() {
        Rule crit = rule("crit payments",
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments"),
                        leaf(FieldRef.system("severity"), Operator.EQUALS, "CRITICAL")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Rule warn = rule("warn payments",
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments"),
                        leaf(FieldRef.system("severity"), Operator.EQUALS, "WARNING")),
                RoutingAction.route(PAY_SVC, PAY_WARN));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(crit, warn), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
        assertThat(evaluator.evaluate(ctx("WARNING", Map.of()), rs, false).escalationPolicyId())
                .isEqualTo(PAY_WARN);
    }

    @Test
    void reorderingChangesOutcome() {
        Rule specific = rule("specific",
                all(leaf(FieldRef.system("severity"), Operator.EQUALS, "CRITICAL")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Rule catchAll = rule("any payments",
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments")),
                RoutingAction.route(PAY_SVC, PAY_WARN));

        Ruleset specificFirst = new Ruleset(1, ZoneId.of("UTC"), List.of(specific, catchAll), RoutingAction.unrouted());
        Ruleset catchAllFirst = new Ruleset(1, ZoneId.of("UTC"), List.of(catchAll, specific), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), specificFirst, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), catchAllFirst, false).escalationPolicyId())
                .isEqualTo(PAY_WARN);
    }

    // --- fallback vs UNROUTED ---

    @Test
    void noMatchWithFallbackUsesFallback() {
        Rule warn = rule("warn", all(leaf(FieldRef.system("severity"), Operator.EQUALS, "WARNING")),
                RoutingAction.route(PAY_SVC, PAY_WARN));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(warn),
                RoutingAction.route(null, FALLBACK_POLICY));

        RoutingDecision d = evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false);
        assertThat(d.escalationPolicyId()).isEqualTo(FALLBACK_POLICY);
        assertThat(d.matchedRuleId()).isNull();
        assertThat(d.unrouted()).isFalse();
    }

    @Test
    void noMatchNoFallbackIsUnrouted() {
        Rule warn = rule("warn", all(leaf(FieldRef.system("severity"), Operator.EQUALS, "WARNING")),
                RoutingAction.route(PAY_SVC, PAY_WARN));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(warn), RoutingAction.unrouted());

        RoutingDecision d = evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false);
        assertThat(d.unrouted()).isTrue();
        assertThat(d.escalationPolicyId()).isNull();
    }

    // --- missing / null / negative semantics ---

    @Test
    void positiveOperatorOnMissingMetadataDoesNotMatch() {
        Rule r = rule("prod", all(leaf(FieldRef.metadata("env"), Operator.EQUALS, "prod")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).unrouted()).isTrue();
    }

    @Test
    void negativeOperatorDoesNotMatchOnMissingField() {
        // NOT_EQUALS on an absent metadata key must NOT match (the designed-out PagerDuty gotcha).
        Rule r = rule("not-staging", all(leaf(FieldRef.metadata("env"), Operator.NOT_EQUALS, "staging")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).unrouted()).isTrue();
    }

    @Test
    void negativeOperatorMatchesWhenPresentAndConditionFails() {
        Rule r = rule("not-staging", all(leaf(FieldRef.metadata("env"), Operator.NOT_EQUALS, "staging")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of("env", "prod")), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
    }

    @Test
    void existsAndNotExists() {
        Ruleset existsRs = new Ruleset(1, ZoneId.of("UTC"),
                List.of(rule("has-env", all(leaf(FieldRef.metadata("env"), Operator.EXISTS)),
                        RoutingAction.route(PAY_SVC, PAY_CRIT))), RoutingAction.unrouted());
        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of("env", "prod")), existsRs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), existsRs, false).unrouted()).isTrue();
    }

    @Test
    void numericTypeMismatchDoesNotMatchAndIsTraced() {
        Rule r = rule("high-load", all(leaf(FieldRef.metadata("load"), Operator.GT, "90")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        RoutingDecision d = evaluator.evaluate(ctx("CRITICAL", Map.of("load", "not-a-number")), rs, true);
        assertThat(d.unrouted()).isTrue();
        assertThat(d.trace().get(0).detail()).contains("type mismatch");
    }

    @Test
    void numericComparisonMatches() {
        Rule r = rule("high-load", all(leaf(FieldRef.metadata("load"), Operator.GT, "90")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of("load", "95")), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
    }

    @Test
    void regexMatchesViaRe2j() {
        Rule r = rule("host-regex",
                all(leaf(FieldRef.metadata("host"), Operator.MATCHES_REGEX, "db-[0-9]+\\.prod")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of("host", "db-12.prod")), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of("host", "web-1.prod")), rs, false).unrouted())
                .isTrue();
    }

    @Test
    void traceExplainsWhyEarlierRuleDidNotMatch() {
        Rule prod = rule("prod-only", all(leaf(FieldRef.metadata("env"), Operator.EQUALS, "prod")),
                RoutingAction.route(PAY_SVC, PAY_CRIT));
        Rule any = rule("any", all(), RoutingAction.route(PAY_SVC, PAY_WARN));
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(prod, any), RoutingAction.unrouted());

        RoutingDecision d = evaluator.evaluate(ctx("CRITICAL", Map.of("env", "staging")), rs, true);
        assertThat(d.escalationPolicyId()).isEqualTo(PAY_WARN);
        assertThat(d.trace()).hasSize(2);
        assertThat(d.trace().get(0).matched()).isFalse();
        assertThat(d.trace().get(0).detail()).contains("staging").contains("prod");
        assertThat(d.trace().get(1).matched()).isTrue();
    }

    // --- time restriction (org timezone, DST, midnight-spanning) ---

    @Test
    void timeRestrictionMatchesInsideWindowInOrgTimezone() {
        // 12:00 UTC == 08:00 America/New_York (EDT, summer). Window 07:00-09:00 local matches.
        TimeRestriction window = new TimeRestriction(Set.of(), LocalTime.of(7, 0), LocalTime.of(9, 0));
        Rule r = new Rule(UUID.randomUUID(), "business-hours", true,
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments")),
                RoutingAction.route(PAY_SVC, PAY_CRIT), window);
        Ruleset rs = new Ruleset(1, ZoneId.of("America/New_York"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
    }

    @Test
    void timeRestrictionMissesOutsideWindow() {
        // 12:00 UTC == 08:00 EDT; window 09:00-17:00 local does NOT match.
        TimeRestriction window = new TimeRestriction(Set.of(), LocalTime.of(9, 0), LocalTime.of(17, 0));
        Rule r = new Rule(UUID.randomUUID(), "business-hours", true,
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments")),
                RoutingAction.route(PAY_SVC, PAY_CRIT), window);
        Ruleset rs = new Ruleset(1, ZoneId.of("America/New_York"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).unrouted()).isTrue();
    }

    @Test
    void midnightSpanningWindowMatches() {
        // 12:00 UTC == 07:00 America/Los_Angeles (PDT). Window 22:00-08:00 spans midnight, includes 07:00.
        TimeRestriction overnight = new TimeRestriction(Set.of(), LocalTime.of(22, 0), LocalTime.of(8, 0));
        Rule r = new Rule(UUID.randomUUID(), "overnight", true,
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments")),
                RoutingAction.route(PAY_SVC, PAY_CRIT), overnight);
        Ruleset rs = new Ruleset(1, ZoneId.of("America/Los_Angeles"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).escalationPolicyId())
                .isEqualTo(PAY_CRIT);
    }

    @Test
    void dayOfWeekRestriction() {
        // 2026-06-25 is a Thursday.
        TimeRestriction weekend = new TimeRestriction(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                LocalTime.MIN, LocalTime.MIN);
        Rule r = new Rule(UUID.randomUUID(), "weekend", true,
                all(leaf(FieldRef.system("routingKey"), Operator.EQUALS, "payments")),
                RoutingAction.route(PAY_SVC, PAY_CRIT), weekend);
        Ruleset rs = new Ruleset(1, ZoneId.of("UTC"), List.of(r), RoutingAction.unrouted());

        assertThat(evaluator.evaluate(ctx("CRITICAL", Map.of()), rs, false).unrouted()).isTrue();
    }
}
