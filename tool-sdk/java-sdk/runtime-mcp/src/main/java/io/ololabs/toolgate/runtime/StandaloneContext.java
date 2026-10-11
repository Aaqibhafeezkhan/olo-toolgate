// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.Tasks;
import io.ololabs.toolgate.sdk.context.CancellationToken;
import io.ololabs.toolgate.sdk.context.HttpClient;
import io.ololabs.toolgate.sdk.context.InputRequest;
import io.ololabs.toolgate.sdk.context.Invocation;
import io.ololabs.toolgate.sdk.context.InvocationMode;
import io.ololabs.toolgate.sdk.context.Jwt;
import io.ololabs.toolgate.sdk.context.TcpConnector;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.context.ToolLogger;
import io.ololabs.toolgate.sdk.context.Values;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The ToolContext of one standalone invocation. */
final class StandaloneContext implements ToolContext, CancellationToken, Invocation, ToolLogger {
    static final int LOG_ENTRY_BYTES = 4 * 1024;
    static final long LOG_INVOCATION_BYTES = 256 * 1024;

    private final DeclaredValues inputs;
    private final DeclaredValues globals;
    private final DeclaredValues secrets;
    private final DeclaredValues claims;
    private final String invocationId;
    private final String toolId;
    private final Instant deadline;
    private final Redactor redactor;
    private final PrintStream diagnostics;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> onCancel = new CopyOnWriteArrayList<>();
    private final AtomicLong logged = new AtomicLong();
    private final AtomicInteger inputRequests = new AtomicInteger();
    private double progressed = -1;
    private volatile Tasks tasks;
    private volatile JsonNode progressToken;
    private volatile java.util.function.Consumer<ObjectNode> notifications;
    private volatile String taskId;
    private volatile ObjectNode responses;
    private volatile ObjectNode pendingInput;
    volatile Thread worker;
    private HttpClient http;

    StandaloneContext(DeclaredValues inputs, DeclaredValues globals, DeclaredValues secrets, DeclaredValues claims,
            String invocationId, String toolId, Instant deadline, Redactor redactor, PrintStream diagnostics) {
        this.inputs = inputs;
        this.globals = globals;
        this.secrets = secrets;
        this.claims = claims;
        this.invocationId = invocationId;
        this.toolId = toolId;
        this.deadline = deadline;
        this.redactor = redactor;
        this.diagnostics = diagnostics;
    }

    @Override public String getStringValue(String name) { return inputs.getStringValue(name); }
    @Override public int getIntValue(String name) { return inputs.getIntValue(name); }
    @Override public long getLongValue(String name) { return inputs.getLongValue(name); }
    @Override public double getNumberValue(String name) { return inputs.getNumberValue(name); }
    @Override public boolean getBooleanValue(String name) { return inputs.getBooleanValue(name); }
    @Override public LocalDate getDateValue(String name) { return inputs.getDateValue(name); }
    @Override public OffsetDateTime getDateTimeValue(String name) { return inputs.getDateTimeValue(name); }
    @Override public Duration getDurationValue(String name) { return inputs.getDurationValue(name); }
    @Override public <T> T getObjectValue(String name, Class<T> type) { return inputs.getObjectValue(name, type); }
    @Override public <T> List<T> getListValue(String name, Class<T> type) { return inputs.getListValue(name, type); }
    @Override public byte[] getBinaryValue(String name) { return inputs.getBinaryValue(name); }

    @Override public Values global() { return globals; }
    @Override public Values secret() { return secrets; }
    @Override public Jwt jwt() { return claims; }

    @Override
    public HttpClient http() {
        if (http == null) throw new ToolConfigurationError("The routes HTTP client is not available here");
        return http;
    }

    @Override
    public TcpConnector tcp() {
        throw new ToolConfigurationError("The TCP connector is not in this runtime yet");
    }

    @Override public Invocation invocation() { return this; }

    /** Gives this invocation its routes HTTP client. */
    void attachHttp(HttpClient http) {
        this.http = http;
    }

    /** Makes this invocation a task's: progress and input requests go to it. */
    void attachTask(Tasks tasks, String taskId) {
        this.tasks = tasks;
        this.taskId = taskId;
    }

    /** Makes a sync call answer {@code requestInput()} by MRTR from this round's input responses (adapter A3). */
    void attachInputResponses(ObjectNode responses) {
        this.responses = responses;
    }

    /**
     * The {@code InputRequiredResult} this round ends with, or null. It is recorded before {@link InputNeeded} is thrown,
     * so a handler that catches the exception still ends the round asking for input.
     */
    ObjectNode pendingInput() {
        return pendingInput;
    }

    /** Ends a sync round that needs an answer the client has not sent yet. */
    static final class InputNeeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InputNeeded() {
            super("input required", null, false, false);
        }
    }

    /** Makes a sync call report progress as {@code notifications/progress} for the request's progress token. */
    void attachProgress(JsonNode token, java.util.function.Consumer<ObjectNode> sink) {
        this.progressToken = token;
        this.notifications = sink;
    }

    /**
     * Bounded and monotonic (case {@code context/progress}): a fraction outside 0..1, or lower than the last one, is
     * ignored. A task reports it as its status message; a sync call as {@code notifications/progress} when the request
     * carried a progress token, and nowhere otherwise.
     */
    @Override
    public synchronized void progress(double fraction, String message) {
        if (Double.isNaN(fraction) || fraction < 0 || fraction > 1 || fraction < progressed) return;
        double bounded = fraction;
        progressed = bounded;
        String safe = message == null || message.isBlank() ? null : Redactor.cap(redactor.apply(message), 256);
        if (tasks != null) {
            tasks.progress(taskId, Math.round(bounded * 100) + "%" + (safe == null ? "" : " " + safe));
        } else if (progressToken != null && notifications != null) {
            ObjectNode frame = JsonNodeFactory.instance.objectNode();
            frame.put("jsonrpc", "2.0");
            frame.put("method", "notifications/progress");
            ObjectNode params = frame.putObject("params");
            params.set("progressToken", progressToken);
            params.put("progress", Math.round(bounded * 100));
            params.put("total", 100);
            if (safe != null) params.put("message", safe);
            try {
                notifications.accept(frame);
            } catch (RuntimeException e) {
                // the client went away; the tool carries on to its own end
            }
        }
    }

    @Override public boolean isCancelled() { return cancelled.get(); }
    @Override public CancellationToken cancellation() { return this; }

    /**
     * Asks for one form elicitation. In a task it moves the task to {@code input_required} and waits for the answer
     * from {@code tasks/update}. In a sync call it is MRTR: the handler runs again from the start on each round, and
     * the n-th call answers from the round's input response {@code input-n}, or ends the round with an
     * {@code InputRequiredResult} asking for it.
     */
    @Override
    public <T> T requestInput(InputRequest<T> request) {
        String key = "input-" + inputRequests.incrementAndGet();
        ObjectNode elicitation = JsonNodeFactory.instance.objectNode();
        elicitation.put("method", "elicitation/create");
        ObjectNode params = elicitation.putObject("params");
        params.put("mode", "form");
        params.put("message", redactor.apply(request.message()));
        JsonNode schema = Converter.toJson(request.requestedSchema());
        params.set("requestedSchema", schema);
        Tasks t = tasks;
        JsonNode answer;
        if (t == null) {
            ObjectNode round = responses;
            answer = round == null ? null : round.get(key);
            if (answer == null) {
                ObjectNode result = JsonNodeFactory.instance.objectNode();
                result.put("resultType", "input_required");
                result.putObject("inputRequests").set(key, elicitation);
                pendingInput = result;
                throw new InputNeeded();
            }
        } else {
            ObjectNode answers;
            try {
                answers = t.awaitInput(taskId, JsonNodeFactory.instance.objectNode().set(key, elicitation), deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlatformError(PlatformError.Code.CANCELLED, "The invocation was cancelled");
            }
            if (answers == null) {
                if (isCancelled() || t.cancelRequested(taskId)) throw new PlatformError(PlatformError.Code.CANCELLED, "The invocation was cancelled");
                throw new PlatformError(PlatformError.Code.DEADLINE_EXCEEDED, "No input arrived before the deadline");
            }
            answer = answers.path(key);
        }
        if (!"accept".equals(answer.path("action").asText())) {
            throw new PlatformError(PlatformError.Code.CANCELLED, "The input request was " + (answer.path("action").asText("declined").equals("cancel") ? "cancelled" : "declined"));
        }
        JsonNode content = answer.path("content");
        String invalid = new SchemaValidator(schema).validate(content);
        if (invalid != null) throw new ToolInputError("The input does not match the requested schema: " + invalid.substring(1));
        try {
            return request.answerType().cast(Converter.fromJson(content, request.answerType()));
        } catch (Converter.ConversionException e) {
            throw new ToolInputError("The input does not match the requested type: " + e.getMessage());
        }
    }

    @Override public ToolLogger log() { return this; }

    // CancellationToken
    @Override public boolean isCancellationRequested() { return cancelled.get(); }

    @Override
    public void throwIfCancellationRequested() {
        if (cancelled.get()) throw new PlatformError(PlatformError.Code.CANCELLED, "The invocation was cancelled");
    }

    @Override
    public void onCancel(Runnable callback) {
        onCancel.add(callback);
        if (cancelled.get()) callback.run();
    }

    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable r : onCancel) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    // a callback failure does not stop cancellation
                }
            }
            Thread t = worker;
            if (t != null) t.interrupt();
        }
    }

    // Invocation
    @Override public String invocationId() { return invocationId; }
    @Override public String toolId() { return toolId; }
    @Override public Optional<String> agentId() { return Optional.empty(); }
    @Override public Optional<String> userId() { return Optional.empty(); }
    @Override public Optional<Long> catalogRevision() { return Optional.empty(); }
    @Override public Instant deadline() { return deadline; }
    @Override public Optional<String> businessKey() { return Optional.empty(); }
    @Override public int attempt() { return 1; }
    @Override public InvocationMode mode() { return InvocationMode.STANDALONE; }

    // ToolLogger: redacted, 4 KiB per entry, 256 KiB per invocation, kept as local diagnostics
    @Override public void debug(String message) { write("debug", message); }
    @Override public void info(String message) { write("info", message); }
    @Override public void warn(String message) { write("warn", message); }
    @Override public void error(String message) { write("error", message); }

    private void write(String level, String message) {
        String entry = Redactor.cap(redactor.apply(String.valueOf(message)), LOG_ENTRY_BYTES);
        long size = entry.getBytes(StandardCharsets.UTF_8).length;
        if (logged.addAndGet(size) > LOG_INVOCATION_BYTES) return;
        diagnostics.println("[" + level + "] " + toolId + " " + invocationId + ": " + entry);
    }
}
