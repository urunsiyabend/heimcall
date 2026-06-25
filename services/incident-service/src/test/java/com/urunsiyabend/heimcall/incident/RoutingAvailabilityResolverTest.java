package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.incident.RoutingProjectionStore.Loaded;
import com.urunsiyabend.heimcall.incident.domain.ProjectionState;
import com.urunsiyabend.heimcall.routing.ConditionNode;
import com.urunsiyabend.heimcall.routing.FieldRef;
import com.urunsiyabend.heimcall.routing.Operator;
import com.urunsiyabend.heimcall.routing.Rule;
import com.urunsiyabend.heimcall.routing.RoutingAction;
import com.urunsiyabend.heimcall.routing.Ruleset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 17 T2: the resolver now evaluates the locally replicated ruleset (catalog off the hot path).
 * Verifies the projection states drive the right behavior — READY/STALE route locally, a cold miss
 * lazily hydrates, and a cold miss with catalog also down falls to a deliberate UNROUTED (never a
 * misroute). The real {@code TreeRoutingEvaluator} runs inside the resolver, so these also prove the
 * decision is produced from the replicated ruleset.
 */
@ExtendWith(MockitoExtension.class)
class RoutingAvailabilityResolverTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SVC = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();

    @Mock
    RoutingProjectionStore store;
    @Mock
    CatalogClient catalog;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RoutingAvailabilityResolver resolver() {
        return new RoutingAvailabilityResolver(store, catalog, registry);
    }

    private AlertReceivedEvent event() {
        return new AlertReceivedEvent(UUID.randomUUID(), Instant.now(), ORG, UUID.randomUUID(),
                "payments", "grafana", MessageType.CRITICAL, "ext-1", "dedup",
                "Payment API 5xx", "error rate high", Severity.CRITICAL, Map.of());
    }

    private Ruleset rulesetMatchingPayments(long version) {
        Rule rule = new Rule(UUID.randomUUID(), "payments", true,
                new ConditionNode.Leaf(FieldRef.system("routingKey"), Operator.EQUALS, List.of("payments")),
                RoutingAction.route(SVC, POLICY), null);
        return new Ruleset(version, ZoneId.of("UTC"), List.of(rule), RoutingAction.unrouted());
    }

    @Test
    void readyProjectionRoutesLocally() {
        when(store.load(eq(ORG), any(Instant.class)))
                .thenReturn(Optional.of(new Loaded(rulesetMatchingPayments(5), 5, ProjectionState.READY, Instant.now())));

        RoutingDecision d = resolver().resolve(event());

        assertThat(d.unrouted()).isFalse();
        assertThat(d.policyId()).isEqualTo(POLICY);
        assertThat(d.serviceId()).isEqualTo(SVC);
        assertThat(d.rulesetVersion()).isEqualTo(5);
        verify(catalog, never()).fetchRuleset(any());
    }

    @Test
    void coldMissHydratesFromCatalogThenRoutes() {
        when(store.load(eq(ORG), any(Instant.class))).thenReturn(Optional.empty());
        RoutingRulesetSnapshotEvent snapshot = new RoutingRulesetSnapshotEvent(
                UUID.randomUUID(), ORG, 5, rulesetMatchingPayments(5), Instant.now());
        when(catalog.fetchRuleset(ORG)).thenReturn(snapshot);

        RoutingDecision d = resolver().resolve(event());

        assertThat(d.policyId()).isEqualTo(POLICY);
        verify(store).apply(eq(snapshot), any(Instant.class));
    }

    @Test
    void coldMissWithCatalogDownFallsToUnrouted() {
        when(store.load(eq(ORG), any(Instant.class))).thenReturn(Optional.empty());
        when(catalog.fetchRuleset(ORG))
                .thenThrow(new RoutingUnavailableException(null, new RuntimeException("down")));

        RoutingDecision d = resolver().resolve(event());

        assertThat(d.unrouted()).isTrue();
        assertThat(d.policyId()).isNull();
        assertThat(d.rulesetVersion()).isEqualTo(0);
    }

    @Test
    void staleProjectionStillRoutesOnLastKnown() {
        when(store.load(eq(ORG), any(Instant.class)))
                .thenReturn(Optional.of(new Loaded(rulesetMatchingPayments(5), 5, ProjectionState.STALE,
                        Instant.now().minusSeconds(7200))));

        RoutingDecision d = resolver().resolve(event());

        assertThat(d.unrouted()).isFalse();
        assertThat(d.policyId()).isEqualTo(POLICY);
        verify(catalog, never()).fetchRuleset(any());
    }
}
