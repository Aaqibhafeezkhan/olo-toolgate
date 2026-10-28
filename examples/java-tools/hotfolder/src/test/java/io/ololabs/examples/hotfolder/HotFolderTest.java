// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.hotfolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotFolderTest {
    @Test
    void examplesHoldInAnEmptyFolderAsTheDeviceSelfTestRunsThem() {
        try (ToolHarness harness = ToolHarness.builder().deviceFiles(new InMemoryDeviceFiles()).build()) {
            harness.assertExamples();
        }
    }

    @Test
    void readsWritesSearchesAndCopiesThroughTheDeviceFilesArea() {
        var files = new InMemoryDeviceFiles().file("notes.txt", "alpha\ninvoice 12\nbeta\ninvoice 13");
        try (ToolHarness harness = ToolHarness.builder().deviceFiles(files).build()) {
            assertEquals(List.of(2, 4), lines(harness));
            var read = harness.call("read_text", Map.of("path", "notes.txt")).output();
            assertTrue(read.path("exists").asBoolean());
            assertEquals("alpha\ninvoice 12\nbeta\ninvoice 13", read.path("text").asText());
            assertEquals(64, harness.call("hash", Map.of("path", "notes.txt")).output().path("sha256").asText().length());

            assertTrue(harness.call("copy", Map.of("path", "notes.txt", "destination", "copy.txt")).output().path("copied").asBoolean());
            assertEquals(files.content("notes.txt"), files.content("copy.txt"));
            assertTrue(harness.call("copy", Map.of("path", "notes.txt", "destination", "copy.txt")).isError(), "never overwrites a destination");

            assertTrue(harness.call("append_text", Map.of("path", "copy.txt", "text", "\nend")).output().path("written").asBoolean());
            assertTrue(files.content("copy.txt").endsWith("\nend"));
            assertTrue(harness.call("move", Map.of("path", "copy.txt", "destination", "moved.txt")).output().path("moved").asBoolean());
            assertEquals(List.of("moved.txt", "notes.txt"), files.list());

            // The device's naming rules: nothing outside the folder exists, and writes outside it or of other file types fail.
            assertFalse(harness.call("read_text", Map.of("path", "../etc/passwd")).output().path("exists").asBoolean());
            assertTrue(harness.call("write_text", Map.of("path", "../escape.txt", "text", "x")).isError());
            assertTrue(harness.call("write_text", Map.of("path", "run.sh", "text", "x")).isError());
            assertFalse(harness.call("read_text", Map.of("path", "absent.txt")).output().path("exists").asBoolean());
        }
    }

    private static List<Integer> lines(ToolHarness harness) {
        var lines = new java.util.ArrayList<Integer>();
        harness.call("search_text", Map.of("path", "notes.txt", "query", "invoice")).output().path("lines").forEach(n -> lines.add(n.asInt()));
        return lines;
    }
}
