package com.urunsiyabend.heimcall.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 T2: the ruleset crosses the wire as a snapshot (catalog -&gt; incident). The pure model must
 * round-trip through Jackson byte-identically so incident reconstructs the exact same {@link Ruleset}
 * catalog evaluated — otherwise local routing could drift from catalog's preview. This guards the
 * polymorphic condition tree, the time restriction, the zone, and the fallback.
 */
class RulesetSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rulesetRoundTrips() throws Exception {
        ConditionNode condition = new ConditionNode.Group(ConditionNode.BoolOp.ALL, List.of(
                new ConditionNode.Leaf(FieldRef.system("routingKey"), Operator.EQUALS, List.of("payments")),
                new ConditionNode.Group(ConditionNode.BoolOp.ANY, List.of(
                        new ConditionNode.Leaf(FieldRef.system("severity"), Operator.EQUALS, List.of("CRITICAL")),
                        new ConditionNode.Leaf(FieldRef.metadata("env"), Operator.IN, List.of("prod", "stg")))),
                new ConditionNode.Group(ConditionNode.BoolOp.NOT, List.of(
                        new ConditionNode.Leaf(FieldRef.metadata("silenced"), Operator.EXISTS, List.of())))));
        Rule rule = new Rule(UUID.randomUUID(), "prod payments", true, condition,
                RoutingAction.route(UUID.randomUUID(), UUID.randomUUID()),
                new TimeRestriction(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                        LocalTime.of(9, 0), LocalTime.of(17, 0)));
        Ruleset ruleset = new Ruleset(12, ZoneId.of("America/New_York"), List.of(rule),
                RoutingAction.route(null, UUID.randomUUID()));

        Ruleset back = mapper.readValue(mapper.writeValueAsString(ruleset), Ruleset.class);

        assertThat(back).isEqualTo(ruleset);
    }

    @Test
    void unroutedFallbackRoundTrips() throws Exception {
        Ruleset ruleset = new Ruleset(1, ZoneId.of("UTC"), List.of(), RoutingAction.unrouted());

        Ruleset back = mapper.readValue(mapper.writeValueAsString(ruleset), Ruleset.class);

        assertThat(back).isEqualTo(ruleset);
        assertThat(back.fallbackAction().type()).isEqualTo(RoutingAction.Type.UNROUTED);
    }
}
