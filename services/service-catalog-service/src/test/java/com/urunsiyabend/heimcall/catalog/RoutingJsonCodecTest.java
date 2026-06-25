package com.urunsiyabend.heimcall.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.routing.ConditionNode;
import com.urunsiyabend.heimcall.routing.FieldRef;
import com.urunsiyabend.heimcall.routing.Operator;
import com.urunsiyabend.heimcall.routing.TimeRestriction;
import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingJsonCodecTest {

    private final RoutingJsonCodec codec = new RoutingJsonCodec(new ObjectMapper());

    @Test
    void conditionRoundTrips() {
        ConditionNode tree = new ConditionNode.Group(ConditionNode.BoolOp.ALL, List.of(
                new ConditionNode.Leaf(FieldRef.system("routingKey"), Operator.EQUALS, List.of("payments")),
                new ConditionNode.Leaf(FieldRef.metadata("env"), Operator.IN, List.of("prod", "staging")),
                new ConditionNode.Group(ConditionNode.BoolOp.NOT, List.of(
                        new ConditionNode.Leaf(FieldRef.system("severity"), Operator.EQUALS, List.of("INFO"))))));

        ConditionNode back = codec.readCondition(codec.toJson(tree));

        assertThat(back).isEqualTo(tree);
    }

    @Test
    void timeRestrictionRoundTrips() {
        TimeRestriction tr = new TimeRestriction(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 0), LocalTime.of(17, 30));

        TimeRestriction back = codec.readTimeRestriction(codec.toJson(tr));

        assertThat(back.days()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        assertThat(back.start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(back.end()).isEqualTo(LocalTime.of(17, 30));
    }

    @Test
    void nullTimeRestrictionSerializesToNull() {
        assertThat(codec.toJson((TimeRestriction) null)).isNull();
        assertThat(codec.readTimeRestriction(null)).isNull();
    }

    @Test
    void malformedConditionIsBadRequest() {
        assertThatThrownBy(() -> codec.parseCondition(new ObjectMapper().createObjectNode().put("node", "BOGUS")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void unknownOperatorIsBadRequest() throws Exception {
        var node = new ObjectMapper().readTree(
                "{\"node\":\"LEAF\",\"field\":{\"kind\":\"SYSTEM\",\"name\":\"source\"},"
                        + "\"operator\":\"WAT\",\"values\":[\"x\"]}");
        assertThatThrownBy(() -> codec.parseCondition(node))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }
}
