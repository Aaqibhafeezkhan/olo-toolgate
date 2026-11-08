// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ololabs.toolgate.contracts.v2.TriggerEmitStatus;
import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TriggerHarness drives the real trigger runtime over its frames (plan 5.6, test support). */
class TriggerHarnessTest {
    private static InMemoryDeviceFiles drop() {
        InMemoryDeviceFiles files = new InMemoryDeviceFiles();
        files.mkdir("drop");
        return files;
    }

    @Test
    void drivesPollsAndRecordsEventsAndCheckpoints() {
        InMemoryDeviceFiles files = drop().file("drop/1.json", "{\"id\":1}");
        try (TriggerHarness trigger = ToolHarness.trigger("order-dropped").config("{\"folder\":\"drop\"}").deviceFiles(files).start()) {
            TriggerHarness.Poll first = trigger.firstPoll();
            assertEquals(List.of("drop/1.json"), first.events().stream().map(TriggerHarness.Emitted::id).toList());
            assertEquals("{\"id\":1}", first.events().get(0).data().path("text").asText());
            assertEquals(Checkpoint.of("last", "drop/1.json"), first.checkpoint().orElseThrow());
            files.file("drop/2.json", "{}").file("drop/3.json", "{}");
            List<TriggerHarness.Poll> next = trigger.poll(2);
            assertEquals(2, next.get(0).events().size());
            assertTrue(next.get(1).events().isEmpty());
            assertTrue(next.get(1).checkpoint().isPresent(), "an unchanged checkpoint is still committed after each check");
            assertEquals(List.of(1L, 3L, 3L), trigger.checkpointPositions());
            assertEquals(0, trigger.stop());
            assertEquals("READY", trigger.frames().get(0).path("kind").asText());
        }
    }

    @Test
    void scriptedRejectionsAndFileFaultsReachTheTrigger() {
        InMemoryDeviceFiles files = drop().file("drop/1.json", "{}");
        try (TriggerHarness trigger = ToolHarness.trigger("acme.orders/order-dropped").config(Map.of("folder", "drop")).deviceFiles(files).start()) {
            trigger.firstPoll();
            files.file("drop/2.json", "{}");
            trigger.rejectNext(TriggerEmitStatus.RETRYABLE_REJECTION);
            TriggerHarness.Poll retried = trigger.poll();
            assertEquals(2, retried.events().size(), "the rejected emit is sent again");
            assertEquals(retried.events().get(0), retried.events().get(1));
            assertEquals(Checkpoint.of("last", "drop/2.json"), retried.checkpoint().orElseThrow());

            files.file("drop/3.json", "{}");
            trigger.failNextFilesCall("unreachable");
            TriggerHarness.Poll failed = trigger.poll();
            assertTrue(failed.events().isEmpty() && failed.checkpoint().isEmpty());
            assertEquals("DEGRADED", trigger.health().get(0).status());
            assertEquals(List.of("drop/3.json"), trigger.poll().events().stream().map(TriggerHarness.Emitted::id).toList());
        }
    }

    @Test
    void startsFromAGivenCheckpointAndPauses() {
        InMemoryDeviceFiles files = drop().file("drop/1.json", "{}").file("drop/2.json", "{}");
        try (TriggerHarness trigger = ToolHarness.trigger("order-dropped").config(Map.of("folder", "drop"))
                .checkpoint("{\"last\":\"drop/1.json\"}").deviceFiles(files).start()) {
            assertEquals(List.of("drop/2.json"), trigger.firstPoll().events().stream().map(TriggerHarness.Emitted::id).toList());
            trigger.pause();
            trigger.resume();
            assertTrue(trigger.poll().checkpoint().isPresent());
        }
    }

    @Test
    void examplesRunAndUnknownTriggersAreRefused() {
        List<TriggerHarness.ExampleResult> results = TriggerHarness.runExamples(getClass().getClassLoader());
        assertEquals(1, results.size());
        assertTrue(results.get(0).passed(), results.get(0)::toString);
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> ToolHarness.trigger("nope").start());
        assertTrue(unknown.getMessage().contains("order-dropped"), unknown.getMessage());
    }

    @Test
    void anInvalidConfigEndsTheRuntimeBeforeAnyCheck() {
        try (TriggerHarness trigger = ToolHarness.trigger("order-dropped").config(Map.of("folder", 1)).start()) {
            AssertionError refused = assertThrows(AssertionError.class, trigger::firstPoll);
            assertTrue(refused.getMessage().contains("exited with 78"), refused.getMessage());
            assertTrue(trigger.health().get(0).reason().startsWith("InvalidConfig"));
        }
    }
}
