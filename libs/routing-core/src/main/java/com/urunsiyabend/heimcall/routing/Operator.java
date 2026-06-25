package com.urunsiyabend.heimcall.routing;

/**
 * Condition leaf operators (Phase 17). String-typed unless noted. The {@code GT/GTE/LT/LTE} family
 * coerces both operands to numbers (mismatch -&gt; non-match, recorded in the trace); {@code IN/NOT_IN}
 * take a list value; {@code EXISTS/NOT_EXISTS} ignore the value.
 *
 * <p>Missing / null / type-mismatch semantics are defined once in {@link TreeRoutingEvaluator} — a
 * positive operator on a missing or null field is {@code false}; a negative operator matches only when
 * the field is <b>present and non-null</b> and the inner comparison fails (absence needs
 * {@link #NOT_EXISTS}). This deliberately designs out PagerDuty's "does-not-equal also matches missing"
 * gotcha.
 */
public enum Operator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    CONTAINS_SUBSTRING,
    NOT_CONTAINS_SUBSTRING,
    STARTS_WITH,
    ENDS_WITH,
    EXISTS,
    NOT_EXISTS,
    MATCHES_REGEX,
    NOT_MATCHES_REGEX,
    GT,
    GTE,
    LT,
    LTE;

    /** Operators whose semantics are "field present and non-null, inner check fails -&gt; true". */
    public boolean isNegative() {
        return this == NOT_EQUALS || this == NOT_IN || this == NOT_CONTAINS_SUBSTRING
                || this == NOT_MATCHES_REGEX;
    }

    public boolean isNumeric() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }

    public boolean isListValued() {
        return this == IN || this == NOT_IN;
    }

    public boolean isValueless() {
        return this == EXISTS || this == NOT_EXISTS;
    }
}
