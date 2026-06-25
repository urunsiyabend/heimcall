package com.urunsiyabend.heimcall.catalog;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.urunsiyabend.heimcall.catalog.routing.ConditionNode;
import com.urunsiyabend.heimcall.catalog.routing.FieldRef;
import com.urunsiyabend.heimcall.catalog.routing.Operator;
import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;

/**
 * Save-time semantic validation of a routing rule's condition tree and timezone (Phase 17). Catches
 * what the structural codec cannot: unknown SYSTEM field names, operator/value arity mismatches,
 * non-numeric values for numeric operators, regex that does not compile (or is over the size limit),
 * severity/messageType values that are not valid domain enums, and bad IANA timezones. Cross-service
 * reference checks (service in org, policy exists) are the service layer's job. RE2J patterns are
 * compiled here, once, at save time — never per event on the hot path.
 */
@Component
public class RoutingRuleValidator {

    /** Known SYSTEM field names, mirroring {@code RoutingContext.resolve}. */
    static final Set<String> SYSTEM_FIELDS = Set.of(
            "routingKey", "source", "messageType", "severity", "externalEntityId", "title", "description");

    private static final int MAX_REGEX_LENGTH = 512;

    public void validateCondition(ConditionNode node) {
        if (node instanceof ConditionNode.Group g) {
            g.children().forEach(this::validateCondition);
            return;
        }
        validateLeaf((ConditionNode.Leaf) node);
    }

    private void validateLeaf(ConditionNode.Leaf leaf) {
        FieldRef field = leaf.field();
        if (field.kind() == FieldRef.Kind.SYSTEM && !SYSTEM_FIELDS.contains(field.name())) {
            throw bad("unknown system field: " + field.name() + " (allowed: " + SYSTEM_FIELDS + ")");
        }
        Operator op = leaf.operator();
        int n = leaf.values().size();

        if (op.isValueless()) {
            if (n != 0) {
                throw bad(op + " takes no value");
            }
            return;
        }
        if (op.isListValued()) {
            if (n == 0) {
                throw bad(op + " requires at least one value");
            }
        } else if (n != 1) {
            throw bad(op + " requires exactly one value");
        }

        if (op.isNumeric()) {
            for (String v : leaf.values()) {
                try {
                    Double.parseDouble(v.trim());
                } catch (NumberFormatException e) {
                    throw bad(op + " value must be numeric: " + v);
                }
            }
        }
        if (op == Operator.MATCHES_REGEX || op == Operator.NOT_MATCHES_REGEX) {
            validateRegex(leaf.values().get(0));
        }
        validateEnumValues(field, op, leaf.values());
    }

    private void validateRegex(String regex) {
        if (regex.length() > MAX_REGEX_LENGTH) {
            throw bad("regex exceeds " + MAX_REGEX_LENGTH + " chars");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw bad("invalid regex: " + e.getMessage());
        }
    }

    /** severity/messageType compared by (in)equality or membership must use valid domain enum names. */
    private void validateEnumValues(FieldRef field, Operator op, java.util.List<String> values) {
        if (field.kind() != FieldRef.Kind.SYSTEM) {
            return;
        }
        boolean exactMatch = op == Operator.EQUALS || op == Operator.NOT_EQUALS
                || op == Operator.IN || op == Operator.NOT_IN;
        if (!exactMatch) {
            return;
        }
        if (field.name().equals("severity")) {
            values.forEach(v -> requireEnum(Severity.class, v, "severity"));
        } else if (field.name().equals("messageType")) {
            values.forEach(v -> requireEnum(MessageType.class, v, "messageType"));
        }
    }

    public void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw bad("invalid IANA timezone: " + timezone);
        }
    }

    private static <E extends Enum<E>> void requireEnum(Class<E> type, String value, String label) {
        try {
            Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw bad(label + " must be one of " + java.util.Arrays.toString(type.getEnumConstants())
                    + ", was: " + value);
        }
    }

    private static ApiExceptions.BadRequestException bad(String message) {
        return new ApiExceptions.BadRequestException(message);
    }
}
