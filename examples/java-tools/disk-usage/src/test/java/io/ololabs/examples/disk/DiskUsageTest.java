// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.disk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskUsageTest {
    @TempDir
    Path root;

    @Test
    void listsVolumesFullestFirst() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode volumes = tools.call("space", Map.of("all", true)).output().path("volumes");
            assertTrue(volumes.size() >= 1, volumes.toString());
            for (int i = 1; i < volumes.size(); i++) {
                assertTrue(volumes.get(i - 1).path("percentUsed").asDouble() >= volumes.get(i).path("percentUsed").asDouble());
            }
        }
    }

    @Test
    void findsTheLargestFilesAndFolders() throws IOException {
        Files.createDirectories(root.resolve("cache/a"));
        Files.createDirectories(root.resolve("data"));
        Files.write(root.resolve("cache/a/big.bin"), new byte[5000]);
        Files.write(root.resolve("cache/small.bin"), new byte[100]);
        Files.write(root.resolve("data/mid.bin"), new byte[2000]);
        Files.write(root.resolve("top.txt"), new byte[10]);
        try (ToolHarness tools = ToolHarness.builder().global("AllowedRoots", root.toString()).build()) {
            ToolResult result = tools.call("largest", Map.of("path", root.toString(), "limit", 2));
            JsonNode out = result.output();
            assertEquals("cache/a/big.bin", out.path("files").get(0).path("path").asText(), result.toString());
            assertEquals("data/mid.bin", out.path("files").get(1).path("path").asText());
            assertEquals(2, out.path("files").size());
            assertEquals("cache/", out.path("directories").get(0).path("path").asText());
            assertEquals(5100, out.path("directories").get(0).path("sizeBytes").asLong());
            assertEquals(7110, out.path("totalBytes").asLong());
            assertEquals(4, out.path("filesVisited").asLong());
        }
    }

    @Test
    void staysInsideTheAllowedRoots(@TempDir Path elsewhere) {
        try (ToolHarness tools = ToolHarness.builder().global("AllowedRoots", root.toString()).build()) {
            assertEquals("INVALID_ARGUMENT", tools.call("largest", Map.of("path", elsewhere.toString())).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("largest", Map.of("path", root + "/../")).errorCode());
        }
    }

    @Test
    void stopsAtTheVisitCap() throws IOException {
        for (int i = 0; i < 150; i++) Files.write(root.resolve("f" + i), new byte[i]);
        try (ToolHarness tools = ToolHarness.builder().global("AllowedRoots", root.toString()).global("MaxFilesVisited", 100).build()) {
            JsonNode out = tools.call("largest", Map.of("path", root.toString())).output();
            assertTrue(out.path("truncated").asBoolean(), out.toString());
            assertEquals(100, out.path("filesVisited").asLong());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
