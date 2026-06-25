package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.domain.MessageType;
import com.urunsiyabend.heimcall.common.domain.Severity;
import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.incident.CatalogClient.RoutingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 17 T1: the {@link RoutingAvailabilityResolver} is now a thin, cache-free wrapper over
 * {@link CatalogClient}. A successful resolve is either ROUTED or a deliberate UNROUTED; a catalog
 * OUTAGE propagates as {@link RoutingUnavailableException} (retry/DLT, no misroute) — the documented
 * T1 availability regression vs Phase 10 T4, restored correctly by the T2 local projection.
 */
@ExtendWith(MockitoExtension.class)
class RoutingAvailabilityResolverTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    CatalogClient catalog;
    @InjectMocks
    RoutingAvailabilityResolver resolver;

    private AlertReceivedEvent event() {
        return new AlertReceivedEvent(UUID.randomUUID(), Instant.now(), ORG, UUID.randomUUID(),
                "backend-critical", "grafana", MessageType.CRITICAL, "ext-1", "dedup",
                "Payment API 5xx", "error rate high", Severity.CRITICAL, Map.of());
    }

    @Test
    void routedDecisionPassesThrough() {
        UUID svc = UUID.randomUUID();
        UUID policy = UUID.randomUUID();
        UUID rule = UUID.randomUUID();
        when(catalog.resolve(eq(ORG), any(AlertReceivedEvent.class)))
                .thenReturn(new RoutingResult(svc, policy, rule, 4L, false));

        RoutingDecision decision = resolver.resolve(event());

        assertThat(decision.unrouted()).isFalse();
        assertThat(decision.fromCache()).isFalse();
        assertThat(decision.serviceId()).isEqualTo(svc);
        assertThat(decision.policyId()).isEqualTo(policy);
        assertThat(decision.matchedRuleId()).isEqualTo(rule);
        assertThat(decision.rulesetVersion()).isEqualTo(4L);
    }

    @Test
    void unroutedDecisionPassesThrough() {
        when(catalog.resolve(eq(ORG), any(AlertReceivedEvent.class)))
                .thenReturn(new RoutingResult(null, null, null, 4L, true));

        RoutingDecision decision = resolver.resolve(event());

        assertThat(decision.unrouted()).isTrue();
        assertThat(decision.policyId()).isNull();
    }

    @Test
    void outageRethrowsForRetryDlt() {
        when(catalog.resolve(eq(ORG), any(AlertReceivedEvent.class)))
                .thenThrow(new RoutingUnavailableException("backend-critical", new RuntimeException("down")));

        assertThatThrownBy(() -> resolver.resolve(event()))
                .isInstanceOf(RoutingUnavailableException.class);
    }
}
