// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

/** The MCP proxy definition digest (tool-sdk/spec/mcp-proxy): {@code sha256:} over the RFC 8785 form, without NFC, of
 *  an upstream tool's six definition members exactly as returned. The Tool Host computes the same value. */
public final class McpDefinitions {
    private McpDefinitions() {}

    /** The members a definition digest covers; every other member is dropped. */
    public static final List<String> MEMBERS = List.of("name", "title", "description", "inputSchema", "outputSchema", "annotations");

    public static String digest(JsonNode tool) { return Canonical.sha256(canonical(tool)); }

    static byte[] canonical(JsonNode tool) {
        if (tool == null || !tool.isObject()) throw new IllegalArgumentException("an MCP tool definition is a JSON object");
        var kept = JsonNodeFactory.instance.objectNode();
        for (var member : MEMBERS) if (tool.has(member)) kept.set(member, tool.get(member));
        return Canonical.jcs(kept);
    }
}
