package com.urunsiyabend.heimcall.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urunsiyabend.heimcall.common.events.RoutingRulesetSnapshotEvent;
import com.urunsiyabend.heimcall.incident.domain.ProjectionState;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjection;
import com.urunsiyabend.heimcall.incident.domain.RoutingRulesetProjectionRepository;
import com.urunsiyabend.heimcall.routing.Ruleset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and version-gates writes to the local routing ruleset projection (Phase 17 T2). A snapshot is
 * applied only if its version is strictly greater than what is stored, so duplicate and out-of-order
 * delivery are idempotent/no-ops. Persists the serialized routing-core {@link Ruleset}; the hot path
 * loads it and evaluates locally.
 */
@Component
public class RoutingProjectionStore {

    private static final Logger log = LoggerFactory.getLogger(RoutingProjectionStore.class);

    private final RoutingRulesetProjectionRepository repo;
    private final ObjectMapper mapper;
    private final Duration maxAge;

    public RoutingProjectionStore(RoutingRulesetProjectionRepository repo, ObjectMapper mapper,
                                  @Value("${heimcall.routing.projection.max-age:PT10M}") Duration maxAge) {
        this.repo = repo;
        this.mapper = mapper;
        this.maxAge = maxAge;
    }

    /** A loaded projection: the parsed ruleset plus its version, state, and freshness at read time. */
    public record Loaded(Ruleset ruleset, long version, ProjectionState state, Instant observedAt) {
    }

    /**
     * Apply a snapshot under the version gate. Returns true if it was written (newer), false if it was
     * an older/duplicate no-op. A version-0 snapshot (from the pull endpoint when the org has no rules)
     * is stored as {@link ProjectionState#ABSENT_CONFIRMED}; version &gt;= 1 as {@link ProjectionState#READY}.
     */
    @Transactional
    public boolean apply(RoutingRulesetSnapshotEvent snapshot, Instant observedAt) {
        UUID orgId = snapshot.organizationId();
        Optional<RoutingRulesetProjection> existing = repo.findById(orgId);
        if (existing.isPresent() && existing.get().getVersion() >= snapshot.rulesetVersion()) {
            return false;
        }
        String payload = serialize(snapshot.ruleset());
        ProjectionState state = snapshot.rulesetVersion() == 0
                ? ProjectionState.ABSENT_CONFIRMED : ProjectionState.READY;
        if (existing.isPresent()) {
            existing.get().update(snapshot.rulesetVersion(), payload, state, observedAt);
        } else {
            repo.save(RoutingRulesetProjection.of(orgId, snapshot.rulesetVersion(), payload, state, observedAt));
        }
        log.debug("Applied routing ruleset projection org={} version={} state={}",
                orgId, snapshot.rulesetVersion(), state);
        return true;
    }

    /** Load the projection for an org. Returns empty (UNINITIALIZED) when no row exists; otherwise the
     *  parsed ruleset with state reported as STALE when older than the freshness policy. */
    @Transactional(readOnly = true)
    public Optional<Loaded> load(UUID orgId, Instant now) {
        return repo.findById(orgId).map(p -> {
            ProjectionState state = p.getState();
            if (state == ProjectionState.READY && p.getObservedAt().isBefore(now.minus(maxAge))) {
                state = ProjectionState.STALE;
            }
            return new Loaded(deserialize(p.getPayloadJson()), p.getVersion(), state, p.getObservedAt());
        });
    }

    private String serialize(Ruleset ruleset) {
        try {
            return mapper.writeValueAsString(ruleset);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize ruleset projection", e);
        }
    }

    private Ruleset deserialize(String json) {
        try {
            return mapper.readValue(json, Ruleset.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt ruleset projection payload", e);
        }
    }
}
