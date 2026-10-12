// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogInsightsTest {
    @TempDir Path logs;
    private static final Map<String, Object> WINDOW = Map.of("from", "2026-01-01T00:00:00Z", "to", "2026-01-02T00:00:00Z");

    @BeforeEach
    void writeLogs() throws IOException {
        Files.writeString(logs.resolve("app.log"), """
                2026-01-01T09:00:00Z INFO started
                2026-01-01T09:00:00.100Z ERROR payment 123 failed
                java.lang.IllegalStateException: refused
                    at app.Payment.run(Payment.java:12)
                2026-01-01T09:30:00Z WARNING queue 456
                2026-01-01T10:00:00Z ERROR payment 789 failed
                java.lang.IllegalStateException: refused again
                2026-01-02T09:00:00Z FATAL outside the requested window
                """);
        Files.writeString(logs.resolve("hidden.json"), "2026-01-01T09:00:00Z FATAL not an allowed file\n");
    }

    private ToolHarness harness() { return ToolHarness.builder().global("LogDir", logs.toString()).build(); }
    private static Map<String, Object> arguments(String key, Object value) {
        Map<String, Object> arguments = new HashMap<>(WINDOW);
        arguments.put(key, value);
        return arguments;
    }

    @Test
    void searchesGeneratedToolsWithWindowLevelTextAndStackTrace() {
        try (ToolHarness tools = harness()) {
            Map<String, Object> arguments = arguments("minLevel", "ERROR");
            arguments.put("contains", "ILLEGALSTATEEXCEPTION");
            ToolResult result = tools.call("search", arguments);
            JsonNode out = result.output();
            assertEquals(2, out.path("entries").size(), result.toString());
            assertEquals("2026-01-01T10:00:00Z", out.path("entries").get(0).path("time").asText());
            assertEquals(2, out.path("entries").get(1).path("line").asInt());
            assertTrue(out.path("entries").get(1).path("stackTrace").asText().contains("Payment.java:12"));
            assertFalse(out.path("truncated").asBoolean());
            assertEquals(1, tools.call("search", arguments("limit", 1)).output().path("entries").size());
            JsonNode times = tools.call("search", WINDOW).output().path("entries");
            assertEquals("2026-01-01T09:00:00.100Z", times.get(2).path("time").asText());
            assertEquals("2026-01-01T09:00:00Z", times.get(3).path("time").asText());
        }
    }

    @Test
    void countsLevelsPerFileAndBucketAndGroupsErrorCauses() {
        try (ToolHarness tools = harness()) {
            JsonNode levels = tools.call("levels", WINDOW).output();
            assertEquals(2, levels.path("counts").get(4).path("count").asInt(), levels.toString());
            assertEquals(1, levels.path("counts").get(3).path("count").asInt());
            assertEquals(1, levels.path("files").size());
            assertEquals(2, levels.path("buckets").size());
            JsonNode errors = tools.call("errors", WINDOW).output().path("groups");
            assertEquals(1, errors.size(), errors.toString());
            assertEquals("java.lang.IllegalStateException", errors.get(0).path("cause").asText());
            assertEquals(2, errors.get(0).path("count").asInt());
            assertEquals("2026-01-01T09:00:00.100Z", errors.get(0).path("firstSeen").asText());
            assertEquals("2026-01-01T10:00:00Z", errors.get(0).path("latest").path("time").asText());
        }
    }

    @Test
    void interpretsLogbackTimestampsInTheConfiguredZone() throws IOException {
        Files.writeString(logs.resolve("app.log"), "[2026-01-01 10:00:00,123] SEVERE local time\n");
        try (ToolHarness tools = ToolHarness.builder().global("LogDir", logs.toString()).global("LogTimeZone", "Europe/Berlin").build()) {
            JsonNode entries = tools.call("search", WINDOW).output().path("entries");
            assertEquals(1, entries.size(), entries.toString());
            assertEquals("2026-01-01T09:00:00.123Z", entries.get(0).path("time").asText());
            assertEquals("ERROR", entries.get(0).path("level").asText());
        }
    }

    @Test
    void tailsAllowedFilesAndRejectsTraversalAndOtherExtensions() {
        try (ToolHarness tools = harness()) {
            JsonNode out = tools.call("tail", Map.of("file", "app.log", "lines", 2)).output();
            assertEquals(2, out.path("lines").size(), out.toString());
            assertTrue(out.path("lines").get(1).asText().contains("outside the requested window"));
            assertFalse(out.path("truncated").asBoolean());
            for (String file : new String[]{"../app.log", logs.resolve("app.log").toString(), "hidden.json"}) {
                assertEquals("INVALID_ARGUMENT", tools.call("tail", Map.of("file", file)).errorCode(), file);
            }
        }
    }

    @Test
    void doesNotFollowFileOrDirectoryLinks(@TempDir Path elsewhere) throws IOException {
        // Windows cannot create symlinks without Developer Mode or elevated privileges; Linux exercises this case.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "symlinks require Windows Developer Mode or elevation");
        Files.writeString(elsewhere.resolve("outside.log"), "2026-01-01T09:00:00Z FATAL outside\n");
        Files.createSymbolicLink(logs.resolve("link.log"), elsewhere.resolve("outside.log"));
        Files.createSymbolicLink(logs.resolve("linked"), elsewhere);
        try (ToolHarness tools = harness()) {
            assertEquals(4, tools.call("search", WINDOW).output().path("entries").size());
            assertEquals("INVALID_ARGUMENT", tools.call("tail", Map.of("file", "link.log")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("tail", Map.of("file", "linked/outside.log")).errorCode());
        }
    }

    @Test
    void reportsByteAndTextBoundsWithoutRetainingAnUnboundedLine() throws IOException {
        Files.writeString(logs.resolve("app.log"), "x".repeat(2 * 1024 * 1024));
        try (ToolHarness tools = ToolHarness.builder().global("LogDir", logs.toString()).global("MaxScanMegabytes", 1).build()) {
            JsonNode out = tools.call("tail", Map.of("file", "app.log")).output();
            assertTrue(out.path("truncated").asBoolean(), out.toString());
            assertEquals(2000, out.path("lines").get(0).asText().length());
        }
    }

    @Test
    void everyDeclaredExamplePassesWithoutLogFiles() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals(4, tools.runExamples().size());
            tools.assertExamples();
        }
    }
}
