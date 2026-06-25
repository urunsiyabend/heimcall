package com.urunsiyabend.heimcall.catalog.routing;

/**
 * Evaluates an alert against a ruleset and returns the single routing decision (Phase 17). A pure,
 * I/O-free function {@code (context, ruleset) -> decision}. The interface is the seam behind which a
 * future {@code CelPredicateEvaluator} (advanced expression mode, Phase 17 T3) could sit; for now the
 * only implementation is {@link TreeRoutingEvaluator}, which interprets the condition tree directly.
 *
 * <p>In T1 this lives inside service-catalog-service (the only evaluator). In T2 it is extracted to a
 * shared {@code libs/routing-core} so incident-service can evaluate the replicated ruleset locally and
 * byte-identically to catalog's preview. It must stay Spring/JPA-free so that extraction is a move.
 */
public interface RoutingPredicateEvaluator {

    /**
     * @param trace when true, populate {@link RoutingDecision#trace()} with a per-rule explanation
     *              (used by the dry-run preview); when false, skip the trace work on the hot path.
     */
    RoutingDecision evaluate(RoutingContext context, Ruleset ruleset, boolean trace);
}
