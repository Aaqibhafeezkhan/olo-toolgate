// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.jsonsdk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict JSON authoring model. JSON is source, never an executable runtime registration. */
public final class JsonProject {
    public static final int MAX_BYTES = 65_536;
    public static final String FILE = "toolgate.json";
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    public static String sdkVersion() {
        var properties = new java.util.Properties();
        try (var in = JsonProject.class.getResourceAsStream("/json-sdk.properties")) {
            if (in == null) throw new IllegalStateException("JSON SDK version resource is missing");
            properties.load(in); return properties.getProperty("sdkVersion");
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public record Input(String name, String description, String defaultValue, int maxLength) {}
    public record Part(String literal, String input) {}
    public record Output(String name, List<Part> parts) {}
    public record Event(String level, String message, Double fraction) {}
    public record Tool(String name, String kind, String title, String description,
                       List<Input> inputs, List<Output> outputs, List<Event> events) {}
    private final JsonNode document;
    private final List<Tool> tools;

    private JsonProject(JsonNode document, List<Tool> tools) { this.document = document; this.tools = List.copyOf(tools); }
    public String id() { return document.path("id").asText(); }
    public String name() { return document.path("name").asText(); }
    public String publisher() { return document.path("publisher").asText(); }
    public String version() { return document.path("version").asText(); }
    public List<Tool> tools() { return tools; }
    public JsonNode document() { return document.deepCopy(); }

    public static JsonProject parse(byte[] source) throws IOException {
        if (source.length == 0 || source.length > MAX_BYTES) throw invalid("project size");
        JsonNode root;
        try (var parser = JSON.createParser(source)) {
            root = JSON.readTree(parser);
            if (parser.nextToken() != null) throw invalid("trailing JSON");
        }
        fields(root, Set.of("schemaVersion", "sdkVersion", "id", "name", "publisher", "version", "tools"));
        if (!sdkVersion().equals(text(root, "sdkVersion", 128))) throw invalid("sdkVersion: install the pinned SDK distribution");
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != 1) throw invalid("schemaVersion");
        id(text(root, "id", 128), "[a-zA-Z][a-zA-Z0-9._-]*", "package id");
        text(root, "name", 100);
        id(text(root, "publisher", 128), "[a-zA-Z0-9][a-zA-Z0-9._-]*", "publisher");
        id(text(root, "version", 128), "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[a-zA-Z0-9.-]+)?(\\+[a-zA-Z0-9.-]+)?", "version");
        var declarations = root.path("tools");
        if (!declarations.isArray() || declarations.isEmpty() || declarations.size() > 32) throw invalid("tools");
        var tools = new ArrayList<Tool>();
        var names = new HashSet<String>();
        for (JsonNode t : declarations) {
            fields(t, Set.of("name", "kind", "title", "description", "inputs", "output", "events"));
            String name = text(t, "name", 64);
            id(name, "[a-zA-Z][a-zA-Z0-9._-]*", "tool name");
            if (!names.add(name)) throw invalid("duplicate tool");
            String kind = text(t, "kind", 6);
            if (!Set.of("SERVER", "CLIENT").contains(kind)) throw invalid("tool kind");
            var inputs = new ArrayList<Input>();
            var inputNames = new HashSet<String>();
            var properties = t.path("inputs");
            if (!properties.isObject() || properties.size() > 16) throw invalid("inputs");
            for (var iterator = properties.properties().iterator(); iterator.hasNext();) {
                var property = iterator.next();
                String key = property.getKey();
                id(key, "[a-zA-Z][a-zA-Z0-9_]{0,63}", "input name");
                inputNames.add(key);
                JsonNode spec = property.getValue();
                fields(spec, Set.of("type", "description", "default", "maxLength"));
                if (!"STRING".equals(text(spec, "type", 16))) throw invalid("unsupported input type");
                int max = spec.path("maxLength").asInt(-1);
                if (!spec.path("maxLength").isIntegralNumber() || !spec.path("maxLength").canConvertToInt() || max < 1 || max > 4096) throw invalid("maxLength");
                String value = null;
                if (spec.has("default")) {
                    if (!spec.get("default").isTextual()) throw invalid("default type");
                    value = spec.get("default").textValue();
                    if (value.isEmpty() || value.length() > max || value.indexOf('\0') >= 0) throw invalid("default bounds");
                }
                inputs.add(new Input(key, text(spec, "description", 1024), value, max));
            }
            var output = t.path("output");
            if (!output.isObject() || output.isEmpty() || output.size() > 16) throw invalid("output");
            var outputs = new ArrayList<Output>();
            for (var iterator = output.properties().iterator(); iterator.hasNext();) {
                var item = iterator.next();
                id(item.getKey(), "[a-zA-Z][a-zA-Z0-9_]{0,63}", "output name");
                fields(item.getValue(), Set.of("concat"));
                JsonNode concat = item.getValue().path("concat");
                if (!concat.isArray() || concat.isEmpty() || concat.size() > 32) throw invalid("concat");
                var parts = new ArrayList<Part>();
                int maximum = 0;
                for (JsonNode expression : concat) {
                    fields(expression, Set.of("literal", "input"));
                    if (expression.size() != 1) throw invalid("expression");
                    if (expression.has("literal")) {
                        String literal = textAllowEmpty(expression, "literal", 4096);
                        maximum += literal.length(); parts.add(new Part(literal, null));
                    } else {
                        String input = text(expression, "input", 64);
                        if (!inputNames.contains(input)) throw invalid("undeclared input");
                        maximum += inputs.stream().filter(p -> p.name().equals(input)).findFirst().orElseThrow().maxLength();
                        parts.add(new Part(null, input));
                    }
                }
                if (maximum > 65_536) throw invalid("output bound");
                outputs.add(new Output(item.getKey(), List.copyOf(parts)));
            }
            var events = new ArrayList<Event>();
            double progress = 0;
            if (t.has("events")) {
                if (!t.get("events").isArray() || t.get("events").size() > 32) throw invalid("events");
                for (JsonNode event : t.get("events")) {
                    fields(event, Set.of("level", "message", "fraction"));
                    String message = text(event, "message", 512);
                    if (event.has("fraction")) {
                        double value = event.get("fraction").asDouble(-1);
                        if (event.has("level") || !event.get("fraction").isNumber() || !Double.isFinite(value) || value < progress || value > 1) throw invalid("progress");
                        progress = value; events.add(new Event(null, message, value));
                    } else {
                        String level = text(event, "level", 5);
                        if (!Set.of("debug", "info", "warn", "error").contains(level)) throw invalid("log level");
                        events.add(new Event(level, message, null));
                    }
                }
            }
            tools.add(new Tool(name, kind, text(t, "title", 100), text(t, "description", 2048),
                    List.copyOf(inputs), List.copyOf(outputs), List.copyOf(events)));
        }
        return new JsonProject(root, tools);
    }

    static void fields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) throw invalid("expected object");
        object.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid("unknown field: " + key); });
    }
    private static String textAllowEmpty(JsonNode object, String key, int max) {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual() || value.textValue().length() > max || value.textValue().indexOf('\0') >= 0) throw invalid(key);
        return value.textValue();
    }
    private static String text(JsonNode object, String key, int max) {
        String value = textAllowEmpty(object, key, max);
        if (value.isEmpty()) throw invalid(key);
        return value;
    }
    private static void id(String value, String pattern, String what) { if (!value.matches(pattern)) throw invalid(what); }
    static IllegalArgumentException invalid(String what) { return new IllegalArgumentException("Invalid JSON SDK " + what); }
    public byte[] source() throws IOException { return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8); }
}
