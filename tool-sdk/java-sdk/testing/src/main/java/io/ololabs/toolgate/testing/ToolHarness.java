// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.runtime.StandaloneRuntime;
import io.ololabs.toolgate.runtime.TestExchanges;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the tools on the class path in test mode (runtime-modes.md §4, spec/authoring/testing.md): the same runtime,
 * validation and error model as the MCP server, with deployment values and claims from fixtures, and every route call
 * answered by a {@link RecordedExchange} instead of the network. Calls go through the MCP protocol layer, so a result
 * is exactly what a client would get.
 *
 * <pre>{@code
 * try (ToolHarness harness = ToolHarness.builder()
 *         .global("TimeoutSeconds", 5)
 *         .secret("ApiKey", "test-key")
 *         .claim("sub", "user-1")
 *         .exchange(RecordedExchange.on("orders.get").variable("orderId", "123").replyJson(200, Map.of("id", "123")))
 *         .build()) {
 *     ToolResult result = harness.call("orders.get", Map.of("orderId", "123"));
 *     assertEquals("123", result.output().path("order").path("id").asText());
 *     assertEquals("orders.get", harness.routeCalls().get(0).route());
 *     harness.assertExamples();
 * }
 * }</pre>
 */
public final class ToolHarness implements AutoCloseable {
    private static final long TASK_WAIT_MS = 960_000;

    private final StandaloneRuntime runtime;
    private final ProtocolCore core;
    private final ProtocolCore.Connection connection = new ProtocolCore.Connection("stdio");
    private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    private final ClassLoader loader;
    private final Path workingDirectory;
    private final List<RecordedExchange> exchanges;
    private final List<RouteCallRecord> calls = new ArrayList<>();
    private final Map<String, String> mcpNames = new LinkedHashMap<>();
    private final Map<String, JsonNode> examples = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private List<RecordedExchange> active;

    private ToolHarness(Builder b) {
        this.loader = b.loader;
        this.exchanges = List.copyOf(b.exchanges);
        this.active = exchanges;
        try {
            this.workingDirectory = Files.createTempDirectory("toolgate-harness");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        readIndex();
        TestExchanges answer = this::answer;
        this.runtime = StandaloneRuntime.startTest(loader, b.fixtures, answer, workingDirectory,
                new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
        this.core = new ProtocolCore(runtime);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Calls a tool by local name, tool id or MCP name, with arguments as a JSON string, a map or a JSON tree. */
    public ToolResult call(String tool, Object arguments) {
        String name = mcpName(tool);
        ObjectNode params = params();
        params.put("name", name);
        params.set("arguments", arguments(arguments));
        JsonNode response = request("tools/call", params);
        JsonNode result = response.path("result");
        if (result.path("resultType").asText().equals("task")) result = awaitTask(result.path("taskId").asText());
        return new ToolResult(result);
    }

    /** Every route call the tools made so far, answered or not, in order. */
    public synchronized List<RouteCallRecord> routeCalls() {
        return List.copyOf(calls);
    }

    public synchronized void clearRouteCalls() {
        calls.clear();
    }

    /** What the runtime printed: startup notes, warnings and tool logs. */
    public String diagnostics() {
        return diagnostics.toString(StandardCharsets.UTF_8);
    }

    /**
     * Runs every declared {@code @Example}. Each gets only the exchanges its {@code exchanges} resource records, so an
     * example that calls a route without one fails.
     */
    public List<ExampleResult> runExamples() {
        List<ExampleResult> results = new ArrayList<>();
        for (Map.Entry<String, JsonNode> tool : examples.entrySet()) {
            int index = 0;
            for (JsonNode example : tool.getValue()) results.add(runExample(tool.getKey(), index++, example));
        }
        return results;
    }

    /** Runs every declared {@code @Example} and fails listing each one that did not pass. */
    public void assertExamples() {
        List<ExampleResult> failed = runExamples().stream().filter(r -> !r.passed()).collect(Collectors.toList());
        if (!failed.isEmpty()) {
            throw new AssertionError(failed.size() + " example(s) failed:\n" + failed.stream().map(ExampleResult::toString).collect(Collectors.joining("\n")));
        }
    }

    @Override
    public void close() {
        runtime.tasks().close();
        try (Stream<Path> files = Files.walk(workingDirectory)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(p);
        } catch (IOException e) {
            // a temporary directory; best effort
        }
    }

    // ---- examples ----

    private ExampleResult runExample(String tool, int index, JsonNode example) {
        List<RecordedExchange> recorded = List.of();
        String resource = example.path("exchanges").asText("");
        if (!resource.isEmpty()) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) return new ExampleResult(tool, index, "its exchanges resource " + resource + " is not on the class path", null);
                recorded = RecordedExchange.load(in);
            } catch (IOException | RuntimeException e) {
                return new ExampleResult(tool, index, "its exchanges resource " + resource + " is invalid: " + e.getMessage(), null);
            }
        }
        int before;
        ToolResult result;
        List<RouteCallRecord> made;
        synchronized (this) {
            active = recorded;
            before = calls.size();
        }
        try {
            result = call(tool, example.path("arguments").asText());
        } catch (RuntimeException e) {
            return new ExampleResult(tool, index, "the call was refused: " + e.getMessage(), null);
        } finally {
            synchronized (this) {
                active = exchanges;
            }
        }
        synchronized (this) {
            made = List.copyOf(calls.subList(before, calls.size()));
        }
        for (RouteCallRecord c : made) {
            if (!c.answered()) {
                return new ExampleResult(tool, index, "it calls route " + c.route() + " with " + c.variables()
                        + " without a recorded mock exchange", result);
            }
        }
        return new ExampleResult(tool, index, check(example, result), result);
    }

    private static String check(JsonNode example, ToolResult result) {
        String expectedError = example.path("expectedError").asText("");
        if (!expectedError.isEmpty()) {
            if (!result.isError()) return "expected error " + expectedError + " but it succeeded";
            return result.errorCode().equals(expectedError) ? null : "expected error " + expectedError + " but got " + result.errorCode();
        }
        if (result.isError()) return "it failed with " + result.errorCode() + ": " + result.errorMessage();
        String expectedOutput = example.path("expectedOutput").asText("");
        if (expectedOutput.isEmpty()) return null;
        try {
            JsonNode expected = RecordedExchange.JSON.readTree(expectedOutput);
            JsonNode actual = RecordedExchange.JSON.readTree(result.output().toString());
            return expected.equals(actual) ? null : "expected output " + expected + " but got " + actual;
        } catch (IOException e) {
            return "its expectedOutput is not JSON";
        }
    }

    // ---- plumbing ----

    private synchronized JsonNode answer(ObjectNode request) {
        for (RecordedExchange e : active) {
            if (e.matches(request)) {
                calls.add(RouteCallRecord.of(request, true));
                return e.response();
            }
        }
        calls.add(RouteCallRecord.of(request, false));
        return null;
    }

    private void readIndex() {
        JsonNode index;
        try (InputStream in = loader.getResourceAsStream("META-INF/toolgate/package.json")) {
            if (in == null) throw new IllegalStateException("no tools on the class path: META-INF/toolgate/package.json is missing;"
                    + " compile with the ToolGate processor and -Atoolgate.descriptorGeneration=1");
            index = RecordedExchange.JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (JsonNode entry : index.path("tools")) {
            String name = entry.path("name").asText();
            JsonNode d;
            try (InputStream in = loader.getResourceAsStream("META-INF/toolgate/tools/" + name + ".json")) {
                if (in == null) throw new IllegalStateException("the descriptor of " + name + " is missing");
                d = RecordedExchange.JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String mcpName = d.path("mcpName").asText();
            mcpNames.put(name, mcpName);
            mcpNames.put(d.path("toolId").asText(), mcpName);
            mcpNames.put(mcpName, mcpName);
            if (entry.has("examples")) examples.put(name, entry.get("examples"));
        }
    }

    private String mcpName(String tool) {
        String name = mcpNames.get(tool);
        if (name == null) {
            throw new IllegalArgumentException("no tool " + tool + "; the class path has "
                    + mcpNames.keySet().stream().filter(n -> !n.contains("/") && !n.equals(mcpNames.get(n))).collect(Collectors.joining(", ")));
        }
        return name;
    }

    private static JsonNode arguments(Object arguments) {
        if (arguments == null) return RecordedExchange.JSON.createObjectNode();
        if (arguments instanceof JsonNode node) return node;
        if (arguments instanceof CharSequence text) {
            try {
                return RecordedExchange.JSON.readTree(text.toString());
            } catch (IOException e) {
                throw new IllegalArgumentException("arguments are not JSON: " + text, e);
            }
        }
        return RecordedExchange.JSON.valueToTree(arguments);
    }

    private ObjectNode params() {
        ObjectNode params = RecordedExchange.JSON.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolCore.VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions").putObject("io.modelcontextprotocol/tasks");
        return params;
    }

    private JsonNode request(String method, ObjectNode params) {
        ObjectNode message = RecordedExchange.JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", ids.incrementAndGet());
        message.put("method", method);
        message.set("params", params);
        JsonNode response = core.handle(message, connection);
        if (response.has("error")) {
            throw new IllegalArgumentException(method + " failed: " + response.get("error").path("message").asText());
        }
        return response;
    }

    /** Polls an async tool's task until it ends, and returns its result as a {@code CallToolResult}. */
    private JsonNode awaitTask(String taskId) {
        long end = System.currentTimeMillis() + TASK_WAIT_MS;
        while (true) {
            ObjectNode params = params();
            params.put("taskId", taskId);
            JsonNode task = request("tasks/get", params).path("result");
            switch (task.path("status").asText()) {
                case "completed" -> {
                    return task.path("result");
                }
                case "failed" -> {
                    JsonNode data = task.path("error").path("data");
                    ObjectNode failed = RecordedExchange.JSON.createObjectNode().put("isError", true);
                    failed.putObject("structuredContent").set("error", data.isObject() ? data
                            : RecordedExchange.JSON.createObjectNode().put("code", "INTERNAL_ERROR").put("message", task.path("error").path("message").asText()));
                    return failed;
                }
                case "cancelled" -> {
                    ObjectNode cancelled = RecordedExchange.JSON.createObjectNode().put("isError", true);
                    cancelled.putObject("structuredContent").putObject("error").put("code", "CANCELLED").put("message", "The task was cancelled");
                    return cancelled;
                }
                default -> {
                    if (System.currentTimeMillis() > end) throw new IllegalStateException("task " + taskId + " did not end");
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted waiting for task " + taskId, e);
                    }
                }
            }
        }
    }

    /** Fixtures and recorded exchanges for a harness. */
    public static final class Builder {
        private ClassLoader loader = Thread.currentThread().getContextClassLoader();
        private final ObjectNode fixtures = RecordedExchange.JSON.createObjectNode();
        private final List<RecordedExchange> exchanges = new ArrayList<>();

        private Builder() {}

        /** Where the tools and their descriptors are; the thread's context class loader by default. */
        public Builder classLoader(ClassLoader loader) {
            this.loader = loader;
            return this;
        }

        public Builder global(String name, Object value) {
            fixtures.withObjectProperty("globals").set(name, RecordedExchange.JSON.valueToTree(value));
            return this;
        }

        public Builder secret(String name, Object value) {
            fixtures.withObjectProperty("secrets").set(name, RecordedExchange.JSON.valueToTree(value));
            return this;
        }

        /** A fixture claim, as a verified credential would carry it. */
        public Builder claim(String name, Object value) {
            fixtures.withObjectProperty("claims").set(name, RecordedExchange.JSON.valueToTree(value));
            return this;
        }

        /** A destination host; route calls never use it, but TCP destinations do. */
        public Builder destination(String hostSetting, String host) {
            fixtures.withObjectProperty("destinations").put(hostSetting, host);
            return this;
        }

        public Builder exchange(RecordedExchange... recorded) {
            exchanges.addAll(List.of(recorded));
            return this;
        }

        /** Recorded exchanges from a class path resource in the format of spec/authoring/testing.md. */
        public Builder exchanges(String resource) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) throw new IllegalArgumentException(resource + " is not on the class path");
                exchanges.addAll(RecordedExchange.load(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return this;
        }

        public ToolHarness build() {
            return new ToolHarness(this);
        }
    }
}
