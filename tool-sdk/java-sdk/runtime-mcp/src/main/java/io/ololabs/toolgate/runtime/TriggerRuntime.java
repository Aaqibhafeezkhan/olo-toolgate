// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.LogFrame;
import io.ololabs.toolgate.contracts.v2.LogLevel;
import io.ololabs.toolgate.contracts.v2.TriggerAcceptedUpToFrame;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointAckFrame;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointFrame;
import io.ololabs.toolgate.contracts.v2.TriggerEmitAckFrame;
import io.ololabs.toolgate.contracts.v2.TriggerEmitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerExitFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHealthFrame;
import io.ololabs.toolgate.contracts.v2.TriggerHealthStatus;
import io.ololabs.toolgate.contracts.v2.TriggerHeartbeatFrame;
import io.ololabs.toolgate.contracts.v2.TriggerPauseFrame;
import io.ololabs.toolgate.contracts.v2.TriggerReadyFrame;
import io.ololabs.toolgate.contracts.v2.TriggerReconcileFrame;
import io.ololabs.toolgate.contracts.v2.TriggerResumeFrame;
import io.ololabs.toolgate.contracts.v2.TriggerStartFrame;
import io.ololabs.toolgate.contracts.v2.TriggerStopFrame;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.context.ToolLogger;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trigger mode of tool protocol v2 (ADR 058, plan 6.1 and 6.2), POLL triggers: one process runs one trigger instance
 * under an executor's supervisor. {@code TOOLGATE_PROTOCOL=2} and {@code TOOLGATE_EXECUTION_MODE=TRIGGER} select it.
 *
 * <p>The first stdin line is {@code TRIGGER_START}. Once the config is valid the runtime writes {@code READY}, checks
 * at once and then every {@code intervalMs}, and writes {@code HEARTBEAT} every 10 s. Each check's events go out as
 * {@code EMIT} frames; the check's checkpoint goes out as one {@code CHECKPOINT} only after every one of those emits is
 * resolved, so it never passes an unresolved event (plan 6.6). A retryable rejection re-sends the same {@code EMIT},
 * same {@code emitSeq}, after full-jitter backoff. A terminal rejection under {@code HALT} stops the trigger; under
 * {@code SKIP_AND_RECORD} the supervisor's loss record resolves it. {@code STOP} ends with {@code EXIT}.
 *
 * <p>Every frame uses the generated bindings of {@code trigger.schema.json} and {@code protocol.schema.json}.
 * Exit codes: 0 after {@code STOP} or end of input, 65 for a protocol violation, 78 for a refused start.
 */
public final class TriggerRuntime {
    /** Largest stdin line: TRIGGER_START is bounded well below this (8 KiB config and globals, 4 KiB checkpoint). */
    static final int MAX_LINE = 256 * 1024;
    static final int MAX_CONFIG_BYTES = 8 * 1024;
    static final int MAX_REASON = 256;
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Timing, for tests. {@code timer = false} checks only at start and on {@code RECONCILE}; {@code afterCheck} runs
     * when a check has finished, emits and checkpoint included. The production values are {@link #PRODUCTION}.
     */
    public record Options(boolean timer, long heartbeatMs, long backoffBaseMs, long backoffCapMs, Runnable afterCheck) {
        public static final Options PRODUCTION = new Options(true, 10_000, 1_000, 60_000, () -> { });
    }

    private TriggerRuntime() {}

    /** Runs one trigger instance on {@code in} and {@code out}, and returns the exit code. */
    public static int run(ClassLoader loader, Map<String, String> env, InputStream in, OutputStream out, PrintStream diagnostics) {
        return run(loader, env, in, out, diagnostics, Options.PRODUCTION);
    }

    public static int run(ClassLoader loader, Map<String, String> env, InputStream in, OutputStream out, PrintStream diagnostics, Options options) {
        Wire wire = new Wire(out);
        TriggerStartFrame start;
        try {
            byte[] first = line(in);
            if (first == null) {
                diagnostics.println("toolgate: no TRIGGER_START line");
                return 65;
            }
            start = JSON.readValue(first, TriggerStartFrame.class);
        } catch (IOException | RuntimeException e) {
            diagnostics.println("toolgate: the first line is not a TRIGGER_START frame: " + e.getMessage());
            return 65;
        }
        Instance instance;
        try {
            instance = Instance.load(loader, env, start);
        } catch (Refused e) {
            diagnostics.println("toolgate: refusing to start trigger: " + e.getMessage());
            wire.health(TriggerHealthStatus.UNHEALTHY, e.reason + ": " + e.getMessage());
            return e.exit;
        }
        return new Loop(instance, wire, in, diagnostics, options).run();
    }

    /** A refused start: the reason for HEALTH and the exit code. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;
        final int exit;

        Refused(String reason, int exit, String message) {
            super(message);
            this.reason = reason;
            this.exit = exit;
        }
    }

    // ---- the trigger and its start ----

    /** The resolved trigger: descriptor, handler, config and the starting checkpoint. */
    static final class Instance {
        final TriggerStartFrame start;
        final String name;
        final JsonNode descriptor;
        final Object handler;
        final Method check;
        final Object config;
        final Class<?> eventClass;
        final SchemaValidator events;
        final boolean client;
        final boolean files;
        final Checkpoint checkpoint;
        final Map<String, Object> globals;

        private Instance(TriggerStartFrame start, String name, JsonNode descriptor, Object handler, Method check, Object config, Class<?> eventClass,
                boolean client, boolean files, Checkpoint checkpoint, Map<String, Object> globals) {
            this.start = start;
            this.name = name;
            this.descriptor = descriptor;
            this.handler = handler;
            this.check = check;
            this.config = config;
            this.eventClass = eventClass;
            this.events = new SchemaValidator(descriptor.path("eventSchema"));
            this.client = client;
            this.files = files;
            this.checkpoint = checkpoint;
            this.globals = globals;
        }

        long interval() { return descriptor.path("schedule").path("intervalMs").asLong(); }
        int maxEventBytes() { return descriptor.path("limits").path("maxEventBytes").asInt(); }
        int maxCheckpointBytes() { return descriptor.path("limits").path("maxCheckpointBytes").asInt(); }
        boolean halts() { return "HALT".equals(descriptor.path("onTerminalRejection").asText()); }

        static Instance load(ClassLoader loader, Map<String, String> env, TriggerStartFrame start) throws Refused {
            JsonNode index = resource(loader, "META-INF/toolgate/package.json");
            if (index == null) throw new Refused("NoTriggers", 78, "META-INF/toolgate/package.json is not on the class path");
            String packageId = index.path("id").asText();
            String triggerId = start.triggerId();
            if (!triggerId.startsWith(packageId + "/")) throw new Refused("UnknownTrigger", 65, "trigger " + triggerId + " is not in package " + packageId);
            String name = triggerId.substring(packageId.length() + 1);
            JsonNode entry = null;
            for (JsonNode t : index.path("triggers")) if (t.path("name").asText().equals(name)) entry = t;
            if (entry == null) throw new Refused("UnknownTrigger", 65, "the artifact has no trigger " + triggerId);
            JsonNode descriptor = resource(loader, "META-INF/toolgate/triggers/" + name + ".json");
            if (descriptor == null) throw new Refused("UnknownTrigger", 78, "the descriptor of " + triggerId + " is missing");
            if (!"POLL".equals(descriptor.path("mode").asText())) throw new Refused("Unsupported", 78, "this runtime runs POLL triggers only");

            String target = env.getOrDefault("TOOLGATE_EXECUTION_TARGET", "SERVER");
            if (!target.equals("SERVER") && !target.equals("CLIENT")) throw new Refused("InvalidTarget", 78, "TOOLGATE_EXECUTION_TARGET must be SERVER or CLIENT");
            boolean eligible = false;
            for (JsonNode e : descriptor.path("executors")) eligible |= e.asText().equals(target);
            if (!eligible) throw new Refused("Unsupported", 78, "trigger " + triggerId + " cannot run on a " + target + " executor");
            boolean files = false;
            for (JsonNode p : descriptor.path("permissions")) files |= p.asText().equals("DEVICE_FILES_READ");

            // Config: bounded, valid against the signed configSchema, with declared defaults filled in (plan 5.8).
            ObjectNode config = JSON.valueToTree(start.config());
            byte[] configBytes;
            try {
                configBytes = JSON.writeValueAsBytes(config);
            } catch (IOException e) {
                throw new Refused("InvalidConfig", 78, "the config cannot be encoded");
            }
            if (configBytes.length > MAX_CONFIG_BYTES) throw new Refused("InvalidConfig", 78, "the config is larger than " + MAX_CONFIG_BYTES + " bytes");
            JsonNode configSchema = descriptor.path("configSchema");
            configSchema.path("properties").properties().forEach(p -> {
                if (!config.has(p.getKey()) && p.getValue().has("default")) config.set(p.getKey(), p.getValue().get("default"));
            });
            String invalid = new SchemaValidator(configSchema).validate(config);
            if (invalid != null) throw new Refused("InvalidConfig", 78, "the config does not match the trigger's configSchema: " + invalid);

            Class<?> handlerClass, configClass, eventClass;
            Object handler, typed;
            Method check;
            try {
                handlerClass = Class.forName(entry.path("handlerClass").asText(), true, loader);
                configClass = Class.forName(entry.path("configClass").asText(), true, loader);
                eventClass = Class.forName(entry.path("eventClass").asText(), true, loader);
                check = handlerClass.getMethod(entry.path("handlerMethod").asText(), configClass, PollContext.class);
                handler = handlerClass.getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new Refused("LoadFailed", 78, "cannot load the trigger handler: " + e);
            }
            try {
                typed = Converter.fromJson(config, configClass);
            } catch (RuntimeException e) {
                throw new Refused("InvalidConfig", 78, "the config does not bind to " + configClass.getSimpleName() + ": " + e.getMessage());
            }

            Checkpoint checkpoint = null;
            if (start.checkpoint() != null && !start.checkpoint().isEmpty()) {
                try {
                    byte[] state = Base64.getDecoder().decode(start.checkpoint());
                    if (state.length > descriptor.path("limits").path("maxCheckpointBytes").asInt()) throw new IllegalArgumentException("larger than maxCheckpointBytes");
                    checkpoint = Checkpoint.of(JSON.readValue(state, new TypeReference<LinkedHashMap<String, Object>>() {}));
                } catch (IOException | IllegalArgumentException e) {
                    throw new Refused("InvalidCheckpoint", 65, "the checkpoint is not a JSON object this trigger wrote: " + e.getMessage());
                }
            }
            Map<String, Object> globals = start.globals() == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(JSON.convertValue(start.globals(), new TypeReference<LinkedHashMap<String, Object>>() {}));
            return new Instance(start, name, descriptor, handler, check, typed, eventClass, target.equals("CLIENT"), files, checkpoint, globals);
        }

        private static JsonNode resource(ClassLoader loader, String path) {
            try (InputStream in = loader.getResourceAsStream(path)) {
                return in == null ? null : JSON.readTree(in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    // ---- the frame loop ----

    /** The control loop: reader thread in, one check at a time, frames out. */
    static final class Loop {
        private final Instance instance;
        private final Wire wire;
        private final InputStream in;
        private final PrintStream diagnostics;
        private final Options options;
        private final BlockingQueue<byte[]> brokerResponses = new LinkedBlockingQueue<>();
        private final Map<Long, TriggerEmitAckFrame> acks = new ConcurrentHashMap<>();
        private final Object signal = new Object();
        private final String startedAt = Instant.now().toString();
        private final StdioBroker broker;
        private boolean pollRequested = true;
        private boolean paused;
        private volatile boolean stopping;
        private long graceMs;
        private volatile int violation;
        private boolean halted;
        private long emitSeq;
        private long checkpointId;
        private Checkpoint checkpoint;
        private TriggerHealthStatus health = TriggerHealthStatus.HEALTHY;
        private String healthReason;

        Loop(Instance instance, Wire wire, InputStream in, PrintStream diagnostics, Options options) {
            this.instance = instance;
            this.wire = wire;
            this.in = in;
            this.diagnostics = diagnostics;
            this.options = options;
            this.checkpoint = instance.checkpoint;
            this.broker = new StdioBroker(() -> {
                try {
                    byte[] line = brokerResponses.take();
                    if (line.length == 0) throw new IOException("closed");
                    return line;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }, wire::frame);
        }

        int run() {
            wire.send(new TriggerReadyFrame("READY"));
            Thread reader = daemon("toolgate-trigger-frames", this::read);
            Thread heartbeat = daemon("toolgate-trigger-heartbeat", () -> {
                while (true) {
                    try {
                        Thread.sleep(options.heartbeatMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    wire.send(new TriggerHeartbeatFrame("HEARTBEAT"));
                }
            });
            Thread worker = daemon("toolgate-trigger-check", this::work);
            reader.start();
            heartbeat.start();
            worker.start();
            long grace;
            synchronized (signal) {
                while (!stopping && violation == 0) {
                    try {
                        signal.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 70;
                    }
                }
                grace = graceMs;
            }
            if (violation != 0) return violation;
            // plan 6.8: STOP → no new check, the current one gets its grace → EXIT.
            worker.interrupt();
            try {
                worker.join(Math.max(1, grace));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            heartbeat.interrupt();
            wire.send(new TriggerExitFrame("EXIT", 0L));
            return 0;
        }

        private static Thread daemon(String name, Runnable body) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            return t;
        }

        /** stdin: acks, lifecycle and broker answers. Anything else, or a malformed line, is a protocol violation. */
        private void read() {
            try {
                while (true) {
                    byte[] line = line(in);
                    if (line == null) {
                        // The supervisor went away: stop now.
                        brokerResponses.offer(new byte[0]);
                        stop(0);
                        return;
                    }
                    JsonNode frame = JSON.readTree(line);
                    String kind = frame == null ? "" : frame.path("kind").asText();
                    switch (kind) {
                        case "EMIT_ACK" -> {
                            TriggerEmitAckFrame ack = JSON.treeToValue(frame, TriggerEmitAckFrame.class);
                            acks.put(ack.emitSeq(), ack);
                            wake();
                        }
                        case "ACCEPTED_UPTO" -> JSON.treeToValue(frame, TriggerAcceptedUpToFrame.class);
                        case "CHECKPOINT_ACK" -> JSON.treeToValue(frame, TriggerCheckpointAckFrame.class);
                        case "RECONCILE" -> {
                            JSON.treeToValue(frame, TriggerReconcileFrame.class);
                            synchronized (signal) {
                                pollRequested = true;
                                signal.notifyAll();
                            }
                        }
                        case "PAUSE" -> {
                            JSON.treeToValue(frame, TriggerPauseFrame.class);
                            synchronized (signal) {
                                paused = true;
                                signal.notifyAll();
                            }
                        }
                        case "RESUME" -> {
                            JSON.treeToValue(frame, TriggerResumeFrame.class);
                            synchronized (signal) {
                                paused = false;
                                pollRequested = true;
                                signal.notifyAll();
                            }
                        }
                        case "STOP" -> {
                            TriggerStopFrame stop = JSON.treeToValue(frame, TriggerStopFrame.class);
                            brokerResponses.offer(new byte[0]);
                            stop(Math.min(30_000, Math.max(0, stop.graceMs())));
                            return;
                        }
                        case "BROKER_RESPONSE" -> brokerResponses.offer(line);
                        default -> throw new IOException("unexpected frame " + (kind.isEmpty() ? "without a kind" : kind));
                    }
                }
            } catch (IOException | RuntimeException e) {
                diagnostics.println("toolgate: protocol violation on stdin: " + e.getMessage());
                wire.send(new LogFrame("LOG", LogLevel.error, bounded("protocol violation on stdin: " + e.getMessage(), 8192)));
                synchronized (signal) {
                    violation = 65;
                    stopping = true;
                    signal.notifyAll();
                }
                brokerResponses.offer(new byte[0]);
            }
        }

        private void stop(long grace) {
            synchronized (signal) {
                graceMs = grace;
                stopping = true;
                signal.notifyAll();
            }
        }

        private void wake() {
            synchronized (signal) {
                signal.notifyAll();
            }
        }

        /** The check thread: waits for the timer, RECONCILE or RESUME; never runs two checks at once. */
        private void work() {
            long next = System.currentTimeMillis();
            while (true) {
                synchronized (signal) {
                    while (true) {
                        if (stopping) return;
                        long now = System.currentTimeMillis();
                        boolean due = pollRequested || (options.timer() && now >= next);
                        if (!paused && !halted && due) break;
                        try {
                            long wait = options.timer() && !paused && !halted ? Math.max(1, next - now) : 0;
                            signal.wait(wait);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    pollRequested = false;
                }
                try {
                    cycle();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                options.afterCheck().run();
                next = System.currentTimeMillis() + instance.interval();
            }
        }

        /** One check, its emits and, once they are all resolved, its checkpoint. */
        private void cycle() throws InterruptedException {
            broker.resetBudget();
            PollResult<?> result;
            try {
                result = (PollResult<?>) instance.check.invoke(instance.handler, instance.config, context());
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof InterruptedException) throw (InterruptedException) cause;
                // A check cut short by STOP (its broker channel closes) is not a health change.
                if (!stopping) health(TriggerHealthStatus.DEGRADED, "CheckFailed: " + describe(cause));
                return;
            } catch (IllegalAccessException | RuntimeException e) {
                if (!stopping) health(TriggerHealthStatus.DEGRADED, "CheckFailed: " + describe(e));
                return;
            }
            if (stopping) return;
            if (result == null) {
                health(TriggerHealthStatus.UNHEALTHY, "InvalidResult: check() returned null; return PollResult.none()");
                return;
            }
            // Validate the whole result first: a check is committed whole or not at all.
            List<TriggerEmitFrame> frames = new ArrayList<>();
            long seq = emitSeq;
            for (TriggerEvent<?> event : result.events()) {
                Object data = event.data();
                if (data.getClass() != instance.eventClass) {
                    health(TriggerHealthStatus.UNHEALTHY, "InvalidEvent: data is a " + data.getClass().getSimpleName() + ", not the declared " + instance.eventClass.getSimpleName());
                    return;
                }
                JsonNode tree;
                try {
                    tree = Converter.toJson(data);
                } catch (RuntimeException e) {
                    health(TriggerHealthStatus.UNHEALTHY, "InvalidEvent: the data cannot be encoded: " + describe(e));
                    return;
                }
                int bytes;
                try {
                    bytes = JSON.writeValueAsBytes(tree).length;
                } catch (IOException e) {
                    health(TriggerHealthStatus.UNHEALTHY, "InvalidEvent: the data cannot be encoded");
                    return;
                }
                if (bytes > instance.maxEventBytes()) {
                    health(TriggerHealthStatus.UNHEALTHY, "EventTooLarge: " + bytes + " bytes is over maxEventBytes " + instance.maxEventBytes());
                    return;
                }
                String invalid = instance.events.validate(tree);
                if (invalid != null) {
                    health(TriggerHealthStatus.UNHEALTHY, "InvalidEvent: " + invalid);
                    return;
                }
                frames.add(new TriggerEmitFrame("EMIT", ++seq, event.id(), event.subject(), event.time() == null ? null : event.time().toString(), tree, null));
            }
            String state = null;
            Optional<Checkpoint> next = result.checkpoint();
            if (next.isPresent()) {
                byte[] encoded;
                try {
                    encoded = JSON.writeValueAsBytes(next.get().values());
                } catch (IOException e) {
                    health(TriggerHealthStatus.UNHEALTHY, "InvalidCheckpoint: it cannot be encoded");
                    return;
                }
                if (encoded.length > instance.maxCheckpointBytes()) {
                    health(TriggerHealthStatus.UNHEALTHY, "CheckpointTooLarge: " + encoded.length + " bytes is over maxCheckpointBytes " + instance.maxCheckpointBytes());
                    return;
                }
                state = Base64.getEncoder().encodeToString(encoded);
            }

            // Emit, then wait until every emit of this check is resolved (plan 6.6).
            for (TriggerEmitFrame f : frames) {
                if (stopping) return;
                emitSeq = f.emitSeq();
                wire.send(f);
            }
            boolean terminal = false;
            for (TriggerEmitFrame f : frames) {
                int attempt = 0;
                while (true) {
                    TriggerEmitAckFrame ack = await(f.emitSeq());
                    if (ack == null) return; // stopping: the checkpoint stays before these events
                    switch (ack.status()) {
                        case LOCAL_DURABLE -> { }
                        case TERMINAL_REJECTION -> terminal = true;
                        case RETRYABLE_REJECTION -> {
                            long cap = Math.min(options.backoffCapMs(), options.backoffBaseMs() * (1L << Math.min(attempt++, 20)));
                            Thread.sleep(ThreadLocalRandom.current().nextLong(cap + 1));
                            if (stopping) return;
                            wire.send(f);
                            continue;
                        }
                    }
                    break;
                }
            }
            if (terminal && instance.halts()) {
                // plan 6.6 HALT: nothing after a terminal rejection is checkpointed; a fixed version replays it.
                synchronized (signal) {
                    halted = true;
                }
                health(TriggerHealthStatus.UNHEALTHY, "TerminalRejection: an event was rejected terminally under HALT; checking stopped");
                return;
            }
            if (state != null) {
                wire.send(new TriggerCheckpointFrame("CHECKPOINT", ++checkpointId, emitSeq, state));
                checkpoint = next.get();
            }
            health(TriggerHealthStatus.HEALTHY, null);
        }

        /** The ack of one emit, or null once the process is stopping. Retries reuse the emitSeq, so a stale ack is dropped. */
        private TriggerEmitAckFrame await(long seq) throws InterruptedException {
            synchronized (signal) {
                while (true) {
                    TriggerEmitAckFrame ack = acks.remove(seq);
                    if (ack != null) return ack;
                    if (stopping) return null;
                    signal.wait();
                }
            }
        }

        private void health(TriggerHealthStatus status, String reason) {
            String bounded = reason == null ? null : bounded(reason, MAX_REASON);
            if (status == health && java.util.Objects.equals(bounded, healthReason)) return;
            if (status == TriggerHealthStatus.HEALTHY && health == TriggerHealthStatus.HEALTHY) return;
            health = status;
            healthReason = bounded;
            wire.health(status, bounded);
        }

        private PollContext context() {
            Checkpoint current = checkpoint;
            DeviceFiles files = instance.client && instance.files ? StdioBroker.readOnly(broker) : refusedFiles();
            ToolLogger log = new ToolLogger() {
                public void debug(String m) { log(LogLevel.debug, m); }
                public void info(String m) { log(LogLevel.info, m); }
                public void warn(String m) { log(LogLevel.warn, m); }
                public void error(String m) { log(LogLevel.error, m); }
            };
            return new PollContext() {
                public String triggerId() { return instance.start.triggerId(); }
                public String instanceId() { return instance.start.instanceId(); }
                public long leaseEpoch() { return instance.start.leaseEpoch(); }
                public long configRevision() { return instance.start.configRevision(); }
                public Map<String, Object> globals() { return instance.globals; }
                public Optional<Checkpoint> checkpoint() { return Optional.ofNullable(current); }
                public String startedAt() { return startedAt; }
                public DeviceFiles files() { return files; }
                public ToolLogger log() { return log; }
                public boolean stopping() { return stopping; }
            };
        }

        private void log(LogLevel level, String message) {
            if (message == null || message.isEmpty()) return;
            wire.send(new LogFrame("LOG", level, bounded(message, 8192)));
        }

        private static DeviceFiles refusedFiles() {
            return new DeviceFiles() {
                private PlatformError refused() {
                    return new PlatformError(PlatformError.Code.FILES_REFUSED, "Only a @ClientTrigger declared with @DeviceFileAccess, on a device, reads the device files area");
                }
                public List<String> list() { throw refused(); }
                public String readText(String path) { throw refused(); }
                public Info info(String path) { throw refused(); }
                public void writeText(String path, String text) { throw refused(); }
                public void appendText(String path, String text) { throw refused(); }
                public void mkdir(String path) { throw refused(); }
                public void rename(String path, String destination) { throw refused(); }
            };
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    static String bounded(String s, int max) {
        String flat = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (flat.isEmpty()) flat = "-";
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /** One line of at most {@link #MAX_LINE} bytes, or null at end of input. */
    static byte[] line(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b = in.read();
        if (b == -1) return null;
        for (; b != '\n'; b = in.read()) {
            if (b == -1) throw new IOException("the last line has no newline");
            if (buffer.size() >= MAX_LINE) throw new IOException("a line is longer than " + MAX_LINE + " bytes");
            buffer.write(b);
        }
        return buffer.toByteArray();
    }

    /** stdout: one frame per line, written whole. */
    static final class Wire {
        private final OutputStream out;

        Wire(OutputStream out) {
            this.out = out;
        }

        void send(Object frame) {
            try {
                byte[] bytes = JSON.writeValueAsBytes(frame);
                synchronized (out) {
                    out.write(bytes);
                    out.write('\n');
                    out.flush();
                }
            } catch (IOException e) {
                // The supervisor is gone; the reader sees the end of input and stops.
            }
        }

        void frame(ObjectNode frame) {
            send(frame);
        }

        void health(TriggerHealthStatus status, String reason) {
            send(new TriggerHealthFrame("HEALTH", status, reason == null ? null : bounded(reason, MAX_REASON)));
        }
    }
}
