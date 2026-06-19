package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.incident.CatalogClient.Routing;
import com.urunsiyabend.heimcall.incident.domain.RoutingCacheStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13 T1: the {@link RoutingAvailabilityResolver} decision table (Phase 10 T4) — the highest-risk
 * routing logic, where a wrong branch silently de-pages a real incident or pages from a dead route.
 * Pure Mockito: {@link CatalogClient} and {@link RoutingCacheStore} are stubbed, no infra.
 */
@ExtendWith(MockitoExtension.class)
class RoutingAvailabilityResolverTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String KEY = "backend-critical";

    @Mock
    CatalogClient catalog;
    @Mock
    RoutingCacheStore cache;
    @InjectMocks
    RoutingAvailabilityResolver resolver;

    @Test
    void catalog200_writesThroughCache_andRoutes() {
        Routing live = new Routing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(catalog.resolve(ORG, KEY)).thenReturn(Optional.of(live));

        RoutingDecision decision = resolver.resolve(ORG, KEY);

        assertThat(decision.unrouted()).isFalse();
        assertThat(decision.fromCache()).isFalse();
        assertThat(decision.policyId()).isEqualTo(live.escalationPolicyId());
        assertThat(decision.serviceId()).isEqualTo(live.serviceId());
        // Write-through so this stays last-known-good for a future outage.
        verify(cache).put(eq(ORG), eq(KEY), eq(live), any(Instant.class));
        verify(cache, never()).evict(any(), any());
    }

    @Test
    void catalog404_tombstonesCache_andIsUnrouted() {
        when(catalog.resolve(ORG, KEY)).thenReturn(Optional.empty());

        RoutingDecision decision = resolver.resolve(ORG, KEY);

        assertThat(decision.unrouted()).isTrue();
        assertThat(decision.fromCache()).isFalse();
        assertThat(decision.policyId()).isNull();
        // A dead route must never survive to page from cache on a later outage.
        verify(cache).evict(ORG, KEY);
        verify(cache, never()).put(any(), any(), any(), any());
    }

    @Test
    void outageWithCacheHit_pagesFromCache() {
        Routing cached = new Routing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(catalog.resolve(ORG, KEY)).thenThrow(new RoutingUnavailableException(KEY, new RuntimeException("down")));
        when(cache.find(ORG, KEY)).thenReturn(Optional.of(cached));

        RoutingDecision decision = resolver.resolve(ORG, KEY);

        assertThat(decision.fromCache()).isTrue();
        assertThat(decision.unrouted()).isFalse();
        assertThat(decision.policyId()).isEqualTo(cached.escalationPolicyId());
        // Outage path must not mutate the cache.
        verify(cache, never()).put(any(), any(), any(), any());
        verify(cache, never()).evict(any(), any());
    }

    @Test
    void outageWithCacheMiss_rethrowsForRetryDlt() {
        RoutingUnavailableException outage = new RoutingUnavailableException(KEY, new RuntimeException("down"));
        when(catalog.resolve(ORG, KEY)).thenThrow(outage);
        when(cache.find(ORG, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(ORG, KEY))
                .isInstanceOf(RoutingUnavailableException.class);
    }
}
