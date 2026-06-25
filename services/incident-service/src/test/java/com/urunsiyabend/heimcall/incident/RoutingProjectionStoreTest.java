package com.urunsiyabend.heimcall.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.incident.domain.ProjectionState;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjection;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjectionRepository;
import com.urunsiyabend.heimcall.routing.RoutingAction;
import com.urunsiyabend.heimcall.routing.Ruleset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 17 T2: the version gate is the consistency mechanism for the at-least-once, out-of-order
 * snapshot stream. A snapshot applies only when strictly newer; duplicates and older replays are
 * no-ops. Freshness (STALE) is derived from observed_at, never persisted, and never causes a drop.
 */
@ExtendWith(MockitoExtension.class)
class RoutingProjectionStoreTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    RoutingRulesetProjectionRepository repo;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private RoutingProjectionStore store() {
        return new RoutingProjectionStore(repo, mapper, Duration.ofMinutes(10));
    }

    private RoutingRulesetSnapshotEvent snapshot(long version) {
        Ruleset ruleset = new Ruleset(version, ZoneId.of("UTC"), List.of(), RoutingAction.unrouted());
        return new RoutingRulesetSnapshotEvent(UUID.randomUUID(), ORG, version, ruleset, Instant.now());
    }

    private RoutingRulesetProjection stored(long version, Instant observedAt) {
        Ruleset ruleset = new Ruleset(version, ZoneId.of("UTC"), List.of(), RoutingAction.unrouted());
        String json;
        try {
            json = mapper.writeValueAsString(ruleset);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return RoutingRulesetProjection.of(ORG, version, json, ProjectionState.READY, observedAt);
    }

    @Test
    void appliesWhenNoExisting() {
        when(repo.findById(ORG)).thenReturn(Optional.empty());

        boolean applied = store().apply(snapshot(5), Instant.now());

        assertThat(applied).isTrue();
        verify(repo).save(any(RoutingRulesetProjection.class));
    }

    @Test
    void appliesWhenStrictlyNewer() {
        RoutingRulesetProjection existing = stored(5, Instant.now());
        when(repo.findById(ORG)).thenReturn(Optional.of(existing));

        boolean applied = store().apply(snapshot(7), Instant.now());

        assertThat(applied).isTrue();
        assertThat(existing.getVersion()).isEqualTo(7);
        verify(repo, never()).save(any());
    }

    @Test
    void ignoresOlderOrEqualSnapshot() {
        RoutingRulesetProjection existing = stored(7, Instant.now());
        when(repo.findById(ORG)).thenReturn(Optional.of(existing));

        assertThat(store().apply(snapshot(7), Instant.now())).isFalse();
        assertThat(store().apply(snapshot(3), Instant.now())).isFalse();
        assertThat(existing.getVersion()).isEqualTo(7);
        verify(repo, never()).save(any());
    }

    @Test
    void versionZeroSnapshotIsAbsentConfirmed() {
        when(repo.findById(ORG)).thenReturn(Optional.empty());

        store().apply(snapshot(0), Instant.now());

        org.mockito.ArgumentCaptor<RoutingRulesetProjection> captor =
                org.mockito.ArgumentCaptor.forClass(RoutingRulesetProjection.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(ProjectionState.ABSENT_CONFIRMED);
    }

    @Test
    void loadReportsStaleBeyondFreshnessWindow() {
        when(repo.findById(ORG)).thenReturn(Optional.of(stored(5, Instant.now().minusSeconds(3600))));

        Optional<RoutingProjectionStore.Loaded> loaded = store().load(ORG, Instant.now());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().state()).isEqualTo(ProjectionState.STALE);
        assertThat(loaded.get().version()).isEqualTo(5);
    }

    @Test
    void loadReportsReadyWithinWindow() {
        when(repo.findById(ORG)).thenReturn(Optional.of(stored(5, Instant.now())));

        Optional<RoutingProjectionStore.Loaded> loaded = store().load(ORG, Instant.now());

        assertThat(loaded.get().state()).isEqualTo(ProjectionState.READY);
    }

    @Test
    void loadEmptyWhenUninitialized() {
        when(repo.findById(ORG)).thenReturn(Optional.empty());

        assertThat(store().load(ORG, Instant.now())).isEmpty();
    }
}
