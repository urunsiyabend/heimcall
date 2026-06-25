package com.urunsiyabend.heimcall.routing;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A nestable, typed condition tree (Phase 17). A {@link Group} combines children with
 * {@code ALL}(AND) / {@code ANY}(OR) / {@code NOT}; a {@link Leaf} is a {@code field/operator/value}
 * test. Persisted as JSON; deliberately not a free-text expression language (injection-free,
 * UI-buildable, validatable at save). The evaluator interprets the tree directly (see
 * {@link RoutingPredicateEvaluator}) so an explanation like "rule 3 didn't match because metadata.env
 * was present but != prod" is cheap.
 *
 * <p>Polymorphic over a {@code "node"} discriminator (T2): the same shape the catalog codec emits to
 * the {@code condition_json} column and the migration SQL writes, so the stored form and the ruleset
 * snapshot that crosses the wire to incident-service are one definition.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "node")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConditionNode.Group.class, name = "GROUP"),
        @JsonSubTypes.Type(value = ConditionNode.Leaf.class, name = "LEAF")
})
public sealed interface ConditionNode permits ConditionNode.Group, ConditionNode.Leaf {

    enum BoolOp { ALL, ANY, NOT }

    /**
     * A boolean combinator over children. {@code NOT} negates the conjunction of its children
     * ({@code NOT == !ALL}). An empty {@code ALL} matches (vacuous truth) so an unconditional rule is
     * expressible as {@code ALL[]}; an empty {@code ANY} does not match.
     */
    record Group(BoolOp op, List<ConditionNode> children) implements ConditionNode {
        public Group {
            if (op == null) {
                throw new IllegalArgumentException("group op required");
            }
            children = children == null ? List.of() : List.copyOf(children);
            if (op == BoolOp.NOT && children.isEmpty()) {
                throw new IllegalArgumentException("NOT group requires at least one child");
            }
        }
    }

    /** A single {@code field operator value} test. {@code values} is empty for valueless operators
     *  ({@code EXISTS}/{@code NOT_EXISTS}), a singleton for scalar operators, and a non-empty list for
     *  {@code IN}/{@code NOT_IN}. */
    record Leaf(FieldRef field, Operator operator, List<String> values) implements ConditionNode {
        public Leaf {
            if (field == null) {
                throw new IllegalArgumentException("leaf field required");
            }
            if (operator == null) {
                throw new IllegalArgumentException("leaf operator required");
            }
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
