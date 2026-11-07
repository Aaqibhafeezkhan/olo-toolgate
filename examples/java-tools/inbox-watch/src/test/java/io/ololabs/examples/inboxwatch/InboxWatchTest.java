// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.inboxwatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ololabs.toolgate.contracts.v2.TriggerEmitStatus;
import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.TriggerHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The inbox-watch trigger through the real trigger runtime and its stdio frames, against the harness's supervisor. */
class InboxWatchTest {
    private static InMemoryDeviceFiles area() {
        InMemoryDeviceFiles files = new InMemoryDeviceFiles(List.of("pdf", "txt"), 65536);
        files.mkdir("inbox");
        files.mkdir("archive");
        return files;
    }

    @Test
    void everyTriggerExamplePasses() {
        List<TriggerHarness.ExampleResult> results = TriggerHarness.runExamples(Thread.currentThread().getContextClassLoader());
        assertEquals(2, results.size());
        results.forEach(r -> assertTrue(r.passed(), r::toString));
    }

    @Test
    void reportsEachNewVersionOnceAndPrunesRemovedFiles() {
        InMemoryDeviceFiles files = area();
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(files).start()) {
            TriggerHarness.Poll empty = trigger.firstPoll();
            assertTrue(empty.events().isEmpty());
            assertEquals(Checkpoint.of("reported", Map.of()), empty.checkpoint().orElseThrow());

            files.file("inbox/a.pdf", "%PDF-1").file("inbox/b.pdf", "%PDF-12").file("inbox/readme.txt", "skip");
            TriggerHarness.Poll first = trigger.poll();
            assertEquals(List.of("file:inbox/a.pdf:6", "file:inbox/b.pdf:7"), first.events().stream().map(TriggerHarness.Emitted::id).toList());
            assertEquals("{\"name\":\"a.pdf\",\"path\":\"inbox/a.pdf\",\"size\":6}", first.events().get(0).data().toString());
            assertEquals("inbox/a.pdf", first.events().get(0).subject());
            assertEquals(Checkpoint.of("reported", Map.of("inbox/a.pdf", 6L, "inbox/b.pdf", 7L)), first.checkpoint().orElseThrow());

            assertTrue(trigger.poll().events().isEmpty(), "nothing new: nothing is reported again");

            files.appendText("inbox/a.pdf", " more");          // a new version
            files.rename("inbox/b.pdf", "archive/b.pdf");     // gone from the folder
            TriggerHarness.Poll changed = trigger.poll();
            assertEquals(List.of("file:inbox/a.pdf:11"), changed.events().stream().map(TriggerHarness.Emitted::id).toList());
            assertEquals(Checkpoint.of("reported", Map.of("inbox/a.pdf", 11L)), changed.checkpoint().orElseThrow());

            // emitSeq runs on across checks, and each checkpoint sits after the last emit before it.
            assertEquals(List.of(0L, 2L, 2L, 3L), trigger.checkpointPositions());
            assertEquals(List.of(1L, 2L, 3L), trigger.emitted().stream().map(TriggerHarness.Emitted::emitSeq).toList());
            assertTrue(trigger.health().isEmpty(), "healthy throughout");
        }
    }

    @Test
    void aFailedListingCommitsNothingAndTheNextCheckRecovers() {
        InMemoryDeviceFiles files = area().file("inbox/a.pdf", "%PDF-1");
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(files).start()) {
            assertEquals(1, trigger.firstPoll().events().size());
            files.file("inbox/b.pdf", "%PDF-12");
            trigger.failNextFilesCall("the files area is unavailable");
            TriggerHarness.Poll failed = trigger.poll();
            assertTrue(failed.events().isEmpty());
            assertTrue(failed.checkpoint().isEmpty(), "a failed listing never prunes or advances the checkpoint");
            assertEquals("DEGRADED", trigger.health().get(0).status());
            assertTrue(trigger.health().get(0).reason().startsWith("CheckFailed"), trigger.health().get(0)::reason);

            TriggerHarness.Poll recovered = trigger.poll();
            assertEquals(List.of("file:inbox/b.pdf:7"), recovered.events().stream().map(TriggerHarness.Emitted::id).toList());
            assertEquals("HEALTHY", trigger.health().get(1).status());
        }
    }

    @Test
    void aRetryableRejectionIsResentAndTheCheckpointWaitsForIt() {
        InMemoryDeviceFiles files = area().file("inbox/a.pdf", "%PDF-1").file("inbox/b.pdf", "%PDF-12");
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(files).start()) {
            trigger.firstPoll();
            files.file("inbox/c.pdf", "%PDF-123");
            trigger.rejectNext(TriggerEmitStatus.RETRYABLE_REJECTION, TriggerEmitStatus.RETRYABLE_REJECTION);
            TriggerHarness.Poll retried = trigger.poll();
            // The same emit, same id and emitSeq, three times: two rejections, then durable.
            assertEquals(List.of("file:inbox/c.pdf:8", "file:inbox/c.pdf:8", "file:inbox/c.pdf:8"), retried.events().stream().map(TriggerHarness.Emitted::id).toList());
            assertEquals(1, retried.events().stream().map(TriggerHarness.Emitted::emitSeq).distinct().count());
            assertTrue(retried.checkpoint().orElseThrow().getMap("reported").containsKey("inbox/c.pdf"));
        }
    }

    @Test
    void aTerminalRejectionHaltsWithoutCheckpointing() {
        InMemoryDeviceFiles files = area().file("inbox/a.pdf", "%PDF-1");
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(files).start()) {
            trigger.firstPoll();
            files.file("inbox/b.pdf", "%PDF-12");
            trigger.rejectNext(TriggerEmitStatus.TERMINAL_REJECTION);
            TriggerHarness.Poll halted = trigger.poll();
            assertTrue(halted.checkpoint().isEmpty(), "HALT: nothing after a terminal rejection is checkpointed");
            assertEquals("UNHEALTHY", trigger.health().get(0).status());
            assertEquals(1, trigger.checkpoints().size());
        }
    }

    @Test
    void aRestartFromTheCommittedCheckpointReportsNothingTwice() {
        InMemoryDeviceFiles files = area().file("inbox/a.pdf", "%PDF-1");
        Checkpoint committed;
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(files).start()) {
            committed = trigger.firstPoll().checkpoint().orElseThrow();
        }
        files.file("inbox/b.pdf", "%PDF-12");
        try (TriggerHarness restarted = ToolHarness.trigger("examples.inbox-watch/new-file").config(Map.of("folder", "inbox"))
                .checkpoint(committed).deviceFiles(files).start()) {
            assertEquals(List.of("file:inbox/b.pdf:7"), restarted.firstPoll().events().stream().map(TriggerHarness.Emitted::id).toList());
        }
    }

    @Test
    void stopEndsTheProcessCleanly() {
        try (TriggerHarness trigger = ToolHarness.trigger("new-file").config(Map.of("folder", "inbox")).deviceFiles(area()).start()) {
            trigger.firstPoll();
            assertTrue(trigger.ready());
            assertEquals(0, trigger.stop());
            assertEquals("EXIT", trigger.frames().get(trigger.frames().size() - 1).path("kind").asText());
        }
    }
}
