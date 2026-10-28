// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessesTest {
    @Test
    void measuresCpu() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("cpu", Map.of("sampleMs", 100)).output();
            assertTrue(out.path("systemPercent").asDouble() >= 0 && out.path("systemPercent").asDouble() <= 100, out.toString());
            assertTrue(out.path("processors").asInt() >= 1);
        }
    }

    @Test
    void listsTheBusiestProcessesWithoutArguments() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("top", Map.of("limit", 2, "sampleMs", 100)).output();
            assertEquals(2, out.path("processes").size(), out.toString());
            assertTrue(out.path("processCount").asInt() >= 2);
            assertTrue(out.path("processes").get(0).path("cpuPercent").asDouble()
                    >= out.path("processes").get(1).path("cpuPercent").asDouble());
            assertTrue(!out.path("processes").get(0).has("arguments"));
        }
    }

    @Test
    void findsThisJvm() {
        String self = Processes.executable(ProcessHandle.current());
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("find", Map.of("name", self.toUpperCase())).output();
            assertTrue(out.path("running").asBoolean(), out.toString());
            boolean mine = false;
            for (JsonNode p : out.path("processes")) mine |= p.path("pid").asLong() == ProcessHandle.current().pid();
            assertTrue(mine, out.toString());
        }
    }

    @Test
    void namesCompareWithoutCaseOrExe() {
        assertEquals("sqlservr", Processes.normalise(" SQLSERVR.EXE "));
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
