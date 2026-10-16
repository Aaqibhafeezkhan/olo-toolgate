// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.ProtocolCore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Governed mode: one invocation under a Tool Host, over tool protocol v2 (platform-requirements.md §13.3, protocol.schema.json). The
 * host writes one {@code ToolInput} line to stdin; the runtime emits canonical response frames, ending with one {@code ResultFrame}, on stdout and
 * exits. Globals and claims come from the input, secrets are redeemed by handle, and every route call goes to the
 * host's broker. The descriptors, validation and error handling are the standalone runtime's, so a tool behaves the
 * same in both modes (runtime-modes.md §3).
 */
final class Governed {
    /** Maximum size of the input line (platform-requirements.md §13.5: 1 MiB of arguments, plus the envelope). */
    static final int MAX_INPUT = 2 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Governed() {}

    /** Runs the invocation on {@code in} and returns the process exit code. */
    static int run(ClassLoader loader, Map<String, String> env, InputStream in, OutputStream out, PrintStream diagnostics) throws IOException {
        JsonNode input;
        try {
            input = JSON.readTree(line(in));
        } catch (IOException e) {
            write(out, failure("INVALID_INPUT", "The tool input is not one JSON line: " + e.getMessage()));
            return 65;
        }
        if (input == null || input.path("protocolVersion").asInt() != 2) {
            write(out, failure("UNSUPPORTED_PROTOCOL", "This runtime speaks tool protocol v2"));
            return 65;
        }
        if (!input.path("invocationId").isTextual() || !input.path("invocationId").asText().matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}")
                || !input.path("deadlineUnixMs").isIntegralNumber() || !input.path("deadlineUnixMs").canConvertToLong()
                || input.path("deadlineUnixMs").asLong() <= 0 || input.path("deadlineUnixMs").asLong() > 9007199254740991L) {
            write(out, failure("INVALID_INPUT", "The tool input must bind an invocation and deadline"));
            return 65;
        }
        if (input.path("deadlineUnixMs").asLong() <= System.currentTimeMillis()) {
            write(out, failure("DEADLINE_EXCEEDED", "The invocation deadline passed before the tool started"));
            return 0;
        }
        // Everything after the ToolInput line travels over the same stdio channel: frames out, broker answers in.
        java.util.function.Consumer<ObjectNode> frames = frame -> {
            try { write(out, frame); }
            catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
        };
        StdioBroker broker = new StdioBroker(in, frames);
        Map<String, String> handles = new HashMap<>();
        input.path("secretHandles").forEach(h -> handles.put(h.path("name").asText(), h.path("handle").asText()));
        ObjectNode values = JSON.createObjectNode();
        values.set("globals", input.path("globals").isObject() ? input.get("globals") : JSON.createObjectNode());
        values.set("claims", input.path("claims").isObject() ? input.get("claims") : JSON.createObjectNode());
        Config config = Config.governed(values, name -> {
            String handle = handles.get(name);
            return handle == null ? null : broker.redeem(handle);
        });
        config.governedInvocationId = input.path("invocationId").asText();
        config.governedDeadline = java.time.Instant.ofEpochMilli(input.path("deadlineUnixMs").asLong());
        config.responses = frames;
        try {
            config.location = io.ololabs.toolgate.sdk.ExecutionLocation.valueOf(env.getOrDefault("TOOLGATE_EXECUTION_TARGET", "SERVER"));
        } catch (IllegalArgumentException invalid) {
            write(out, failure("INVALID_TARGET", "Execution target must be SERVER or CLIENT"));
            return 78;
        }
        // A device brokers its files area; a Tool Host refuses those operations, as a device refuses routes and secrets.
        if (config.location == io.ololabs.toolgate.sdk.ExecutionLocation.CLIENT) config.deviceFiles = broker;
        TestExchanges routes = broker;
        StandaloneRuntime runtime;
        try {
            runtime = StandaloneRuntime.startGoverned(loader, config, routes, Path.of(""), diagnostics);
        } catch (StartupRefused e) {
            write(out, failure(e.code(), e.getMessage()));
            return 78;
        }
        String toolId = input.path("toolId").asText();
        String mcpName = runtime.mcpNameOf(toolId);
        if (mcpName == null) {
            write(out, failure("TOOL_NOT_FOUND", "The artifact has no tool " + toolId));
            return 65;
        }
        ProtocolCore core = new ProtocolCore(runtime);
        ProtocolCore.Connection connection = new ProtocolCore.Connection("governed");
        ObjectNode params = params();
        params.put("name", mcpName);
        params.set("arguments", input.path("arguments").isObject() ? input.get("arguments") : JSON.createObjectNode());
        JsonNode response = core.handle(request("tools/call", params), connection);
        if (response.has("error")) {
            write(out, failure("INVALID_ARGUMENT", response.get("error").path("message").asText()));
            return 0;
        }
        JsonNode result = response.path("result");
        if (result.path("resultType").asText().equals("task")) {
            result = awaitTask(core, connection, result.path("taskId").asText(), input.path("deadlineUnixMs").asLong(Long.MAX_VALUE));
        }
        write(out, frame(result));
        return 0;
    }

    /** One line of at most {@link #MAX_INPUT} bytes. */
    private static byte[] line(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1 && b != '\n'; ) {
            if (buffer.size() >= MAX_INPUT) throw new IOException("the tool input is larger than " + MAX_INPUT + " bytes");
            buffer.write(b);
        }
        return buffer.toByteArray();
    }

    private static ObjectNode params() {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolCore.VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions").putObject("io.modelcontextprotocol/tasks");
        return params;
    }

    private static ObjectNode request(String method, ObjectNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0").put("id", 1).put("method", method);
        message.set("params", params);
        return message;
    }

    /** Waits for an async tool's task, within the invocation deadline; the host's ledger keeps the task, not this process. */
    private static JsonNode awaitTask(ProtocolCore core, ProtocolCore.Connection connection, String taskId, long deadlineUnixMs) {
        while (true) {
            ObjectNode params = params();
            params.put("taskId", taskId);
            JsonNode task = core.handle(request("tasks/get", params), connection).path("result");
            switch (task.path("status").asText()) {
                case "completed" -> {
                    return task.path("result");
                }
                case "failed" -> {
                    JsonNode data = task.path("error").path("data");
                    return failureResult(data.path("code").asText("INTERNAL_ERROR"), data.path("message").asText(task.path("error").path("message").asText()),
                            data.path("retryable").asBoolean());
                }
                case "cancelled" -> {
                    return failureResult("CANCELLED", "The task was cancelled", false);
                }
                default -> {
                    if (System.currentTimeMillis() > deadlineUnixMs) return failureResult("DEADLINE_EXCEEDED", "The invocation deadline passed", false);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return failureResult("CANCELLED", "The invocation was interrupted", false);
                    }
                }
            }
        }
    }

    private static ObjectNode failureResult(String code, String message, boolean retryable) {
        ObjectNode result = JSON.createObjectNode().put("isError", true);
        result.putArray("content").addObject().put("type", "text").put("text", message);
        result.putObject("structuredContent").putObject("error").put("code", code).put("message", message).put("retryable", retryable);
        return result;
    }

    /** The {@code ResultFrame} for an MCP {@code CallToolResult}. */
    static ObjectNode frame(JsonNode result) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("kind", "RESULT").put("resultType", "complete");
        frame.set("content", result.path("content").isArray() ? result.get("content") : JSON.createArrayNode());
        JsonNode structured = result.get("structuredContent");
        boolean isError = result.path("isError").asBoolean();
        if (structured != null && structured.isObject()) frame.set("structuredContent", structured);
        frame.put("isError", isError);
        if (isError) {
            JsonNode error = structured == null ? null : structured.get("error");
            ObjectNode body = frame.putObject("error");
            body.put("code", error == null ? "INTERNAL_ERROR" : error.path("code").asText("INTERNAL_ERROR"));
            body.put("message", error == null ? "The tool failed" : error.path("message").asText(""));
            body.put("retryable", error != null && error.path("retryable").asBoolean());
            if (error != null && error.path("data").isObject()) body.set("data", error.get("data"));
        }
        return frame;
    }

    private static ObjectNode failure(String code, String message) {
        return frame(failureResult(code, message, false));
    }

    private static void write(OutputStream out, ObjectNode frame) throws IOException {
        synchronized (out) {
            out.write(JSON.writeValueAsBytes(frame));
            out.write('\n');
            out.flush();
        }
    }
}
