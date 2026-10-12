// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemHealthTest {
    @Test
    void reportsEveryCheckWorstFirst(@TempDir Path dir) {
        try (ToolHarness tools = ToolHarness.builder().global("DiskPaths", dir.toString()).build()) {
            ToolResult result = tools.call("system", Map.of());
            JsonNode out = result.output();
            List<String> names = new ArrayList<>();
            out.path("checks").forEach(c -> names.add(c.path("name").asText()));
            assertTrue(names.contains("cpu") && names.contains("memory"), result.toString());
            assertEquals(1, names.stream().filter(n -> n.startsWith("disk:")).count(), names.toString());
            assertEquals(out.path("checks").get(0).path("status").asText(), out.path("status").asText());
            assertTrue(out.path("processors").asInt() >= 1);
        }
    }

    @Test
    void thresholdsComeFromTheDeployment() {
        try (ToolHarness tools = ToolHarness.builder().global("CriticalPercent", 1).global("MemoryWarnPercent", 1).build()) {
            JsonNode out = tools.call("system", Map.of()).output();
            assertEquals("CRITICAL", out.path("status").asText(), out.toString());
        }
    }

    @Test
    void rating() {
        assertEquals("OK", SystemHealth.rate(10, 80, 95));
        assertEquals("WARN", SystemHealth.rate(80, 80, 95));
        assertEquals("CRITICAL", SystemHealth.rate(99, 80, 95));
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
