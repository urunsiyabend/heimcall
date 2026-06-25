package com.urunsiyabend.heimcall.catalog.routing;

/**
 * A typed reference to a matchable field on the normalized alert (Phase 17). Deliberately NOT a
 * free JSONPath string: the grammar stays finite so it is UI-buildable and validatable at save time.
 *
 * <ul>
 *   <li>{@link Kind#SYSTEM} — a known schema field on the event ({@code routingKey}, {@code source},
 *       {@code messageType}, {@code severity}, {@code externalEntityId}, {@code title},
 *       {@code description}); {@link #name} is the field name.</li>
 *   <li>{@link Kind#METADATA} — an arbitrary key in the event's {@code metadata} map;
 *       {@link #name} is that key.</li>
 * </ul>
 */
public record FieldRef(Kind kind, String name) {

    public enum Kind { SYSTEM, METADATA }

    public FieldRef {
        if (kind == null) {
            throw new IllegalArgumentException("field kind required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name/key required");
        }
    }

    public static FieldRef system(String name) {
        return new FieldRef(Kind.SYSTEM, name);
    }

    public static FieldRef metadata(String key) {
        return new FieldRef(Kind.METADATA, key);
    }
}
