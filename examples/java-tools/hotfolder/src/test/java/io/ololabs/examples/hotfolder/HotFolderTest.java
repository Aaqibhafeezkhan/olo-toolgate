// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.hotfolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.TriggerHarness;
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

    @Test
    void everyTriggerExamplePasses() {
        var results = TriggerHarness.runExamples(Thread.currentThread().getContextClassLoader());
        assertEquals(2, results.size());
        results.forEach(r -> assertTrue(r.passed(), r::toString));
    }

    /** The hot-folder flow: a dropped file is reported once; after the agent moves it to processed/ it is pruned. */
    @Test
    void aDroppedFileIsReportedOnceAndPrunedOnceMovedToProcessed() {
        var files = new InMemoryDeviceFiles();
        files.mkdir("inbox");
        files.mkdir("processed");
        files.mkdir("inbox/sub");
        try (TriggerHarness trigger = ToolHarness.trigger("file-dropped").config(Map.of("folder", "inbox")).deviceFiles(files).start();
             ToolHarness tools = ToolHarness.builder().deviceFiles(files).build()) {
            assertTrue(trigger.firstPoll().events().isEmpty());

            files.file("inbox/order-1042.txt", "2 x widget").file("inbox/sub/nested.txt", "not watched");
            TriggerHarness.Poll dropped = trigger.poll();
            assertEquals(List.of("file:inbox/order-1042.txt:10"), dropped.events().stream().map(TriggerHarness.Emitted::id).toList());
            var event = dropped.events().get(0).data();
            assertEquals("inbox/order-1042.txt", event.path("path").asText());
            assertTrue(trigger.poll().events().isEmpty(), "reported once");

            // What the agent does with the event: read the file and move it to processed/.
            assertEquals("2 x widget", tools.call("read_text", Map.of("path", event.path("path").asText())).output().path("text").asText());
            assertTrue(tools.call("move", Map.of("path", "inbox/order-1042.txt", "destination", "processed/order-1042.txt")).output().path("moved").asBoolean());
            TriggerHarness.Poll after = trigger.poll();
            assertTrue(after.events().isEmpty(), "a processed file is never reported");
            assertEquals(Checkpoint.of("reported", Map.of()), after.checkpoint().orElseThrow());
            assertTrue(trigger.health().isEmpty(), "healthy throughout");
        }
    }

    private static List<Integer> lines(ToolHarness harness) {
        var lines = new java.util.ArrayList<Integer>();
        harness.call("search_text", Map.of("path", "notes.txt", "query", "invoice")).output().path("lines").forEach(n -> lines.add(n.asInt()));
        return lines;
    }
}
