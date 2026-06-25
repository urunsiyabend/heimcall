package com.urunsiyabend.heimcall.catalog.routing;

import java.time.Instant;
import java.util.Map;

/**
 * The matchable projection of a normalized alert (Phase 17) — the left-hand side of a routing
 * decision. System fields are carried as Strings ({@code messageType}/{@code severity} as their enum
 * names) so the engine stays free of any domain-enum dependency; the caller builds this from the
 * {@code AlertReceivedEvent}. {@code evaluatedAt} drives time-restriction checks (against the
 * ruleset's timezone).
 *
 * <p>Resolution distinguishes <b>missing</b> from <b>null</b>: a SYSTEM field is always present (it is
 * a known schema field) but its value may be null; a METADATA field is present only if the key exists
 * in the map. {@link Operator}-level semantics consume this distinction.
 */
public record RoutingContext(String routingKey, String source, String messageType, String severity,
                             String externalEntityId, String title, String description,
                             Map<String, String> metadata, Instant evaluatedAt) {

    public RoutingContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt required");
        }
    }

    /** A field lookup outcome: {@code present} = the field/key exists at all; {@code value} = its value
     *  (may be {@code null} even when present). */
    public record Resolved(boolean present, String value) {
        static final Resolved ABSENT = new Resolved(false, null);
    }

    public Resolved resolve(FieldRef ref) {
        return switch (ref.kind()) {
            case METADATA -> metadata.containsKey(ref.name())
                    ? new Resolved(true, metadata.get(ref.name()))
                    : Resolved.ABSENT;
            case SYSTEM -> switch (ref.name()) {
                case "routingKey" -> new Resolved(true, routingKey);
                case "source" -> new Resolved(true, source);
                case "messageType" -> new Resolved(true, messageType);
                case "severity" -> new Resolved(true, severity);
                case "externalEntityId" -> new Resolved(true, externalEntityId);
                case "title" -> new Resolved(true, title);
                case "description" -> new Resolved(true, description);
                // Unknown system field name (should be caught by save-time validation) -> absent.
                default -> Resolved.ABSENT;
            };
        };
    }
}
