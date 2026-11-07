// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.BrokerRequestFrame;
import io.ololabs.toolgate.contracts.v2.BrokerResponseFrame;
import io.ololabs.toolgate.contracts.v2.LogFrame;
import io.ololabs.toolgate.contracts.v2.ToolErrorBody;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointAckFrame;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointFrame;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointLevel;
import io.ololabs.toolgate.contracts.v2.TriggerEmitAckFrame;
import io.ololabs.toolgate.contracts.v2.TriggerEmitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerEmitStatus;
import io.ololabs.toolgate.contracts.v2.TriggerExitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHealthFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHeartbeatFrame;
import io.ololabs.toolgate.contracts.v2.TriggerPauseFrame;
import io.ololabs.toolgate.contracts.v2.TriggerReadyFrame;
import io.ololabs.toolgate.contracts.v2.TriggerReconcileFrame;
import io.ololabs.toolgate.contracts.v2.TriggerResumeFrame;
import io.ololabs.toolgate.contracts.v2.TriggerStartFrame;
import io.ololabs.toolgate.contracts.v2.TriggerStopFrame;
import io.ololabs.toolgate.runtime.InMemoryDeviceFiles;
import io.ololabs.toolgate.runtime.TriggerRuntime;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Runs one POLL trigger in test mode (plan 5.6, test support): the real trigger runtime, over the real stdio frames,
 * against a fake supervisor. Every frame is read and written through the generated bindings of
 * {@code trigger.schema.json}, so a trigger that passes here speaks the protocol an executor expects. The harness acks
 * each {@code EMIT} as {@code LOCAL_DURABLE} unless a rejection is scripted, acks each {@code CHECKPOINT} at
 * {@code LOCAL}, and answers {@code FILES_*} broker requests from a device files area, with injectable faults.
 *
 * <pre>{@code
 * try (TriggerHarness trigger = ToolHarness.trigger("new-file")
 *         .config(Map.of("folder", "inbox"))
 *         .deviceFiles(new InMemoryDeviceFiles(List.of("pdf"), 65536).file(...))
 *         .start()) {
 *     TriggerHarness.Poll first = trigger.firstPoll();
 *     assertEquals(1, first.events().size());
 *     TriggerHarness.Poll second = trigger.poll();   // RECONCILE: check again now
 *     assertTrue(second.events().isEmpty());
 * }
 * }</pre>
 */
public final class TriggerHarness implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration WAIT = Duration.ofSeconds(30);

    /** One emitted event, as the supervisor received it. */
    public record Emitted(long emitSeq, String id, String subject, String time, JsonNode data) {}

    /** What one check produced: its emits, in order (a retried emit appears once per attempt), and its checkpoint. */
    public record Poll(List<Emitted> events, Optional<Checkpoint> checkpoint) {}

    /** A HEALTH frame. */
    public record Health(String status, String reason) {}

    private final Builder builder;
    private final PipedOutputStream toRuntime = new PipedOutputStream();
    private final PipedInputStream fromRuntime = new PipedInputStream(1 << 20);
    private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    /** Per finished check, how many frames the runtime had written by then; -1 once it exited. */
    private final LinkedBlockingQueue<Long> checks = new LinkedBlockingQueue<>();
    private final AtomicLong written = new AtomicLong();
    private long handled;
    private final Thread runtime;
    private final Thread supervisor;
    private final List<JsonNode> frames = new ArrayList<>();
    private final List<Emitted> emitted = new ArrayList<>();
    private final List<Health> health = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final List<Long> checkpointAfter = new ArrayList<>();
    private final Deque<TriggerEmitStatus> scripted = new ArrayDeque<>();
    private final Deque<String> fileFaults = new ArrayDeque<>();
    private volatile Integer exit;
    private int polled;
    private int emittedAtPoll;
    private int checkpointsAtPoll;
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private long outboxSeq;
    private boolean ready;

    private TriggerHarness(Builder b) {
        this.builder = b;
        PipedInputStream stdin = new PipedInputStream(1 << 20);
        PipedOutputStream stdout = new PipedOutputStream();
        try {
            toRuntime.connect(stdin);
            stdout.connect(fromRuntime);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A check is read only once the supervisor has handled every frame written before it ended.
        OutputStream counted = new FilterOutputStream(stdout) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                if (b == '\n') written.incrementAndGet();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                for (int i = off; i < off + len; i++) if (b[i] == '\n') written.incrementAndGet();
            }
        };
        TriggerRuntime.Options options = new TriggerRuntime.Options(false, 3_600_000, 1, 5, () -> checks.add(written.get()));
        Map<String, String> env = Map.of("TOOLGATE_PROTOCOL", "2", "TOOLGATE_EXECUTION_MODE", "TRIGGER", "TOOLGATE_EXECUTION_TARGET", b.target());
        PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8);
        runtime = new Thread(() -> {
            int code = TriggerRuntime.run(b.loader, env, stdin, counted, err, options);
            exit = code;
            try {
                stdout.close();
            } catch (IOException e) {
                // closed already
            }
            checks.add(-1L);
        }, "toolgate-trigger-harness-runtime");
        runtime.setDaemon(true);
        supervisor = new Thread(this::supervise, "toolgate-trigger-harness-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
        runtime.start();
        send(new TriggerStartFrame("TRIGGER_START", b.triggerId(), b.instanceId, b.configRevision, b.leaseEpoch, b.config, b.globals, null, b.encodedCheckpoint()));
    }

    /** A builder for the trigger with this local name or trigger id, from the class path's {@code META-INF/toolgate}. */
    public static Builder builder(String trigger) {
        return new Builder(trigger);
    }

    // ---- driving ----

    /** Waits for the check the runtime runs at start, and returns it. */
    public Poll firstPoll() {
        if (polled != 0) throw new IllegalStateException("the first poll was already read");
        return next();
    }

    /** Sends {@code RECONCILE}, which in POLL mode checks now, and returns that check. */
    public Poll poll() {
        if (polled == 0) firstPoll();
        send(new TriggerReconcileFrame("RECONCILE"));
        return next();
    }

    /** Drives {@code n} more checks and returns them in order. */
    public List<Poll> poll(int n) {
        List<Poll> polls = new ArrayList<>();
        for (int i = 0; i < n; i++) polls.add(poll());
        return polls;
    }

    /** The next acks, in emit order, are these statuses instead of LOCAL_DURABLE. */
    public synchronized TriggerHarness rejectNext(TriggerEmitStatus... statuses) {
        scripted.addAll(List.of(statuses));
        return this;
    }

    /** The next device files operation fails, as an unreachable or refusing executor would. */
    public synchronized TriggerHarness failNextFilesCall(String message) {
        fileFaults.add(message);
        return this;
    }

    public void pause() {
        send(new TriggerPauseFrame("PAUSE"));
    }

    public void resume() {
        send(new TriggerResumeFrame("RESUME"));
    }

    /** Sends {@code STOP} and returns the exit code. */
    public int stop() {
        if (exit == null) send(new TriggerStopFrame("STOP", "Stopped", 1_000L));
        try {
            runtime.join(WAIT.toMillis());
            supervisor.join(WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (exit == null) throw new AssertionError("the trigger did not stop:\n" + diagnostics());
        return exit;
    }

    @Override
    public void close() {
        if (exit == null) stop();
    }

    // ---- what happened ----

    /** Every frame the runtime wrote, in order. */
    public synchronized List<JsonNode> frames() {
        return List.copyOf(frames);
    }

    public synchronized List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    /** Every checkpoint the runtime committed, decoded, in order. */
    public synchronized List<Checkpoint> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /** {@code afterEmitSeq} of each committed checkpoint. */
    public synchronized List<Long> checkpointPositions() {
        return List.copyOf(checkpointAfter);
    }

    public synchronized List<Health> health() {
        return List.copyOf(health);
    }

    public synchronized List<String> logs() {
        return List.copyOf(logs);
    }

    public synchronized boolean ready() {
        return ready;
    }

    /** The exit code once the runtime ended, else empty. */
    public Optional<Integer> exitCode() {
        return Optional.ofNullable(exit);
    }

    /** What the runtime printed to stderr. */
    public String diagnostics() {
        return diagnostics.toString(StandardCharsets.UTF_8);
    }

    /** Sends a raw stdin line, for protocol tests. */
    public void sendLine(String line) {
        try {
            toRuntime.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toRuntime.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- plumbing ----

    private Poll next() {
        Long frames;
        try {
            frames = checks.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (frames == null) throw new AssertionError("no check finished within " + WAIT + ":\n" + diagnostics());
            if (frames < 0) {
                checks.add(-1L);
                supervisor.join(WAIT.toMillis()); // every frame written before the exit is recorded
                throw new AssertionError("the trigger exited with " + exit + " before the check finished:\n" + diagnostics());
            }
            synchronized (this) {
                long end = System.currentTimeMillis() + WAIT.toMillis();
                while (handled < frames) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new AssertionError("the supervisor did not read the check's frames");
                    wait(left);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
        synchronized (this) {
            polled++;
            List<Emitted> events = List.copyOf(emitted.subList(emittedAtPoll, emitted.size()));
            Optional<Checkpoint> checkpoint = checkpoints.size() > checkpointsAtPoll ? Optional.of(checkpoints.get(checkpoints.size() - 1)) : Optional.empty();
            emittedAtPoll = emitted.size();
            checkpointsAtPoll = checkpoints.size();
            return new Poll(events, checkpoint);
        }
    }

    private void send(Object frame) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(frame);
            synchronized (toRuntime) {
                toRuntime.write(bytes);
                toRuntime.write('\n');
                toRuntime.flush();
            }
        } catch (IOException e) {
            // the runtime has ended
        }
    }

    /** The fake supervisor: parses every frame strictly and answers what needs an answer. */
    private void supervise() {
        try (InputStream in = fromRuntime) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b; (b = in.read()) != -1; ) {
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                handle(JSON.readTree(line.toByteArray()));
                line.reset();
                synchronized (this) {
                    handled++;
                    notifyAll();
                }
            }
        } catch (IOException e) {
            // the runtime closed stdout
        }
    }

    private void handle(JsonNode frame) throws IOException {
        synchronized (this) {
            frames.add(frame);
        }
        switch (frame.path("kind").asText()) {
            case "READY" -> {
                JSON.treeToValue(frame, TriggerReadyFrame.class);
                synchronized (this) {
                    ready = true;
                }
            }
            case "HEARTBEAT" -> JSON.treeToValue(frame, TriggerHeartbeatFrame.class);
            case "EXIT" -> JSON.treeToValue(frame, TriggerExitFrame.class);
            case "LOG" -> {
                LogFrame log = JSON.treeToValue(frame, LogFrame.class);
                synchronized (this) {
                    logs.add(log.level() + ": " + log.message());
                }
            }
            case "HEALTH" -> {
                TriggerHealthFrame h = JSON.treeToValue(frame, TriggerHealthFrame.class);
                synchronized (this) {
                    health.add(new Health(h.status().name(), h.reason()));
                }
            }
            case "EMIT" -> {
                TriggerEmitFrame e = JSON.treeToValue(frame, TriggerEmitFrame.class);
                TriggerEmitStatus status;
                synchronized (this) {
                    emitted.add(new Emitted(e.emitSeq(), e.id(), e.subject(), e.time(), e.data()));
                    status = scripted.isEmpty() ? TriggerEmitStatus.LOCAL_DURABLE : scripted.poll();
                }
                Long outbox = status == TriggerEmitStatus.RETRYABLE_REJECTION ? null : ++outboxSeq;
                String reason = switch (status) {
                    case LOCAL_DURABLE -> null;
                    case RETRYABLE_REJECTION -> "RateLimited";
                    case TERMINAL_REJECTION -> "SchemaViolation";
                };
                send(new TriggerEmitAckFrame("EMIT_ACK", e.emitSeq(), status, reason, outbox));
            }
            case "CHECKPOINT" -> {
                TriggerCheckpointFrame c = JSON.treeToValue(frame, TriggerCheckpointFrame.class);
                Checkpoint decoded = Checkpoint.of(JSON.readValue(Base64.getDecoder().decode(c.state()), new TypeReference<LinkedHashMap<String, Object>>() {}));
                synchronized (this) {
                    checkpoints.add(decoded);
                    checkpointAfter.add(c.afterEmitSeq());
                }
                send(new TriggerCheckpointAckFrame("CHECKPOINT_ACK", c.checkpointId(), TriggerCheckpointLevel.LOCAL));
            }
            case "BROKER_REQUEST" -> {
                BrokerRequestFrame r = JSON.treeToValue(frame, BrokerRequestFrame.class);
                send(answer(r));
            }
            default -> throw new IOException("the runtime wrote an unknown frame: " + frame);
        }
    }

    private BrokerResponseFrame answer(BrokerRequestFrame r) {
        String fault;
        synchronized (this) {
            fault = fileFaults.poll();
        }
        if (fault != null) return refusal(r, "FILES_UNAVAILABLE", fault);
        DeviceFiles files = builder.files;
        Map<String, JsonNode> request = r.request() == null ? Map.of() : r.request();
        String path = request.containsKey("path") ? request.get("path").asText() : null;
        try {
            ObjectNode result = JSON.createObjectNode();
            switch (r.operation()) {
                case FILES_LIST -> result.set("paths", JSON.valueToTree(files.list()));
                case FILES_READ -> result.put("text", files.readText(path));
                case FILES_INFO -> {
                    DeviceFiles.Info info = files.info(path);
                    result.put("path", info.path()).put("bytes", info.bytes());
                }
                default -> {
                    // A trigger's device file access is read-only, and no other operation reaches a trigger in this SDK version.
                    return refusal(r, "FILES_REFUSED", "The executor refuses " + r.operation() + " for a trigger");
                }
            }
            return new BrokerResponseFrame("BROKER_RESPONSE", r.requestId(), JSON.convertValue(result, new TypeReference<LinkedHashMap<String, JsonNode>>() {}), null);
        } catch (PlatformError e) {
            return refusal(r, "FILES_REFUSED", e.getMessage());
        }
    }

    private static BrokerResponseFrame refusal(BrokerRequestFrame r, String code, String message) {
        return new BrokerResponseFrame("BROKER_RESPONSE", r.requestId(), null, new ToolErrorBody(code, message, false, null));
    }

    // ---- examples ----

    /** The outcome of one {@code @TriggerExample}: null {@code problem} when it passed. */
    public record ExampleResult(String trigger, String name, String problem) {
        public boolean passed() {
            return problem == null;
        }

        @Override
        public String toString() {
            return "trigger " + trigger + " example " + name + (passed() ? " passed" : ": " + problem);
        }
    }

    /** Runs every {@code @TriggerExample} of every trigger on the class path: one check each, from its own state. */
    public static List<ExampleResult> runExamples(ClassLoader loader) {
        List<ExampleResult> results = new ArrayList<>();
        JsonNode index = index(loader);
        if (index == null) return results;
        for (JsonNode trigger : index.path("triggers")) {
            String name = trigger.path("name").asText();
            for (JsonNode example : trigger.path("examples")) results.add(runExample(loader, name, example));
        }
        return results;
    }

    private static ExampleResult runExample(ClassLoader loader, String trigger, JsonNode example) {
        String name = example.path("name").asText();
        try {
            Builder b = builder(trigger).classLoader(loader).config(example.path("config").asText());
            if (example.has("checkpoint")) b.checkpoint(example.path("checkpoint").asText());
            if (example.has("files")) b.deviceFiles(filesOf(JSON.readTree(example.path("files").asText())));
            Poll poll;
            List<Health> health;
            try (TriggerHarness h = b.start()) {
                poll = h.firstPoll();
                health = h.health();
            }
            for (Health x : health) {
                if (!x.status().equals("HEALTHY")) return new ExampleResult(trigger, name, "the check reported " + x.status() + ": " + x.reason());
            }
            JsonNode expected = JSON.readTree(example.path("expectedEvents").asText());
            if (!expected.isArray()) return new ExampleResult(trigger, name, "its expectedEvents is not a JSON array");
            if (expected.size() != poll.events().size()) {
                return new ExampleResult(trigger, name, "expected " + expected.size() + " events but got " + poll.events().size() + ": " + poll.events());
            }
            for (int i = 0; i < expected.size(); i++) {
                JsonNode want = expected.get(i);
                Emitted got = poll.events().get(i);
                if (want.has("id") && !want.path("id").asText().equals(got.id())) return new ExampleResult(trigger, name, "event " + (i + 1) + " has id " + got.id() + ", not " + want.path("id").asText());
                if (want.has("subject") && !want.path("subject").asText().equals(got.subject())) return new ExampleResult(trigger, name, "event " + (i + 1) + " has subject " + got.subject());
                if (!want.path("data").equals(got.data())) return new ExampleResult(trigger, name, "event " + (i + 1) + " data is " + got.data() + ", not " + want.path("data"));
            }
            String expectedCheckpoint = example.path("expectedCheckpoint").asText("");
            if (expectedCheckpoint.isEmpty()) {
                if (poll.checkpoint().isPresent()) return new ExampleResult(trigger, name, "expected no checkpoint but got " + poll.checkpoint().get());
            } else {
                Checkpoint want = Checkpoint.of(JSON.readValue(expectedCheckpoint, new TypeReference<LinkedHashMap<String, Object>>() {}));
                if (poll.checkpoint().isEmpty() || !poll.checkpoint().get().equals(want)) {
                    return new ExampleResult(trigger, name, "expected checkpoint " + want + " but got " + poll.checkpoint().map(Object::toString).orElse("none"));
                }
            }
            return new ExampleResult(trigger, name, null);
        } catch (IOException | RuntimeException | AssertionError e) {
            return new ExampleResult(trigger, name, "it could not run: " + e.getMessage());
        }
    }

    /** A device files area holding exactly {@code {path: text}}, with the extensions those files use allowed. */
    static InMemoryDeviceFiles filesOf(JsonNode files) {
        TreeSet<String> extensions = new TreeSet<>(List.of("txt", "md", "json", "csv"));
        files.properties().forEach(e -> {
            int dot = e.getKey().lastIndexOf('.');
            if (dot > 0) extensions.add(e.getKey().substring(dot + 1));
        });
        InMemoryDeviceFiles area = new InMemoryDeviceFiles(List.copyOf(extensions), 1 << 20);
        TreeSet<String> directories = new TreeSet<>();
        files.properties().forEach(e -> {
            String path = e.getKey();
            for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) directories.add(path.substring(0, slash));
        });
        for (String d : directories) area.mkdir(d);
        files.properties().forEach(e -> area.file(e.getKey(), e.getValue().asText()));
        return area;
    }

    static JsonNode index(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream("META-INF/toolgate/package.json")) {
            return in == null ? null : JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Config, checkpoint, files and identity for one harnessed trigger. */
    public static final class Builder {
        private final String trigger;
        private ClassLoader loader = Thread.currentThread().getContextClassLoader();
        private Map<String, JsonNode> config = Map.of();
        private Map<String, JsonNode> globals;
        private Checkpoint checkpoint;
        private DeviceFiles files = new InMemoryDeviceFiles();
        private String target;
        private String instanceId = "instance-1";
        private long leaseEpoch = 1;
        private long configRevision = 1;
        private String triggerId;
        private JsonNode descriptor;

        private Builder(String trigger) {
            this.trigger = trigger;
        }

        public Builder classLoader(ClassLoader loader) {
            this.loader = loader;
            return this;
        }

        /** The assignment config: a JSON string, a map, a record or a JSON tree. */
        public Builder config(Object config) {
            JsonNode tree = config instanceof CharSequence text ? read(text.toString()) : config instanceof JsonNode n ? n : JSON.valueToTree(config);
            if (!tree.isObject()) throw new IllegalArgumentException("the config is a JSON object");
            this.config = JSON.convertValue(tree, new TypeReference<LinkedHashMap<String, JsonNode>>() {});
            return this;
        }

        public Builder global(String name, Object value) {
            Map<String, JsonNode> next = globals == null ? new LinkedHashMap<>() : new LinkedHashMap<>(globals);
            next.put(name, JSON.valueToTree(value));
            this.globals = next;
            return this;
        }

        /** The checkpoint the executor starts the process with: a {@link Checkpoint} or a JSON object string. */
        public Builder checkpoint(Object checkpoint) {
            this.checkpoint = checkpoint instanceof Checkpoint c ? c
                    : Checkpoint.of(JSON.convertValue(checkpoint instanceof CharSequence t ? read(t.toString()) : JSON.valueToTree(checkpoint),
                            new TypeReference<LinkedHashMap<String, Object>>() {}));
            return this;
        }

        /** The device files area the executor brokers; an empty {@link InMemoryDeviceFiles} by default. */
        public Builder deviceFiles(DeviceFiles files) {
            this.files = files;
            return this;
        }

        /** SERVER or CLIENT; by default CLIENT for a client trigger and SERVER otherwise. */
        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder instance(String instanceId, long leaseEpoch, long configRevision) {
            this.instanceId = instanceId;
            this.leaseEpoch = leaseEpoch;
            this.configRevision = configRevision;
            return this;
        }

        /** Starts the runtime and sends TRIGGER_START; the first check runs at once. */
        public TriggerHarness start() {
            resolve();
            return new TriggerHarness(this);
        }

        private void resolve() {
            JsonNode index = index(loader);
            if (index == null) throw new IllegalStateException("no triggers on the class path: META-INF/toolgate/package.json is missing;"
                    + " compile with the ToolGate processor and -Atoolgate.descriptorGeneration=1");
            String packageId = index.path("id").asText();
            String name = trigger.startsWith(packageId + "/") ? trigger.substring(packageId.length() + 1) : trigger;
            List<String> names = new ArrayList<>();
            for (JsonNode t : index.path("triggers")) names.add(t.path("name").asText());
            if (!names.contains(name)) throw new IllegalArgumentException("no trigger " + trigger + "; the class path has " + names);
            triggerId = packageId + "/" + name;
            try (InputStream in = loader.getResourceAsStream("META-INF/toolgate/triggers/" + name + ".json")) {
                if (in == null) throw new IllegalStateException("the descriptor of " + name + " is missing");
                descriptor = JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String triggerId() {
            return triggerId;
        }

        String target() {
            if (target != null) return target;
            return "CLIENT_TRIGGER".equals(descriptor.path("kind").asText()) ? "CLIENT" : "SERVER";
        }

        String encodedCheckpoint() {
            if (checkpoint == null) return null;
            try {
                return Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(checkpoint.values()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static JsonNode read(String json) {
            try {
                return JSON.readTree(json);
            } catch (IOException e) {
                throw new IllegalArgumentException("not JSON: " + json, e);
            }
        }
    }
}
