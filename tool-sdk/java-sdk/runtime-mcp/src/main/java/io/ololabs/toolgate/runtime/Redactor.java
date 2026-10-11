// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Replaces declared secret values and sensitive inputs or globals with [REDACTED] (errors.md §4). */
final class Redactor {
    static final String MARK = "[REDACTED]";
    private final Set<String> values = ConcurrentHashMap.newKeySet();

    /** A copy that per-invocation values (sensitive inputs) can be added to. */
    Redactor copy() {
        Redactor out = new Redactor();
        out.values.addAll(values);
        return out;
    }

    void add(JsonNode value) {
        if (value == null || value.isNull()) return;
        if (value.isContainerNode()) {
            for (JsonNode v : value) add(v);
            return;
        }
        String s = value.asText();
        if (!s.isEmpty()) values.add(s);
    }

    String apply(String text) {
        if (text == null || values.isEmpty()) return text;
        List<String> longestFirst = new ArrayList<>(values);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String v : longestFirst) out = out.replace(v, MARK);
        return out;
    }

    JsonNode apply(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) return JsonNodeFactory.instance.textNode(apply(node.asText()));
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                out.set(apply(f.getKey()), apply(f.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode v : node) out.add(apply(v));
            return out;
        }
        if (node.isValueNode() && values.contains(node.asText())) return JsonNodeFactory.instance.textNode(MARK);
        return node;
    }

    /** Truncates to at most {@code max} UTF-8 bytes without splitting a character. */
    static String cap(String text, int max) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) return text;
        int end = max;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
