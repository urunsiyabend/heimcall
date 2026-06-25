package com.urunsiyabend.heimcall.catalog;

import com.urunsiyabend.heimcall.catalog.routing.ConditionNode;
import com.urunsiyabend.heimcall.catalog.routing.FieldRef;
import com.urunsiyabend.heimcall.catalog.routing.Operator;
import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingRuleValidatorTest {

    private final RoutingRuleValidator validator = new RoutingRuleValidator();

    private ConditionNode leaf(FieldRef f, Operator op, String... values) {
        return new ConditionNode.Leaf(f, op, List.of(values));
    }

    @Test
    void acceptsWellFormedCondition() {
        assertThatCode(() -> validator.validateCondition(
                leaf(FieldRef.system("severity"), Operator.EQUALS, "CRITICAL"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownSystemField() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.system("bogus"), Operator.EQUALS, "x")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsScalarOperatorWithMultipleValues() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.system("source"), Operator.EQUALS, "a", "b")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsValuelessOperatorWithValue() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.metadata("env"), Operator.EXISTS, "x")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsNonNumericValueForNumericOperator() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.metadata("load"), Operator.GT, "high")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsInvalidRegex() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.metadata("host"), Operator.MATCHES_REGEX, "db-[0-9")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsInvalidSeverityEnumValue() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.system("severity"), Operator.EQUALS, "SEVERE")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsInvalidMessageTypeInList() {
        assertThatThrownBy(() -> validator.validateCondition(
                leaf(FieldRef.system("messageType"), Operator.IN, "CRITICAL", "MELTDOWN")))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void rejectsBadTimezone() {
        assertThatThrownBy(() -> validator.validateTimezone("Mars/Phobos"))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    void acceptsValidIanaTimezone() {
        assertThatCode(() -> validator.validateTimezone("America/New_York")).doesNotThrowAnyException();
    }
}
