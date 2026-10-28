// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCleanupTest {
    @TempDir
    Path root;

    @BeforeEach
    void files() throws IOException {
        old(root.resolve("app-1.log"), 40, 100);
        old(root.resolve("app-2.log"), 20, 200);
        old(root.resolve("keep.csv"), 40, 300);
        old(root.resolve("fresh.log"), 1, 400);
        Files.createDirectories(root.resolve("archive"));
        old(root.resolve("archive/app-0.log"), 90, 500);
    }

    static void old(Path p, int days, int size) throws IOException {
        Files.write(p, new byte[size]);
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofDays(days))));
    }

    private ToolHarness harness(int maxDeletes) {
        return ToolHarness.builder().global("CleanupRoots", root.toString()).global("MaxDeletesPerCall", maxDeletes).build();
    }

    @Test
    void aDryRunOnlyLists() {
        try (ToolHarness tools = harness(500)) {
            ToolResult result = tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 10, "pattern", "*.log", "recursive", true));
            JsonNode out = result.output();
            assertTrue(out.path("dryRun").asBoolean(), result.toString());
            assertEquals(3, out.path("matched").asInt());
            assertEquals(800, out.path("matchedBytes").asLong());
            assertEquals("archive/app-0.log", out.path("files").get(0).path("path").asText(), "oldest first");
            assertEquals(0, out.path("deleted").asInt());
            assertTrue(Files.exists(root.resolve("app-1.log")));
        }
    }

    @Test
    void deletesOnlyWhenAskedAndOnlyWhatMatches() {
        try (ToolHarness tools = harness(500)) {
            JsonNode out = tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 30, "pattern", "*.log",
                    "dryRun", false)).output();
            assertEquals(1, out.path("deleted").asInt(), out.toString());
            assertEquals(100, out.path("freedBytes").asLong());
            assertFalse(Files.exists(root.resolve("app-1.log")));
            assertTrue(Files.exists(root.resolve("app-2.log")) && Files.exists(root.resolve("keep.csv"))
                    && Files.exists(root.resolve("archive/app-0.log")), "younger, other pattern and sub-directory files stay");
        }
    }

    @Test
    void capsDeletesPerCall() {
        try (ToolHarness tools = harness(1)) {
            JsonNode out = tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 10, "recursive", true,
                    "dryRun", false)).output();
            assertEquals(1, out.path("deleted").asInt(), out.toString());
            assertTrue(out.path("limitReached").asBoolean());
        }
    }

    @Test
    void guardRails(@TempDir Path elsewhere) {
        try (ToolHarness tools = harness(500)) {
            assertEquals("INVALID_ARGUMENT", tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 2, "dryRun", false)).errorCode(),
                    "younger than MinAgeDays");
            assertEquals("INVALID_ARGUMENT", tools.call("old-files", Map.of("path", elsewhere.toString(), "olderThanDays", 30)).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 30, "pattern", "../*")).errorCode());
        }
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("CONFIGURATION", tools.call("old-files", Map.of("path", root.toString(), "olderThanDays", 30)).errorCode(),
                    "disabled until CleanupRoots is set");
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
