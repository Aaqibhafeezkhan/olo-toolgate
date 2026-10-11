// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adapter A2: the Tasks extension {@code io.modelcontextprotocol/tasks} over a {@link TaskStore} (protocol-layer.md
 * §3, plan.md §12.4 and §12.6). The runtime creates tasks and reports their progress, input requests and outcome;
 * the protocol core routes {@code tasks/get}, {@code tasks/update} and {@code tasks/cancel} here.
 */
public final class Tasks implements AutoCloseable {
    public static final long DEFAULT_TTL_MS = 3_600_000;
    public static final long DEFAULT_POLL_INTERVAL_MS = 1_000;
    /** Polls extend retention, never past this long after creation. */
    public static final long MAX_RETENTION_MS = 86_400_000;
    /** A {@code cancelIfAbandoned} task with no poll for this many poll intervals is cancelled (execution.md §6). */
    public static final int ABANDONED_AFTER_POLLS = 3;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final TaskStore store;
    private final long ttlMs;
    private final long pollIntervalMs;
    private final Map<String, Runnable> cancelHandlers = new ConcurrentHashMap<>();
    private final Object changed = new Object();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "toolgate-tasks");
        t.setDaemon(true);
        return t;
    });

    public Tasks(TaskStore store, long ttlMs, long pollIntervalMs) {
        if (ttlMs <= 0 || pollIntervalMs <= 0) throw new IllegalArgumentException("ttlMs and pollIntervalMs must be positive");
        this.store = store;
        this.ttlMs = ttlMs;
        this.pollIntervalMs = pollIntervalMs;
        long sweep = Math.max(10, pollIntervalMs / 2);
        sweeper.scheduleWithFixedDelay(this::sweep, sweep, sweep, TimeUnit.MILLISECONDS);
    }

    // ---- runtime side ----

    /**
     * Creates a working task owned by {@code owner}. {@code onCancel} runs once when cancellation is requested by
     * {@code tasks/cancel}, by abandonment or by purge.
     */
    public TaskRecord create(String owner, boolean cancelIfAbandoned, String statusMessage, Runnable onCancel) {
        byte[] id = new byte[16];
        random.nextBytes(id);
        Instant now = Instant.now();
        TaskRecord t = new TaskRecord();
        t.taskId = HexFormat.of().formatHex(id);
        t.owner = owner;
        t.statusMessage = statusMessage;
        t.createdAt = now;
        t.lastUpdatedAt = now;
        t.ttlMs = ttlMs;
        t.pollIntervalMs = pollIntervalMs;
        t.expiresAt = now.plusMillis(ttlMs);
        t.lastPolledAt = now;
        t.cancelIfAbandoned = cancelIfAbandoned;
        cancelHandlers.put(t.taskId, onCancel);
        store.create(t);
        return t.copy();
    }

    /** The {@code CreateTaskResult} for a new task: flat, {@code resultType: "task"}. */
    public static ObjectNode createResult(TaskRecord t) {
        ObjectNode r = view(t);
        r.put("resultType", "task");
        return r;
    }

    /** Updates the status message of a working task. */
    public void progress(String taskId, String message) {
        change(taskId, t -> {
            if (t.status == TaskStatus.WORKING) t.statusMessage = message;
        });
    }

    /** Ends the task with the tool result; a task whose cancellation was requested ends cancelled instead. */
    public void complete(String taskId, ObjectNode result) {
        end(taskId, t -> {
            if (t.cancelRequested) {
                t.status = TaskStatus.CANCELLED;
            } else {
                t.status = TaskStatus.COMPLETED;
                t.result = result;
            }
        });
    }

    /** Ends the task with a protocol-level error. */
    public void fail(String taskId, int code, String message) {
        end(taskId, t -> {
            t.status = TaskStatus.FAILED;
            t.error = JSON.objectNode().put("code", code).put("message", message);
        });
    }

    /** Ends the task cancelled. */
    public void cancelled(String taskId) {
        end(taskId, t -> t.status = TaskStatus.CANCELLED);
    }

    /** Whether cancellation of the task was requested, or the task is gone. */
    public boolean cancelRequested(String taskId) {
        return store.transition(taskId, t -> {}).map(t -> t.cancelRequested).orElse(true);
    }

    /**
     * Moves the task to {@code input_required} with {@code requests} (key to {@code {method, params}}) and blocks until
     * every key is answered through {@code tasks/update}. Returns the answers by key, or null when the deadline passes,
     * cancellation is requested or the task is gone; the task is working again either way.
     */
    public ObjectNode awaitInput(String taskId, ObjectNode requests, Instant deadline) throws InterruptedException {
        change(taskId, t -> {
            t.inputRequests.setAll(requests);
            t.status = TaskStatus.INPUT_REQUIRED;
        });
        try {
            synchronized (changed) {
                while (true) {
                    Optional<TaskRecord> now = store.transition(taskId, t -> {});
                    if (now.isEmpty() || now.get().cancelRequested) return null;
                    boolean answered = true;
                    Iterator<String> keys = requests.fieldNames();
                    while (keys.hasNext()) answered &= now.get().inputResponses.has(keys.next());
                    if (answered) {
                        ObjectNode answers = JSON.objectNode();
                        change(taskId, t -> {
                            Iterator<String> k = requests.fieldNames();
                            while (k.hasNext()) {
                                String key = k.next();
                                answers.set(key, t.inputResponses.remove(key));
                            }
                        });
                        return answers;
                    }
                    long left = deadline.toEpochMilli() - System.currentTimeMillis();
                    if (left <= 0) return null;
                    changed.wait(Math.min(left, 200));
                }
            }
        } finally {
            change(taskId, t -> {
                Iterator<String> k = requests.fieldNames();
                while (k.hasNext()) {
                    String key = k.next();
                    t.inputRequests.remove(key);
                    t.inputResponses.remove(key);
                }
                if (t.status == TaskStatus.INPUT_REQUIRED && t.inputRequests.isEmpty()) t.status = TaskStatus.WORKING;
            });
        }
    }

    // ---- protocol side ----

    /** {@code tasks/get}. */
    public ObjectNode get(JsonNode params, RequestMeta meta) {
        String taskId = taskId(params);
        TaskRecord t = store.get(taskId, meta.owner()).orElseThrow(Tasks::notFound);
        Instant now = Instant.now();
        store.transition(taskId, x -> {
            x.lastPolledAt = now;
            Instant extended = now.plusMillis(pollIntervalMs * ABANDONED_AFTER_POLLS);
            Instant cap = x.createdAt.plusMillis(MAX_RETENTION_MS);
            if (extended.isAfter(x.expiresAt)) x.expiresAt = extended.isAfter(cap) ? cap : extended;
        });
        ObjectNode r = view(t);
        switch (t.status) {
            case INPUT_REQUIRED -> r.set("inputRequests", t.inputRequests);
            case COMPLETED -> r.set("result", t.result);
            case FAILED -> r.set("error", t.error);
            default -> { }
        }
        return r;
    }

    /** {@code tasks/update}: records answers to pending input requests; unknown keys are ignored. */
    public ObjectNode update(JsonNode params, RequestMeta meta) {
        String taskId = taskId(params);
        JsonNode responses = params.get("inputResponses");
        if (responses == null || !responses.isObject()) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "inputResponses must be an object");
        store.submitInput(taskId, meta.owner(), (ObjectNode) responses).orElseThrow(Tasks::notFound);
        signal();
        return JSON.objectNode();
    }

    /** {@code tasks/cancel}: requests cooperative cancellation; a terminal task is acknowledged unchanged. */
    public ObjectNode cancel(JsonNode params, RequestMeta meta) {
        String taskId = taskId(params);
        TaskRecord t = store.requestCancel(taskId, meta.owner()).orElseThrow(Tasks::notFound);
        if (t.cancelRequested) runCancel(taskId);
        return JSON.objectNode();
    }

    /** Purges expired tasks and cancels abandoned ones. Runs on a timer; public for tests. */
    public void sweep() {
        Instant now = Instant.now();
        for (TaskRecord t : store.purgeExpired(now)) {
            if (!t.status.terminal()) runCancel(t.taskId);
            cancelHandlers.remove(t.taskId);
        }
        for (TaskRecord t : store.all()) {
            if (t.cancelIfAbandoned && !t.status.terminal() && !t.cancelRequested
                    && now.isAfter(t.lastPolledAt.plusMillis(t.pollIntervalMs * ABANDONED_AFTER_POLLS))) {
                change(t.taskId, x -> x.cancelRequested = true);
                runCancel(t.taskId);
            }
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }

    private static ObjectNode view(TaskRecord t) {
        ObjectNode r = JSON.objectNode();
        r.put("taskId", t.taskId);
        r.put("status", t.status.wire());
        if (t.statusMessage != null) r.put("statusMessage", t.statusMessage);
        r.put("createdAt", t.createdAt.toString());
        r.put("lastUpdatedAt", t.lastUpdatedAt.toString());
        r.put("ttlMs", t.ttlMs);
        r.put("pollIntervalMs", t.pollIntervalMs);
        return r;
    }

    private void change(String taskId, java.util.function.Consumer<TaskRecord> change) {
        store.transition(taskId, t -> {
            change.accept(t);
            t.lastUpdatedAt = Instant.now();
        });
        signal();
    }

    private void end(String taskId, java.util.function.Consumer<TaskRecord> change) {
        change(taskId, t -> {
            if (t.status.terminal()) return;
            change.accept(t);
            t.inputRequests.removeAll();
            t.inputResponses.removeAll();
        });
        cancelHandlers.remove(taskId);
    }

    private void runCancel(String taskId) {
        Runnable handler = cancelHandlers.remove(taskId);
        if (handler != null) handler.run();
        signal();
    }

    private void signal() {
        synchronized (changed) {
            changed.notifyAll();
        }
    }

    private static String taskId(JsonNode params) {
        JsonNode id = params.get("taskId");
        if (id == null || !id.isTextual() || id.asText().isEmpty() || id.asText().length() > 128) {
            throw new ProtocolError(ProtocolError.INVALID_PARAMS, "taskId is required");
        }
        return id.asText();
    }

    private static ProtocolError notFound() {
        return new ProtocolError(ProtocolError.INVALID_PARAMS, "Task not found");
    }
}
