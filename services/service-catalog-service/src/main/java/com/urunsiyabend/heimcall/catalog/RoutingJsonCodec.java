package com.urunsiyabend.heimcall.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urunsiyabend.heimcall.routing.ConditionNode;
import com.urunsiyabend.heimcall.routing.FieldRef;
import com.urunsiyabend.heimcall.routing.Operator;
import com.urunsiyabend.heimcall.routing.TimeRestriction;
import com.urunsiyabend.heimcall.catalog.web.ApiExceptions;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hand-written JSON codec between the request/stored JSON and the pure routing-core model (Phase 17).
 * Lives in service-catalog (not routing-core) so the engine stays Jackson-free and the wire shape is
 * pinned here — the same shape {@code V4__routing_rules.sql} emits for migrated rules. Structural
 * problems on the request path surface as 400s; reading trusted stored JSON should never fail.
 *
 * <p>Shapes:
 * <pre>
 *   GROUP: {"node":"GROUP","op":"ALL|ANY|NOT","children":[...]}
 *   LEAF:  {"node":"LEAF","field":{"kind":"SYSTEM|METADATA","name":"..."},"operator":"EQUALS","values":["a"]}
 *   TIME:  {"days":["MONDAY",...],"start":"09:00","end":"17:00"}
 * </pre>
 */
@Component
public class RoutingJsonCodec {

    private final ObjectMapper mapper;

    public RoutingJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // --- condition tree ---

    public ConditionNode parseCondition(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw bad("condition must be a JSON object");
        }
        String kind = text(node, "node");
        return switch (kind) {
            case "GROUP" -> parseGroup(node);
            case "LEAF" -> parseLeaf(node);
            default -> throw bad("condition node must be GROUP or LEAF, was: " + kind);
        };
    }

    private ConditionNode parseGroup(JsonNode node) {
        ConditionNode.BoolOp op = enumValue(ConditionNode.BoolOp.class, text(node, "op"), "group op");
        JsonNode children = node.get("children");
        if (children != null && !children.isArray()) {
            throw bad("group children must be an array");
        }
        List<ConditionNode> parsed = new ArrayList<>();
        if (children != null) {
            for (JsonNode child : children) {
                parsed.add(parseCondition(child));
            }
        }
        try {
            return new ConditionNode.Group(op, parsed);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private ConditionNode parseLeaf(JsonNode node) {
        JsonNode field = node.get("field");
        if (field == null || !field.isObject()) {
            throw bad("leaf field must be a JSON object");
        }
        FieldRef.Kind kind = enumValue(FieldRef.Kind.class, text(field, "kind"), "field kind");
        String name = text(field, "name");
        Operator operator = enumValue(Operator.class, text(node, "operator"), "operator");
        List<String> values = new ArrayList<>();
        JsonNode valuesNode = node.get("values");
        if (valuesNode != null) {
            if (!valuesNode.isArray()) {
                throw bad("leaf values must be an array");
            }
            for (JsonNode v : valuesNode) {
                values.add(v.asText());
            }
        }
        try {
            return new ConditionNode.Leaf(new FieldRef(kind, name), operator, values);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    public String toJson(ConditionNode node) {
        return write(toNode(node));
    }

    /** Re-parse a stored JSON string into a tree node for API responses (structured, not a string). */
    public JsonNode rawNode(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored JSON", e);
        }
    }

    public JsonNode toNode(ConditionNode node) {
        if (node instanceof ConditionNode.Group g) {
            ObjectNode o = mapper.createObjectNode();
            o.put("node", "GROUP");
            o.put("op", g.op().name());
            ArrayNode children = o.putArray("children");
            for (ConditionNode child : g.children()) {
                children.add(toNode(child));
            }
            return o;
        }
        ConditionNode.Leaf leaf = (ConditionNode.Leaf) node;
        ObjectNode o = mapper.createObjectNode();
        o.put("node", "LEAF");
        ObjectNode field = o.putObject("field");
        field.put("kind", leaf.field().kind().name());
        field.put("name", leaf.field().name());
        o.put("operator", leaf.operator().name());
        ArrayNode values = o.putArray("values");
        leaf.values().forEach(values::add);
        return o;
    }

    public ConditionNode readCondition(String json) {
        try {
            return parseCondition(mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored condition_json", e);
        }
    }

    // --- time restriction ---

    public TimeRestriction parseTimeRestriction(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw bad("timeRestriction must be a JSON object");
        }
        Set<DayOfWeek> days = new LinkedHashSet<>();
        JsonNode daysNode = node.get("days");
        if (daysNode != null) {
            if (!daysNode.isArray()) {
                throw bad("timeRestriction.days must be an array");
            }
            for (JsonNode d : daysNode) {
                days.add(enumValue(DayOfWeek.class, d.asText(), "day-of-week"));
            }
        }
        LocalTime start = parseTime(text(node, "start"));
        LocalTime end = parseTime(text(node, "end"));
        return new TimeRestriction(days, start, end);
    }

    public String toJson(TimeRestriction tr) {
        if (tr == null) {
            return null;
        }
        ObjectNode o = mapper.createObjectNode();
        ArrayNode days = o.putArray("days");
        tr.days().forEach(d -> days.add(d.name()));
        o.put("start", tr.start().toString());
        o.put("end", tr.end().toString());
        return write(o);
    }

    public TimeRestriction readTimeRestriction(String json) {
        if (json == null) {
            return null;
        }
        try {
            return parseTimeRestriction(mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored time_restriction_json", e);
        }
    }

    // --- helpers ---

    private LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            throw bad("invalid time (expected HH:mm): " + s);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            throw bad("missing or non-text field: " + field);
        }
        return v.asText();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw bad("invalid " + label + ": " + value);
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize routing JSON", e);
        }
    }

    private static ApiExceptions.BadRequestException bad(String message) {
        return new ApiExceptions.BadRequestException(message);
    }
}
