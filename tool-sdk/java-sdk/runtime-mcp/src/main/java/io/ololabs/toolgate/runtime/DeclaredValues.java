// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.sdk.context.Jwt;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed reads over declared values. Reading an undeclared name, or with a getter that does not fit the declared
 * type, is a {@link ToolConfigurationError}; a declared value with nothing behind it raises {@code onMissing}.
 */
class DeclaredValues implements Jwt {
    /** A declared value: its value type name and the value, or null. */
    record Entry(String type, JsonNode value) {}

    private final String kind;
    private final Map<String, Entry> entries;
    private final java.util.function.Function<String, RuntimeException> onMissing;

    DeclaredValues(String kind, Map<String, Entry> entries, java.util.function.Function<String, RuntimeException> onMissing) {
        this.kind = kind;
        this.entries = entries;
        this.onMissing = onMissing;
    }

    private JsonNode read(String name, Set<String> types, String getter) {
        Entry e = entries.get(name);
        if (e == null) throw new ToolConfigurationError(kind + " " + name + " is not declared for this tool");
        if (!types.contains(e.type())) {
            throw new ToolConfigurationError(kind + " " + name + " is " + e.type() + "; " + getter + " does not read it");
        }
        if (e.value() == null || e.value().isNull()) throw onMissing.apply(name);
        return e.value();
    }

    private static final Set<String> TEXT = Set.of("STRING", "ENUM", "URI", "DATE", "DATE_TIME", "DURATION");

    @Override
    public String getStringValue(String name) {
        return read(name, TEXT, "getStringValue").asText();
    }

    @Override
    public int getIntValue(String name) {
        return (Integer) Converter.fromJson(read(name, Set.of("INT"), "getIntValue"), int.class);
    }

    @Override
    public long getLongValue(String name) {
        return (Long) Converter.fromJson(read(name, Set.of("INT", "LONG"), "getLongValue"), long.class);
    }

    @Override
    public double getNumberValue(String name) {
        return (Double) Converter.fromJson(read(name, Set.of("INT", "LONG", "NUMBER"), "getNumberValue"), double.class);
    }

    @Override
    public boolean getBooleanValue(String name) {
        return (Boolean) Converter.fromJson(read(name, Set.of("BOOLEAN"), "getBooleanValue"), boolean.class);
    }

    @Override
    public LocalDate getDateValue(String name) {
        return (LocalDate) Converter.fromJson(read(name, Set.of("DATE"), "getDateValue"), LocalDate.class);
    }

    @Override
    public OffsetDateTime getDateTimeValue(String name) {
        return (OffsetDateTime) Converter.fromJson(read(name, Set.of("DATE_TIME"), "getDateTimeValue"), OffsetDateTime.class);
    }

    @Override
    public Duration getDurationValue(String name) {
        return (Duration) Converter.fromJson(read(name, Set.of("DURATION"), "getDurationValue"), Duration.class);
    }

    @Override
    public <T> T getObjectValue(String name, Class<T> type) {
        return type.cast(Converter.fromJson(read(name, Set.of("OBJECT"), "getObjectValue"), type));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getListValue(String name, Class<T> type) {
        Type listOf = new ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return new Type[] {type}; }
            @Override public Type getRawType() { return List.class; }
            @Override public Type getOwnerType() { return null; }
        };
        return (List<T>) Converter.fromJson(read(name, Set.of("ARRAY"), "getListValue"), listOf);
    }

    @Override
    public byte[] getBinaryValue(String name) {
        return (byte[]) Converter.fromJson(read(name, Set.of("BINARY"), "getBinaryValue"), byte[].class);
    }

    // Jwt members; only the claims accessor exposes them.
    JsonNode identity;

    @Override
    public String getUserName() {
        return standard("preferred_username");
    }

    @Override
    public String getSubject() {
        return standard("sub");
    }

    @Override
    public String getIssuer() {
        return standard("iss");
    }

    private String standard(String claim) {
        if (identity == null) throw new PlatformError(PlatformError.Code.CLAIMS_UNAVAILABLE, "No verified identity is available on this transport");
        JsonNode v = identity.get(claim);
        if (v == null || !v.isTextual()) throw new PlatformError(PlatformError.Code.CLAIMS_UNAVAILABLE, "The identity has no " + claim + " claim");
        return v.asText();
    }

    /** The value type name of a descriptor property schema (authoring README §7). */
    static String typeOf(JsonNode schema) {
        String type = schema.path("type").asText("");
        return switch (type) {
            case "integer" -> schema.path("maximum").asLong(Long.MAX_VALUE) <= Integer.MAX_VALUE
                    && schema.path("minimum").asLong(Long.MIN_VALUE) >= Integer.MIN_VALUE ? "INT" : "LONG";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "object" -> "OBJECT";
            case "array" -> "ARRAY";
            case "string" -> {
                if (schema.has("enum")) yield "ENUM";
                if ("base64".equals(schema.path("contentEncoding").asText(null))) yield "BINARY";
                yield switch (schema.path("format").asText("")) {
                    case "date" -> "DATE";
                    case "date-time" -> "DATE_TIME";
                    case "duration" -> "DURATION";
                    case "uri" -> "URI";
                    default -> "STRING";
                };
            }
            default -> "OBJECT";
        };
    }
}
