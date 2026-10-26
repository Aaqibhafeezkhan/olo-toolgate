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
import java.util.Map;
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
    private volatile java.util.function.Consumer<ObjectNode> responseFrames;
    private InvocationMode invocationMode = InvocationMode.STANDALONE;
    private volatile String taskId;
    private volatile ObjectNode responses;
    private io.ololabs.toolgate.sdk.context.DeviceFiles files;
    private volatile ObjectNode pendingInput;
    volatile Thread worker;
    private HttpClient http;
    private TcpConnector tcp;
    private volatile String businessKey;

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
        if (tcp == null) throw new ToolConfigurationError("The TCP connector is not available here");
        return tcp;
    }

    @Override
    public io.ololabs.toolgate.sdk.context.DeviceFiles files() {
        if (files == null) throw new ToolConfigurationError("The device files area is available only to a ClientTool declared with @DeviceFileAccess on a device");
        return files;
    }

    /** Attaches the device files area this tool declared. */
    void attachFiles(io.ololabs.toolgate.sdk.context.DeviceFiles files) {
        this.files = files;
    }

    /** Attaches the TCP connector for the tool's declared destinations. */
    void attachTcp(TcpConnector tcp) {
        this.tcp = tcp;
    }

    @Override public Invocation invocation() { return this; }

    /** Records the business key this invocation reserved. */
    void attachBusinessKey(String key) {
        this.businessKey = key;
    }

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

    /** The governed supervisor receives the same canonical frames for sync calls and tasks. */
    void attachResponses(java.util.function.Consumer<ObjectNode> sink) { responseFrames = sink; }
    void attachMode(InvocationMode mode) { invocationMode = mode; }
    /** The principal the Tool Host verified in the permit; absent in standalone runs. */
    void attachPrincipal(JsonNode verified) { principal = verified != null && verified.isObject() ? verified : null; }
    private JsonNode principal;
    /** The executor's correlation context and trace parent (ADR 031); governed only, never read for any decision. */
    void attachExecution(JsonNode context, String traceparent) {
        execution = context != null && context.isObject() ? context : null;
        this.traceparent = traceparent;
    }
    private JsonNode execution;
    private String traceparent;

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
        var sink = responseFrames;
        if (sink != null) {
            ObjectNode frame = JsonNodeFactory.instance.objectNode().put("kind", "PROGRESS").put("fraction", bounded);
            if (safe != null && !safe.isEmpty()) frame.put("message", safe);
            sink.accept(frame);
        }
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
    @Override public Optional<String> agentId() { return text("agentId"); }
    @Override public Optional<String> userId() { return text("userId"); }
    @Override public Optional<Long> catalogRevision() { return Optional.empty(); }
    @Override public Instant deadline() { return deadline; }
    @Override public Optional<String> businessKey() { return Optional.ofNullable(businessKey); }
    @Override public int attempt() {
        JsonNode attempt = execution == null ? null : execution.get("attemptNumber");
        return attempt != null && attempt.canConvertToInt() && attempt.asInt() >= 1 ? attempt.asInt() : 1;
    }
    @Override public Optional<String> executionId() { return executionText("executionId"); }
    @Override public Optional<String> toolCallId() { return executionText("toolCallId"); }
    @Override public Optional<String> sessionId() { return executionText("sessionId"); }
    @Override public Optional<String> agentRunId() { return executionText("agentRunId"); }
    @Override public Optional<String> traceparent() { return Optional.ofNullable(traceparent); }
    @Override public Map<String, String> traceHeaders() {
        if (traceparent == null) return Map.of();
        return executionText("tracestate").map(state -> Map.of("traceparent", traceparent, "tracestate", state))
                .orElse(Map.of("traceparent", traceparent));
    }
    private Optional<String> executionText(String field) {
        return execution != null && execution.path(field).isTextual() ? Optional.of(execution.get(field).asText()) : Optional.empty();
    }
    @Override public InvocationMode mode() { return invocationMode; }
    @Override public Optional<String> tenantId() { return text("tenantId"); }
    @Override public Optional<String> operation() { return text("operation"); }
    @Override public List<Invocation.Resource> resources() {
        if (principal == null) return List.of();
        var out = new java.util.ArrayList<Invocation.Resource>();
        principal.path("resources").forEach(r -> { if (r.path("kind").isTextual() && r.path("locator").isTextual()) out.add(new Invocation.Resource(r.get("kind").asText(), r.get("locator").asText())); });
        return List.copyOf(out);
    }
    @Override public List<String> delegationChain() {
        if (principal == null) return List.of();
        var out = new java.util.ArrayList<String>();
        principal.path("chain").forEach(agent -> { if (agent.isTextual()) out.add(agent.asText()); });
        return List.copyOf(out);
    }
    private Optional<String> text(String field) {
        return principal != null && principal.path(field).isTextual() ? Optional.of(principal.get(field).asText()) : Optional.empty();
    }

    // ToolLogger: redacted, 4 KiB per entry, 256 KiB per invocation, kept as local diagnostics
    @Override public void debug(String message) { write("debug", message); }
    @Override public void info(String message) { write("info", message); }
    @Override public void warn(String message) { write("warn", message); }
    @Override public void error(String message) { write("error", message); }

    private void write(String level, String message) {
        String entry = Redactor.cap(redactor.apply(String.valueOf(message)), LOG_ENTRY_BYTES);
        long size = entry.getBytes(StandardCharsets.UTF_8).length;
        if (logged.addAndGet(size) > LOG_INVOCATION_BYTES) return;
        var sink = responseFrames;
        if (sink != null && !entry.isEmpty()) sink.accept(JsonNodeFactory.instance.objectNode().put("kind", "LOG").put("level", level).put("message", entry));
        diagnostics.println("[" + level + "] " + toolId + " " + invocationId + ": " + entry);
    }
}
