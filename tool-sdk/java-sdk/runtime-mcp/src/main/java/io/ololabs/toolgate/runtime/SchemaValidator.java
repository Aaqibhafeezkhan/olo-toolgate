// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema 2020-12 validation for the keywords descriptors use (descriptor §5) plus the common structural ones.
 * Unknown keywords are ignored. Returns the first violation, or null.
 */
final class SchemaValidator {
    private final JsonNode root;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    SchemaValidator(JsonNode root) {
        this.root = root;
    }

    String validate(JsonNode value) {
        return validate(root, value, "$", 0);
    }

    private String validate(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > 64) return path + ": too deeply nested";
        if (schema == null || schema.isBoolean()) {
            return schema != null && !schema.asBoolean() ? path + ": no value is allowed" : null;
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null) {
            String r = ref.asText();
            if (!r.startsWith("#/$defs/")) return path + ": unsupported $ref " + r;
            String failure = validate(root.path("$defs").get(r.substring("#/$defs/".length())), value, path, depth + 1);
            if (failure != null) return failure;
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) return path + ": expected " + type.asText(type.toString());
        JsonNode e = schema.get("enum");
        if (e != null) {
            boolean found = false;
            for (JsonNode option : e) found |= equal(option, value);
            if (!found) return path + ": not one of the allowed values";
        }
        if (schema.has("const") && !equal(schema.get("const"), value)) return path + ": not the allowed value";
        if (value.isNumber()) {
            String failure = number(schema, value.decimalValue(), path);
            if (failure != null) return failure;
        }
        if (value.isTextual()) {
            String failure = string(schema, value.asText(), path);
            if (failure != null) return failure;
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asLong()) return path + ": too few items";
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asLong()) return path + ": too many items";
            if (schema.path("uniqueItems").asBoolean(false)) {
                Set<JsonNode> seen = new HashSet<>();
                for (JsonNode item : value) if (!seen.add(item)) return path + ": items are not unique";
            }
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < value.size(); i++) {
                    String failure = validate(items, value.get(i), path + "[" + i + "]", depth + 1);
                    if (failure != null) return failure;
                }
            }
        }
        if (value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null) {
                for (JsonNode name : required) if (!value.has(name.asText())) return path + ": " + name.asText() + " is required";
            }
            JsonNode properties = schema.path("properties");
            JsonNode additional = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String child = path + "." + f.getKey();
                JsonNode property = properties.get(f.getKey());
                String failure;
                if (property != null) failure = validate(property, f.getValue(), child, depth + 1);
                else if (additional != null) failure = validate(additional, f.getValue(), child, depth + 1);
                else failure = null;
                if (failure != null) return failure.equals(child + ": no value is allowed") ? child + ": is not allowed" : failure;
            }
        }
        return null;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode t : type) if (typeMatches(t, value)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && isWhole(value.decimalValue()));
            default -> false;
        };
    }

    private static boolean isWhole(BigDecimal d) {
        return d.stripTrailingZeros().scale() <= 0;
    }

    private static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        return a.equals(b);
    }

    private static String number(JsonNode schema, BigDecimal v, String path) {
        if (schema.has("minimum") && v.compareTo(schema.get("minimum").decimalValue()) < 0) return path + ": below the minimum " + schema.get("minimum");
        if (schema.has("maximum") && v.compareTo(schema.get("maximum").decimalValue()) > 0) return path + ": above the maximum " + schema.get("maximum");
        if (schema.has("exclusiveMinimum") && v.compareTo(schema.get("exclusiveMinimum").decimalValue()) <= 0) return path + ": not above " + schema.get("exclusiveMinimum");
        if (schema.has("exclusiveMaximum") && v.compareTo(schema.get("exclusiveMaximum").decimalValue()) >= 0) return path + ": not below " + schema.get("exclusiveMaximum");
        return null;
    }

    private String string(JsonNode schema, String s, String path) {
        int length = s.codePointCount(0, s.length());
        if (schema.has("minLength") && length < schema.get("minLength").asLong()) return path + ": shorter than " + schema.get("minLength");
        if (schema.has("maxLength") && length > schema.get("maxLength").asLong()) return path + ": longer than " + schema.get("maxLength");
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            Pattern p;
            try {
                p = patterns.computeIfAbsent(pattern.asText(), Pattern::compile);
            } catch (PatternSyntaxException e) {
                return path + ": the schema pattern does not compile";
            }
            if (!p.matcher(s).find()) return path + ": does not match the pattern";
        }
        JsonNode format = schema.get("format");
        if (format != null && !formatMatches(format.asText(), s)) return path + ": not a valid " + format.asText();
        if ("base64".equals(schema.path("contentEncoding").asText(null))) {
            try {
                Base64.getDecoder().decode(s);
            } catch (IllegalArgumentException e) {
                return path + ": not valid base64";
            }
        }
        return null;
    }

    private static boolean formatMatches(String format, String s) {
        try {
            switch (format) {
                case "date" -> LocalDate.parse(s);
                case "date-time" -> OffsetDateTime.parse(s);
                case "duration" -> Duration.parse(s);
                case "uri" -> {
                    if (!new URI(s).isAbsolute()) return false;
                }
                default -> {
                    return true;
                }
            }
            return true;
        } catch (DateTimeParseException | URISyntaxException e) {
            return false;
        }
    }
}
