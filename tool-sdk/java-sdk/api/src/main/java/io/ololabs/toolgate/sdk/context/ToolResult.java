// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.util.List;
import java.util.Map;

/** An explicit result: MCP content plus optional structured content. */
public final class ToolResult {
    private final List<Map<String, Object>> content;
    private final Map<String, Object> structuredContent;

    private ToolResult(List<Map<String, Object>> content, Map<String, Object> structuredContent) {
        this.content = List.copyOf(content);
        this.structuredContent = structuredContent == null ? null : Map.copyOf(structuredContent);
    }

    public static ToolResult of(List<Map<String, Object>> content, Map<String, Object> structuredContent) {
        return new ToolResult(content, structuredContent);
    }

    public static ToolResult text(String text) {
        return new ToolResult(List.of(Map.of("type", "text", "text", text)), null);
    }

    public List<Map<String, Object>> content() { return content; }
    public Map<String, Object> structuredContent() { return structuredContent; }
}
