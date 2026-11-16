// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.logwatch;

import static org.junit.jupiter.api.Assertions.*;

import io.ololabs.toolgate.contracts.v2.TriggerEmitStatus;
import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.context.ToolLogger;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.TriggerHarness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LogWatchTest {
    static final class Time extends Clock {
        long seconds = 1000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }

    static final class Source implements PollContext {
        final InMemoryDeviceFiles files = new InMemoryDeviceFiles(List.of("log"), 131072);
        Checkpoint checkpoint;
        Source(String body) { files.mkdir("logs"); files.file("logs/app.log", body); }
        public String triggerId() { return "examples.log-watch/error-spike"; }
        public String instanceId() { return "test-instance"; }
        public long leaseEpoch() { return 1; }
        public long configRevision() { return 1; }
        public Map<String, Object> globals() { return Map.of(); }
        public Optional<Checkpoint> checkpoint() { return Optional.ofNullable(checkpoint); }
        public String startedAt() { return "2026-11-16T00:00:00Z"; }
        public DeviceFiles files() { return files; }
        public ToolLogger log() { throw new AssertionError("Log contents must not enter runtime diagnostics"); }
        public boolean stopping() { return false; }
        PollResult<LogWatch.MatchObserved> poll(LogWatch watch, LogWatch.Config config) {
            var result = watch.check(config, this);
            checkpoint = result.checkpoint().orElseThrow();
            return result;
        }
    }

    private static LogWatch.Config config(int threshold, int cooldown) {
        return new LogWatch.Config("logs/app.log", "ERROR|FATAL", threshold, 60, cooldown);
    }

    @Test void completeUtf8LinesOnlyAndNoDuplicateAfterRestart() {
        var source = new Source("INFO ready\nERROR café\nERROR incom");
        var time = new Time();
        var watch = new LogWatch(time);
        var first = source.poll(watch, config(1, 0));
        assertEquals(1, first.events().size());
        assertEquals(23, first.events().get(0).data().endByte());
        assertEquals("ERROR café", first.events().get(0).data().sample());
        assertTrue(source.poll(new LogWatch(time), config(1, 0)).events().isEmpty());
        source.files.appendText("logs/app.log", "plete\r\n");
        var next = source.poll(watch, config(1, 0));
        assertEquals("ERROR incomplete", next.events().get(0).data().sample());
        assertEquals(23, next.events().get(0).data().startByte());
    }

    @Test void thresholdSpansPollsAndCooldownSuppressesNoise() {
        var source = new Source("ERROR one\n");
        var time = new Time();
        var watch = new LogWatch(time);
        assertTrue(source.poll(watch, config(2, 60)).events().isEmpty());
        time.seconds += 5;
        source.files.appendText("logs/app.log", "ERROR two\nERROR three\n");
        assertEquals(3, source.poll(watch, config(2, 60)).events().get(0).data().matchCount());
        source.files.appendText("logs/app.log", "ERROR suppressed\nERROR suppressed\n");
        assertTrue(source.poll(watch, config(2, 60)).events().isEmpty());
        time.seconds += 61;
        source.files.appendText("logs/app.log", "ERROR again\nERROR again\n");
        assertEquals(2, source.poll(watch, config(2, 60)).events().get(0).data().matchCount());
    }

    @Test void windowExpiresAndClockRegressionDoesNotShortenCooldown() {
        var source = new Source("ERROR one\n");
        var time = new Time();
        var watch = new LogWatch(time);
        source.poll(watch, config(2, 60));
        time.seconds += 61;
        source.files.appendText("logs/app.log", "ERROR two\n");
        assertTrue(source.poll(watch, config(2, 60)).events().isEmpty());
        source.files.appendText("logs/app.log", "ERROR three\n");
        assertEquals(2, source.poll(watch, config(2, 60)).events().get(0).data().matchCount());
        time.seconds -= 100;
        source.files.appendText("logs/app.log", "ERROR four\nERROR five\n");
        assertTrue(source.poll(watch, config(2, 60)).events().isEmpty());
        assertEquals(1061, source.checkpoint.getLong("observedAt"));
    }

    @Test void detectsTruncationAndSameLengthRewriteWithoutClaimingInodeIdentity() {
        var source = new Source("ERROR aaa\n");
        var watch = new LogWatch(new Time());
        var first = source.poll(watch, config(1, 0)).events().get(0);
        source.files.file("logs/app.log", "ERROR bbb\n");
        var rewrite = source.poll(watch, config(1, 0)).events().get(0);
        assertTrue(rewrite.data().rewritten());
        assertNotEquals(first.id(), rewrite.id());
        source.files.file("logs/app.log", "ERROR\n");
        assertTrue(source.poll(watch, config(1, 0)).events().get(0).data().rewritten());
    }

    @Test void batchesBoundedRecordsWithoutSkippingRemainder() {
        var source = new Source("ERROR x\n".repeat(600));
        var watch = new LogWatch(new Time());
        assertEquals(256, source.poll(watch, config(1, 0)).events().get(0).data().matchCount());
        assertEquals(256, source.poll(watch, config(1, 0)).events().get(0).data().matchCount());
        assertEquals(88, source.poll(watch, config(1, 0)).events().get(0).data().matchCount());
        assertTrue(source.poll(watch, config(1, 0)).events().isEmpty());
    }

    @Test void retryBeforeCheckpointHasSameIdentityAndPayload() {
        var source = new Source("ERROR x\n");
        var watch = new LogWatch(new Time());
        var first = watch.check(config(1, 0), source).events().get(0);
        var replay = watch.check(config(1, 0), source).events().get(0);
        assertEquals(first.id(), replay.id());
        assertEquals(first.data(), replay.data());
    }

    @Test void refusesChangedConfigAndUnknownStateWithoutConvertingIt() {
        var source = new Source("INFO ready\n");
        var watch = new LogWatch(new Time());
        source.poll(watch, config(1, 0));
        var saved = source.checkpoint;
        assertThrows(IllegalStateException.class, () -> source.poll(watch, config(2, 0)));
        assertEquals(saved, source.checkpoint);
        source.checkpoint = saved.with("format", 2);
        assertThrows(IllegalStateException.class, () -> watch.check(config(1, 0), source));
    }

    @Test void rejectsUnsafePathsInvalidRegexAndOversizedFilesWithoutAdvancing() {
        var source = new Source("ERROR x\n");
        var watch = new LogWatch(new Time());
        assertThrows(IllegalArgumentException.class, () -> watch.check(new LogWatch.Config("../app.log", "ERROR", 1, 60, 0), source));
        assertThrows(com.google.re2j.PatternSyntaxException.class, () -> watch.check(new LogWatch.Config("logs/app.log", "(?=ERROR)", 1, 60, 0), source));
        source.files.file("logs/app.log", "x".repeat(65537));
        assertThrows(IllegalStateException.class, () -> source.poll(watch, config(1, 0)));
        assertNull(source.checkpoint);
    }

    @Test void pathologicalBacktrackingPatternUsesLinearTimeEngine() {
        var source = new Source("a".repeat(32000) + "!\n");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertTrue(source.poll(new LogWatch(new Time()), new LogWatch.Config("logs/app.log", "(a+)+$", 1, 60, 0)).events().isEmpty()));
    }

    @Test void actualRuntimeRetriesOneEventBeforeCheckpointAndResumesFromIt() {
        var files = new Source("INFO ready\n").files;
        Checkpoint committed;
        try (var trigger = ToolHarness.trigger("error-spike").config(Map.of("file", "logs/app.log", "cooldownSeconds", 0))
                .deviceFiles(files).start()) {
            assertTrue(trigger.firstPoll().events().isEmpty());
            files.appendText("logs/app.log", "ERROR database down\n");
            trigger.rejectNext(TriggerEmitStatus.RETRYABLE_REJECTION);
            var poll = trigger.poll();
            assertEquals(2, poll.events().size());
            assertEquals(poll.events().get(0).id(), poll.events().get(1).id());
            assertEquals(poll.events().get(0).emitSeq(), poll.events().get(1).emitSeq());
            committed = poll.checkpoint().orElseThrow();
        }
        try (var trigger = ToolHarness.trigger("error-spike").config(Map.of("file", "logs/app.log", "cooldownSeconds", 0))
                .checkpoint(committed).deviceFiles(files).start()) {
            assertTrue(trigger.firstPoll().events().isEmpty());
        }
    }

    @Test void failedReadDoesNotAdvanceAndTerminalRejectionHalts() {
        var files = new Source("INFO ready\n").files;
        try (var trigger = ToolHarness.trigger("error-spike").config(Map.of("file", "logs/app.log", "cooldownSeconds", 0))
                .deviceFiles(files).start()) {
            trigger.firstPoll();
            files.appendText("logs/app.log", "ERROR database down\n");
            trigger.failNextFilesCall("Unavailable");
            assertTrue(trigger.poll().checkpoint().isEmpty());
            trigger.rejectNext(TriggerEmitStatus.TERMINAL_REJECTION);
            assertTrue(trigger.poll().checkpoint().isEmpty());
            assertEquals("UNHEALTHY", trigger.health().get(trigger.health().size() - 1).status());
            assertEquals(1, trigger.checkpoints().size());
        }
    }

    @Test void selfTestPasses() {
        var results = TriggerHarness.runExamples(Thread.currentThread().getContextClassLoader());
        assertEquals(1, results.size());
        results.forEach(result -> assertTrue(result.passed(), result::toString));
    }
}
