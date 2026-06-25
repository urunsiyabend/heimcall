package com.urunsiyabend.heimcall.routing;

import com.google.re2j.Pattern;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct interpreter of the condition tree (Phase 17), implementing first-match-wins over an ordered
 * {@link Ruleset} and the missing/null/type-mismatch semantics locked in the plan:
 *
 * <ul>
 *   <li>a <b>positive</b> operator on a missing or null field is {@code false};</li>
 *   <li>a <b>negative</b> operator ({@code NOT_EQUALS}/{@code NOT_IN}/{@code NOT_CONTAINS_SUBSTRING}/
 *       {@code NOT_MATCHES_REGEX}) is {@code true} only when the field is present and non-null and the
 *       inner check fails — absence requires {@code NOT_EXISTS};</li>
 *   <li>{@code EXISTS} is true iff the field is present (even with a null value); {@code NOT_EXISTS}
 *       iff absent;</li>
 *   <li>a numeric operator on a non-numeric / null / missing value is {@code false} (a type mismatch,
 *       noted in the trace).</li>
 * </ul>
 *
 * <p>Regex uses RE2J (linear-time, no catastrophic backtracking). Patterns are compiled at most once
 * and memoized here, never per event on the hot path. The evaluator is stateless apart from that
 * thread-safe pattern cache, so a single instance is safe to share.
 */
public class TreeRoutingEvaluator implements RoutingPredicateEvaluator {

    private final ConcurrentHashMap<String, Pattern> regexCache = new ConcurrentHashMap<>();

    @Override
    public RoutingDecision evaluate(RoutingContext context, Ruleset ruleset, boolean trace) {
        ZonedDateTime now = context.evaluatedAt().atZone(ruleset.timezone());
        List<RoutingDecision.TraceEntry> entries = trace ? new ArrayList<>() : null;

        for (Rule rule : ruleset.rules()) {
            if (!rule.enabled()) {
                addTrace(entries, rule, false, "disabled");
                continue;
            }
            String[] reason = trace ? new String[1] : null;
            boolean condOk = matches(rule.condition(), context, reason);
            boolean timeOk = rule.timeRestriction() == null || rule.timeRestriction().matches(now);
            boolean matched = condOk && timeOk;

            if (matched) {
                addTrace(entries, rule, true, "matched");
                return RoutingDecision.matched(rule, ruleset.version(), finish(entries));
            }
            String detail = !condOk ? (reason != null && reason[0] != null ? reason[0] : "condition not met")
                    : "outside time restriction";
            addTrace(entries, rule, false, detail);
        }
        return RoutingDecision.fallback(ruleset.fallbackAction(), ruleset.version(), finish(entries));
    }

    private boolean matches(ConditionNode node, RoutingContext ctx, String[] reason) {
        if (node instanceof ConditionNode.Group g) {
            return switch (g.op()) {
                case ALL -> {
                    for (ConditionNode child : g.children()) {
                        if (!matches(child, ctx, reason)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case ANY -> {
                    for (ConditionNode child : g.children()) {
                        if (matches(child, ctx, null)) {
                            yield true;
                        }
                    }
                    setReason(reason, "no sub-condition matched");
                    yield false;
                }
                case NOT -> {
                    boolean inner = true;
                    for (ConditionNode child : g.children()) {
                        if (!matches(child, ctx, null)) {
                            inner = false;
                            break;
                        }
                    }
                    if (inner) {
                        setReason(reason, "negated condition matched");
                    }
                    yield !inner;
                }
            };
        }
        return matchesLeaf((ConditionNode.Leaf) node, ctx, reason);
    }

    private boolean matchesLeaf(ConditionNode.Leaf leaf, RoutingContext ctx, String[] reason) {
        RoutingContext.Resolved r = ctx.resolve(leaf.field());
        Operator op = leaf.operator();
        String label = label(leaf.field());

        if (op == Operator.EXISTS) {
            if (!r.present()) {
                setReason(reason, label + " absent");
            }
            return r.present();
        }
        if (op == Operator.NOT_EXISTS) {
            if (r.present()) {
                setReason(reason, label + " present");
            }
            return !r.present();
        }

        // All remaining operators need a present, non-null value. Missing or null -> non-match (and a
        // negative operator does NOT match on absence: that is exactly the designed-out PagerDuty gotcha).
        if (!r.present()) {
            setReason(reason, label + " missing");
            return false;
        }
        if (r.value() == null) {
            setReason(reason, label + " is null");
            return false;
        }

        String value = r.value();
        if (op.isNumeric()) {
            return matchesNumeric(op, value, leaf, reason, label);
        }

        boolean positive = positiveMatch(op, value, leaf);
        boolean result = op.isNegative() ? !positive : positive;
        if (!result) {
            setReason(reason, label + " '" + value + "' " + describe(op, leaf));
        }
        return result;
    }

    /** The non-negated comparison for an operator; negative operators are this result inverted. */
    private boolean positiveMatch(Operator op, String value, ConditionNode.Leaf leaf) {
        return switch (op) {
            case EQUALS, NOT_EQUALS -> value.equals(first(leaf));
            case IN, NOT_IN -> leaf.values().contains(value);
            case CONTAINS_SUBSTRING, NOT_CONTAINS_SUBSTRING -> value.contains(first(leaf));
            case STARTS_WITH -> value.startsWith(first(leaf));
            case ENDS_WITH -> value.endsWith(first(leaf));
            case MATCHES_REGEX, NOT_MATCHES_REGEX -> pattern(first(leaf)).matches(value);
            default -> throw new IllegalStateException("non-comparison operator: " + op);
        };
    }

    private boolean matchesNumeric(Operator op, String value, ConditionNode.Leaf leaf,
                                   String[] reason, String label) {
        Double left = parse(value);
        Double right = parse(first(leaf));
        if (left == null || right == null) {
            setReason(reason, label + " '" + value + "' is not numeric (type mismatch)");
            return false;
        }
        int c = Double.compare(left, right);
        boolean ok = switch (op) {
            case GT -> c > 0;
            case GTE -> c >= 0;
            case LT -> c < 0;
            case LTE -> c <= 0;
            default -> false;
        };
        if (!ok) {
            setReason(reason, label + " " + value + " not " + op + " " + first(leaf));
        }
        return ok;
    }

    private Pattern pattern(String regex) {
        return regexCache.computeIfAbsent(regex, Pattern::compile);
    }

    private static Double parse(String s) {
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String first(ConditionNode.Leaf leaf) {
        return leaf.values().isEmpty() ? "" : leaf.values().get(0);
    }

    private static String label(FieldRef f) {
        return f.kind() == FieldRef.Kind.METADATA ? "metadata." + f.name() : f.name();
    }

    private static String describe(Operator op, ConditionNode.Leaf leaf) {
        return switch (op) {
            case EQUALS -> "!= '" + first(leaf) + "'";
            case NOT_EQUALS -> "== '" + first(leaf) + "'";
            case IN -> "not in " + leaf.values();
            case NOT_IN -> "in " + leaf.values();
            case CONTAINS_SUBSTRING -> "does not contain '" + first(leaf) + "'";
            case NOT_CONTAINS_SUBSTRING -> "contains '" + first(leaf) + "'";
            case STARTS_WITH -> "does not start with '" + first(leaf) + "'";
            case ENDS_WITH -> "does not end with '" + first(leaf) + "'";
            case MATCHES_REGEX -> "does not match /" + first(leaf) + "/";
            case NOT_MATCHES_REGEX -> "matches /" + first(leaf) + "/";
            default -> op.toString();
        };
    }

    private static void setReason(String[] reason, String text) {
        if (reason != null && reason[0] == null) {
            reason[0] = text;
        }
    }

    private static void addTrace(List<RoutingDecision.TraceEntry> entries, Rule rule, boolean matched,
                                 String detail) {
        if (entries != null) {
            entries.add(new RoutingDecision.TraceEntry(rule.id(), rule.name(), matched, detail));
        }
    }

    private static List<RoutingDecision.TraceEntry> finish(List<RoutingDecision.TraceEntry> entries) {
        return entries == null ? List.of() : entries;
    }
}
