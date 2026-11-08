// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.v2.BrokerRequestFrame;
import io.ololabs.toolgate.contracts.v2.LogFrame;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointFrame;
import io.ololabs.toolgate.contracts.v2.TriggerEmitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerExitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHealthFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHeartbeatFrame;
import io.ololabs.toolgate.contracts.v2.TriggerReadyFrame;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The trigger mode of tool protocol v2 (plan 6.2) as the POLL runtime speaks it, frame by frame, against a scripted
 * supervisor. Every frame the runtime writes is parsed with the generated binding of its definition, which refuses
 * unknown and missing members.
 */
class TriggerRuntimeTest {
    private static final ObjectMapper STRICT = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Map<String, Class<?>> BINDINGS = Map.of("READY", TriggerReadyFrame.class, "EMIT", TriggerEmitFrame.class,
            "CHECKPOINT", TriggerCheckpointFrame.class, "HEALTH", TriggerHealthFrame.class, "HEARTBEAT", TriggerHeartbeatFrame.class,
            "EXIT", TriggerExitFrame.class, "LOG", LogFrame.class, "BROKER_REQUEST", BrokerRequestFrame.class);

    private static final ClassLoader TRIGGERS = Fixtures.compile(Map.of("acme/beat/Beat.java", """
            package acme.beat;

            import io.ololabs.toolgate.sdk.annotations.*;
            import io.ololabs.toolgate.sdk.trigger.*;
            import java.util.*;

            @ToolPackage(id = "acme.beat", name = "Beat", publisher = "Acme")
            public class Beat {
                public record Config(@Param(description = "What a check does", defaultValue = "emit") String mode,
                                     @Param(description = "Events per check") int count) {}
                public record Tick(String label, long check) {}

                @ServerTrigger(name = "tick", mode = TriggerMode.POLL, description = "Emits count events per check", config = Config.class,
                               event = Tick.class, eventType = "acme.beat.tick.v1", sourceReplay = SourceReplay.EVENT_LOG,
                               intervalMs = 5000, maxEventsPerHour = 3600, maxEventBytes = 512)
                public PollResult<Tick> tick(Config config, PollContext ctx) {
                    long check = ctx.checkpoint().map(c -> c.getLong("checks")).orElse(0L) + 1;
                    if (config.mode().equals("throw")) throw new IllegalStateException("source unavailable");
                    if (config.mode().equals("big")) return PollResult.of(List.of(TriggerEvent.of(new Tick("x".repeat(600), check))));
                    if (config.mode().equals("files")) ctx.files().list();
                    ctx.log().info("check " + check);
                    List<TriggerEvent<Tick>> events = new ArrayList<>();
                    for (int i = 1; i <= config.count(); i++) {
                        events.add(TriggerEvent.of(new Tick("t" + i, check)).id("tick-" + check + "-" + i).subject("beat"));
                    }
                    return PollResult.of(events).checkpoint(Checkpoint.of("checks", check));
                }

                @ServerTrigger(name = "lossy", mode = TriggerMode.POLL, description = "Skips rejected events", config = Config.class,
                               event = Tick.class, eventType = "acme.beat.tick.v1", sourceReplay = SourceReplay.NON_REPLAYABLE,
                               intervalMs = 5000, maxEventsPerHour = 3600, onTerminalRejection = TerminalRejectionPolicy.SKIP_AND_RECORD)
                public PollResult<Tick> lossy(Config config, PollContext ctx) { return tick(config, ctx); }

                @ClientTrigger(name = "files", mode = TriggerMode.POLL, description = "Lists the files area", config = Config.class,
                               event = Tick.class, eventType = "acme.beat.files.v1", sourceReplay = SourceReplay.STATE_SNAPSHOT,
                               intervalMs = 5000, maxEventsPerHour = 60)
                @DeviceFileAccess
                public PollResult<Tick> files(Config config, PollContext ctx) {
                    List<String> paths = ctx.files().list();
                    return PollResult.of(List.of(TriggerEvent.of(new Tick(String.join(",", paths), 1)))).checkpoint(Checkpoint.of("n", (long) paths.size()));
                }
            }
            """));

    /** The runtime on pipes, with a frame queue and timing that keeps tests fast. */
    private static final class Process implements AutoCloseable {
        final PipedOutputStream stdin = new PipedOutputStream();
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        final List<JsonNode> all = new ArrayList<>();
        volatile Integer exit;
        final Thread thread;

        Process(Map<String, String> env, long heartbeatMs) throws IOException {
            PipedInputStream in = new PipedInputStream(1 << 20);
            stdin.connect(in);
            PipedOutputStream out = new PipedOutputStream();
            PipedInputStream read = new PipedInputStream(out, 1 << 20);
            thread = new Thread(() -> {
                exit = TriggerRuntime.run(TRIGGERS, env, in, out, new PrintStream(diagnostics, true, StandardCharsets.UTF_8),
                        new TriggerRuntime.Options(false, heartbeatMs, 1, 5, () -> { }));
                try {
                    out.close();
                } catch (IOException e) {
                    // closed
                }
            });
            thread.setDaemon(true);
            thread.start();
            Thread reader = new Thread(() -> {
                try (InputStream r = read) {
                    ByteArrayOutputStream line = new ByteArrayOutputStream();
                    while (true) {
                        int b;
                        try {
                            b = r.read();
                        } catch (IOException e) {
                            // PipedInputStream ties its liveness to the last thread that wrote: when the runtime's
                            // stdin thread writes a final frame and ends before the runtime closes its output, a read
                            // fails with "Write end dead". Once the runtime has returned the pipe is closed, and the
                            // remaining bytes and the end of stream read normally.
                            if (!runtimeEnded()) throw e;
                            continue;
                        }
                        if (b == -1) break;
                        if (b != '\n') {
                            line.write(b);
                            continue;
                        }
                        JsonNode frame = STRICT.readTree(line.toByteArray());
                        line.reset();
                        Class<?> binding = BINDINGS.get(frame.path("kind").asText());
                        if (binding == null) throw new AssertionError("unknown frame " + frame);
                        STRICT.treeToValue(frame, binding);
                        frames.add(frame);
                    }
                } catch (IOException e) {
                    frames.add(STRICT.createObjectNode().put("kind", "INVALID").put("error", e.getMessage()));
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        /** Waits for the runtime thread; true once it has returned (and so closed its output). */
        private boolean runtimeEnded() {
            try {
                thread.join(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !thread.isAlive();
        }

        static Process start(String trigger, String config, String checkpoint) throws IOException {
            return start(trigger, config, checkpoint, "SERVER", 3_600_000);
        }

        static Process start(String trigger, String config, String checkpoint, String target, long heartbeatMs) throws IOException {
            Process p = new Process(Map.of("TOOLGATE_EXECUTION_TARGET", target), heartbeatMs);
            var start = STRICT.createObjectNode().put("kind", "TRIGGER_START").put("triggerId", "acme.beat/" + trigger).put("instanceId", "i-1")
                    .put("configRevision", 3).put("leaseEpoch", 7);
            start.set("config", STRICT.readTree(config));
            if (checkpoint != null) start.put("checkpoint", Base64.getEncoder().encodeToString(checkpoint.getBytes(StandardCharsets.UTF_8)));
            p.send(start.toString());
            return p;
        }

        void send(String line) throws IOException {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        /** The next frame other than HEARTBEAT and LOG. */
        JsonNode next() throws InterruptedException {
            while (true) {
                JsonNode f = frames.poll(20, TimeUnit.SECONDS);
                if (f == null) throw new AssertionError("no frame; stderr:\n" + diagnostics.toString(StandardCharsets.UTF_8));
                all.add(f);
                String kind = f.path("kind").asText();
                if (!kind.equals("HEARTBEAT") && !kind.equals("LOG")) return f;
            }
        }

        JsonNode expect(String kind) throws InterruptedException {
            JsonNode f = next();
            assertEquals(kind, f.path("kind").asText(), f::toString);
            return f;
        }

        /** No frame other than HEARTBEAT or LOG arrives within the window. */
        void quiet(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                JsonNode f = frames.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) return;
                all.add(f);
                String kind = f.path("kind").asText();
                if (!kind.equals("HEARTBEAT") && !kind.equals("LOG")) throw new AssertionError("unexpected frame " + f);
            }
        }

        void ack(long emitSeq, String status) throws IOException {
            send("{\"kind\":\"EMIT_ACK\",\"emitSeq\":" + emitSeq + ",\"status\":\"" + status + "\""
                    + (status.equals("LOCAL_DURABLE") ? ",\"outboxSeq\":" + emitSeq : ",\"reason\":\"RateLimited\"") + "}");
        }

        int exit() throws InterruptedException {
            thread.join(20_000);
            if (exit == null) throw new AssertionError("the runtime did not exit");
            return exit;
        }

        @Override
        public void close() throws IOException {
            if (exit == null) send("{\"kind\":\"STOP\",\"reason\":\"TestEnded\",\"graceMs\":100}");
        }
    }

    private static String state(JsonNode checkpoint) {
        return new String(Base64.getDecoder().decode(checkpoint.path("state").asText()), StandardCharsets.UTF_8);
    }

    @Test
    void readyThenEmitsThenTheCheckpointOnlyOnceEveryEmitIsDurable() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":3}", null)) {
            p.expect("READY");
            JsonNode e1 = p.expect("EMIT"), e2 = p.expect("EMIT"), e3 = p.expect("EMIT");
            assertEquals(List.of(1L, 2L, 3L), List.of(e1.path("emitSeq").asLong(), e2.path("emitSeq").asLong(), e3.path("emitSeq").asLong()));
            assertEquals("tick-1-1", e1.path("id").asText());
            assertEquals("beat", e1.path("subject").asText());
            assertEquals("{\"label\":\"t1\",\"check\":1}", e1.path("data").toString());
            assertFalse(e1.has("position"), "POLL emits carry no position");
            // Acks out of order: the checkpoint waits for the last one, whichever it is.
            p.ack(3, "LOCAL_DURABLE");
            p.ack(1, "LOCAL_DURABLE");
            p.quiet(300);
            p.ack(2, "LOCAL_DURABLE");
            JsonNode cp = p.expect("CHECKPOINT");
            assertEquals(1, cp.path("checkpointId").asLong());
            assertEquals(3, cp.path("afterEmitSeq").asLong());
            assertEquals("{\"checks\":1}", state(cp));
            // RECONCILE checks again from the in-process checkpoint; emitSeq and checkpointId continue.
            p.send("{\"kind\":\"RECONCILE\"}");
            JsonNode e4 = p.expect("EMIT");
            assertEquals(4, e4.path("emitSeq").asLong());
            assertEquals("tick-2-1", e4.path("id").asText());
            for (long s = 4; s <= 6; s++) {
                if (s > 4) p.expect("EMIT");
                p.ack(s, "LOCAL_DURABLE");
            }
            JsonNode cp2 = p.expect("CHECKPOINT");
            assertEquals(2, cp2.path("checkpointId").asLong());
            assertEquals(6, cp2.path("afterEmitSeq").asLong());
            assertEquals("{\"checks\":2}", state(cp2));
            assertTrue(p.all.stream().noneMatch(f -> f.path("kind").asText().equals("HEALTH")), "no health change while healthy");
            assertTrue(p.all.stream().anyMatch(f -> f.path("kind").asText().equals("LOG") && f.path("message").asText().equals("check 2")));
        }
    }

    @Test
    void aRetryableRejectionResendsTheSameEmitAndHoldsTheCheckpoint() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":2}", null)) {
            p.expect("READY");
            JsonNode first = p.expect("EMIT");
            JsonNode second = p.expect("EMIT");
            p.ack(1, "RETRYABLE_REJECTION");
            p.ack(2, "LOCAL_DURABLE");
            JsonNode resent = p.expect("EMIT");
            assertEquals(first, resent, "the same frame, same emitSeq and id");
            p.ack(1, "RETRYABLE_REJECTION");
            assertEquals(first, p.expect("EMIT"));
            p.ack(1, "LOCAL_DURABLE");
            JsonNode cp = p.expect("CHECKPOINT");
            assertEquals(2, cp.path("afterEmitSeq").asLong());
            assertEquals(2, second.path("emitSeq").asLong());
        }
    }

    @Test
    void aTerminalRejectionUnderHaltStopsCheckingWithoutACheckpoint() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":2}", null)) {
            p.expect("READY");
            p.expect("EMIT");
            p.expect("EMIT");
            p.send("{\"kind\":\"EMIT_ACK\",\"emitSeq\":1,\"status\":\"TERMINAL_REJECTION\",\"reason\":\"SchemaViolation\"}");
            p.ack(2, "LOCAL_DURABLE");
            JsonNode health = p.expect("HEALTH");
            assertEquals("UNHEALTHY", health.path("status").asText());
            assertTrue(health.path("reason").asText().startsWith("TerminalRejection"), health::toString);
            p.send("{\"kind\":\"RECONCILE\"}");
            p.quiet(500);
            assertTrue(p.all.stream().noneMatch(f -> f.path("kind").asText().equals("CHECKPOINT")));
        }
    }

    @Test
    void aTerminalRejectionUnderSkipAndRecordIsResolvedByTheLossRecord() throws Exception {
        try (Process p = Process.start("lossy", "{\"count\":2}", null)) {
            p.expect("READY");
            p.expect("EMIT");
            p.expect("EMIT");
            p.send("{\"kind\":\"EMIT_ACK\",\"emitSeq\":1,\"status\":\"TERMINAL_REJECTION\",\"reason\":\"SchemaViolation\"}");
            p.ack(2, "LOCAL_DURABLE");
            assertEquals(2, p.expect("CHECKPOINT").path("afterEmitSeq").asLong());
        }
    }

    @Test
    void stopEndsWithExitAndNothingIsEmittedAfterIt() throws Exception {
        Process p = Process.start("tick", "{\"count\":0}", null);
        p.expect("READY");
        JsonNode cp = p.expect("CHECKPOINT");
        assertEquals(0, cp.path("afterEmitSeq").asLong(), "a check without events still commits its checkpoint");
        p.send("{\"kind\":\"STOP\",\"reason\":\"Removed\",\"graceMs\":200}");
        assertEquals(0, p.expect("EXIT").path("code").asInt());
        assertEquals(0, p.exit());
    }

    @Test
    void pauseHoldsChecksUntilResume() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":0}", null)) {
            p.expect("READY");
            p.expect("CHECKPOINT");
            p.send("{\"kind\":\"PAUSE\"}");
            p.send("{\"kind\":\"RECONCILE\"}");
            p.quiet(400);
            p.send("{\"kind\":\"RESUME\"}");
            assertEquals("{\"checks\":2}", state(p.expect("CHECKPOINT")));
        }
    }

    @Test
    void theStartingCheckpointAndConfigDefaultsReachTheCheck() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":1}", "{\"checks\":41}")) {
            p.expect("READY");
            JsonNode emit = p.expect("EMIT");
            assertEquals("tick-42-1", emit.path("id").asText());
            p.ack(1, "LOCAL_DURABLE");
            assertEquals("{\"checks\":42}", state(p.expect("CHECKPOINT")));
        }
    }

    @Test
    void aFailingCheckIsDegradedAndCommitsNothing() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":1,\"mode\":\"throw\"}", null)) {
            p.expect("READY");
            JsonNode health = p.expect("HEALTH");
            assertEquals("DEGRADED", health.path("status").asText());
            assertEquals("CheckFailed: IllegalStateException: source unavailable", health.path("reason").asText());
            p.send("{\"kind\":\"RECONCILE\"}");
            p.quiet(300); // the same degraded state is not reported twice
        }
    }

    @Test
    void anOversizedEventIsRefusedBeforeAnyEmit() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":1,\"mode\":\"big\"}", null)) {
            p.expect("READY");
            JsonNode health = p.expect("HEALTH");
            assertEquals("UNHEALTHY", health.path("status").asText());
            assertTrue(health.path("reason").asText().startsWith("EventTooLarge"), health::toString);
            assertTrue(p.all.stream().noneMatch(f -> f.path("kind").asText().equals("EMIT")));
        }
    }

    @Test
    void aClientTriggerReadsTheFilesAreaThroughTheBroker() throws Exception {
        try (Process p = Process.start("files", "{\"count\":0}", null, "CLIENT", 3_600_000)) {
            p.expect("READY");
            JsonNode request = p.expect("BROKER_REQUEST");
            assertEquals("FILES_LIST", request.path("operation").asText());
            p.send("{\"kind\":\"BROKER_RESPONSE\",\"requestId\":\"" + request.path("requestId").asText() + "\",\"result\":{\"paths\":[\"inbox\",\"inbox/a.txt\"]}}");
            JsonNode emit = p.expect("EMIT");
            assertEquals("inbox,inbox/a.txt", emit.path("data").path("label").asText());
            assertNull(emit.get("id"), "without an id the executor assigns one");
            p.ack(1, "LOCAL_DURABLE");
            assertEquals("{\"n\":2}", state(p.expect("CHECKPOINT")));
        }
    }

    @Test
    void aServerTriggerHasNoFilesArea() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":1,\"mode\":\"files\"}", null, "CLIENT", 3_600_000)) {
            p.expect("READY");
            JsonNode health = p.expect("HEALTH");
            assertTrue(health.path("reason").asText().contains("FILES_REFUSED") || health.path("reason").asText().contains("@DeviceFileAccess"), health::toString);
        }
    }

    @Test
    void heartbeatsComeFromTheirOwnThread() throws Exception {
        try (Process p = Process.start("tick", "{\"count\":1}", null, "SERVER", 50)) {
            p.expect("READY");
            p.expect("EMIT");
            // No ack: the check is blocked, and heartbeats still arrive.
            long beats = 0;
            long end = System.currentTimeMillis() + 5_000;
            while (beats < 3 && System.currentTimeMillis() < end) {
                JsonNode f = p.frames.poll(1, TimeUnit.SECONDS);
                if (f != null && f.path("kind").asText().equals("HEARTBEAT")) beats++;
            }
            assertEquals(3, beats);
        }
    }

    @Test
    void refusedStartsExitWithoutReady() throws Exception {
        Process garbage = new Process(Map.of(), 3_600_000);
        garbage.send("not json");
        assertEquals(65, garbage.exit());

        Process unknown = Process.start("nope", "{\"count\":1}", null);
        assertEquals(65, unknown.exit());

        Process badConfig = Process.start("tick", "{\"count\":\"many\"}", null);
        JsonNode health = badConfig.expect("HEALTH");
        assertTrue(health.path("reason").asText().startsWith("InvalidConfig"), health::toString);
        assertEquals(78, badConfig.exit());

        Process extra = Process.start("tick", "{\"count\":1,\"other\":true}", null);
        assertTrue(extra.expect("HEALTH").path("reason").asText().startsWith("InvalidConfig"));
        assertEquals(78, extra.exit());

        Process wrongExecutor = Process.start("files", "{\"count\":1}", null, "SERVER", 3_600_000);
        assertTrue(wrongExecutor.expect("HEALTH").path("reason").asText().startsWith("Unsupported"));
        assertEquals(78, wrongExecutor.exit());

        Process badCheckpoint = Process.start("tick", "{\"count\":1}", "[1,2]");
        assertEquals(65, badCheckpoint.exit());

        Process unknownMember = new Process(Map.of(), 3_600_000);
        unknownMember.send("{\"kind\":\"TRIGGER_START\",\"triggerId\":\"acme.beat/tick\",\"instanceId\":\"i\",\"configRevision\":1,\"leaseEpoch\":1,\"config\":{\"count\":1},\"extra\":1}");
        assertEquals(65, unknownMember.exit());
    }

    @Test
    void anUnexpectedFrameIsAProtocolViolation() throws Exception {
        Process p = Process.start("tick", "{\"count\":0}", null);
        p.expect("READY");
        p.expect("CHECKPOINT");
        p.send("{\"kind\":\"TRIGGER_START\"}");
        assertEquals(65, p.exit());
        p.quiet(200);
        assertTrue(p.all.stream().noneMatch(f -> f.path("kind").asText().equals("EXIT")), "a violation ends without EXIT");
        assertTrue(p.all.stream().anyMatch(f -> f.path("kind").asText().equals("LOG") && f.path("message").asText().contains("protocol violation")));
    }
}
