// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/**
 * Validates an assignment config against the signed {@code configSchema} of a trigger descriptor (plan 5.9, 9.1). The
 * schema comes from a tenant's package, so it is refused unless every {@code $ref} is local and it names no {@code $id}
 * or {@code $schema} of its own: validation never resolves anything outside the schema, so it cannot reach the network.
 */
final class TriggerConfigs {
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    static final int MAX_DEPTH = 32;

    private TriggerConfigs() {}

    /** Null when the config matches, else the first problem. */
    static String validate(JsonNode schema, JsonNode config) {
        if (!schema.isObject()) return "the descriptor has no configSchema";
        String closed = closed(schema, 0);
        if (closed != null) return closed;
        try {
            var messages = FACTORY.getSchema(schema).validate(config);
            return messages.isEmpty() ? null : bounded(messages.iterator().next().getMessage());
        } catch (RuntimeException e) {
            return "the configSchema cannot be compiled";
        }
    }

    /** Only local references, no identifiers, bounded depth. */
    private static String closed(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) return "the configSchema is nested too deeply";
        if (node.isObject()) {
            for (var field : node.properties()) {
                String key = field.getKey();
                if ((key.equals("$id") || key.equals("$schema") || key.equals("$dynamicRef") || key.equals("$recursiveRef"))) {
                    return "the configSchema may not declare " + key;
                }
                if (key.equals("$ref") && !(field.getValue().isTextual() && field.getValue().asText().startsWith("#"))) {
                    return "the configSchema may only use local references";
                }
                String inner = closed(field.getValue(), depth + 1);
                if (inner != null) return inner;
            }
        } else if (node.isArray()) {
            for (var item : node) {
                String inner = closed(item, depth + 1);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static String bounded(String message) {
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
