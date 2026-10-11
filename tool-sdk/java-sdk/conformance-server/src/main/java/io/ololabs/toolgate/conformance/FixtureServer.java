// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.MemoryTaskStore;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.protocol.ProtocolError;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.StreamableHttpServer;
import io.ololabs.toolgate.protocol.TaskRecord;
import io.ololabs.toolgate.protocol.Tasks;
import io.ololabs.toolgate.protocol.ToolCatalog;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The tools the official MCP conformance suite calls (tools-*, server-stateless, tasks-*), served by the protocol layer
 * over Streamable HTTP. It exercises adapters A1 and A2 and the transport, not the tool runtime: the suite needs bare
 * fixture names, and SDK tools are always named {@code <package alias>.<local name>}. See README.md.
 */
public final class FixtureServer implements ToolCatalog {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** A 1x1 transparent PNG. */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    /** A 44-byte WAV header with no samples. */
    static final String WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQAAAAA=";

    private enum TaskSupport { FORBIDDEN, OPTIONAL, REQUIRED }

    private record Fixture(String description, ObjectNode inputSchema, TaskSupport tasks) {}

    private final Map<String, Fixture> fixtures = new LinkedHashMap<>();
    private final Tasks tasks = new Tasks(new MemoryTaskStore(), Tasks.DEFAULT_TTL_MS, 200);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fixture-task");
        t.setDaemon(true);
        return t;
    });
    private volatile ProtocolCore core;

    FixtureServer() {
        // The first tool takes no arguments and always succeeds: http-header-validation calls tools[0] with {}.
        add("test_simple_text", "Returns a simple text response.", schema(), TaskSupport.FORBIDDEN);
        add("test_image_content", "Returns an image.", schema(), TaskSupport.FORBIDDEN);
        add("test_audio_content", "Returns audio.", schema(), TaskSupport.FORBIDDEN);
        add("test_embedded_resource", "Returns an embedded resource.", schema(), TaskSupport.FORBIDDEN);
        add("test_multiple_content_types", "Returns text, an image and a resource.", schema(), TaskSupport.FORBIDDEN);
        add("test_error_handling", "Returns a tool error.", schema(), TaskSupport.FORBIDDEN);
        add("test_tool_with_progress", "Reports progress, then returns.", schema(), TaskSupport.FORBIDDEN);
        add("test_missing_capability", "Needs the sampling capability.", schema(), TaskSupport.FORBIDDEN);
        add("test_streaming_elicitation", "Returns without sending requests on the stream.", schema(), TaskSupport.FORBIDDEN);
        add("test_logging_tool", "Returns without log notifications.", schema(), TaskSupport.FORBIDDEN);
        add("test_trigger_tool_change", "Announces a tools list change.", schema(), TaskSupport.FORBIDDEN);
        add("greet", "Greets someone.", schema("name", "string"), TaskSupport.FORBIDDEN);
        ObjectNode slow = schema("seconds", "number");
        ((ObjectNode) slow.get("properties")).putObject("label").put("type", "string");
        add("slow_compute", "Sleeps, then returns.", slow, TaskSupport.OPTIONAL);
        add("failing_job", "Fails as a task.", schema(), TaskSupport.REQUIRED);
        add("protocol_error_job", "Ends its task failed.", schema(), TaskSupport.REQUIRED);
        add("confirm_delete", "Asks for confirmation.", schema("filename", "string"), TaskSupport.REQUIRED);
        add("multi_input", "Asks two questions at once.", schema(), TaskSupport.REQUIRED);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        FixtureServer catalog = new FixtureServer();
        ProtocolCore core = new ProtocolCore(catalog);
        catalog.core = core;
        StreamableHttpServer server = StreamableHttpServer.start(core, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), "/mcp");
        System.out.println("fixture server at http://localhost:" + server.port() + "/mcp");
        System.out.flush();
        Thread.currentThread().join();
    }

    private void add(String name, String description, ObjectNode inputSchema, TaskSupport support) {
        fixtures.put(name, new Fixture(description, inputSchema, support));
    }

    private static ObjectNode schema(String... nameAndType) {
        ObjectNode s = JSON.objectNode();
        s.put("type", "object");
        ObjectNode properties = s.putObject("properties");
        for (int i = 0; i < nameAndType.length; i += 2) properties.putObject(nameAndType[i]).put("type", nameAndType[i + 1]);
        return s;
    }

    @Override
    public ObjectNode serverInfo() {
        return JSON.objectNode().put("name", "toolgate-java-fixtures").put("version", "1");
    }

    @Override
    public ObjectNode discoverMeta() {
        return JSON.objectNode();
    }

    @Override
    public Tasks tasks() {
        return tasks;
    }

    @Override
    public JsonNode tools(RequestMeta meta) {
        ArrayNode list = JSON.arrayNode();
        fixtures.forEach((name, f) -> {
            ObjectNode t = list.addObject();
            t.put("name", name);
            t.put("description", f.description);
            t.set("inputSchema", f.inputSchema);
            if (f.tasks != TaskSupport.FORBIDDEN) t.putObject("execution").put("taskSupport", f.tasks.name().toLowerCase(java.util.Locale.ROOT));
        });
        return list;
    }

    @Override
    public ObjectNode call(String name, JsonNode arguments, RequestMeta meta) {
        Fixture f = fixtures.get(name);
        if (f == null) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Unknown tool: " + name);
        boolean asTask = meta.declares(RequestMeta.TASKS) && f.tasks != TaskSupport.FORBIDDEN;
        if (f.tasks == TaskSupport.REQUIRED && !asTask) throw ProtocolError.missingExtension(RequestMeta.TASKS);
        if (asTask) return task(name, arguments, meta);
        return switch (name) {
            case "test_simple_text" -> text("This is a simple text response for testing.");
            case "test_image_content" -> content(image());
            case "test_audio_content" -> content(JSON.objectNode().put("type", "audio").put("data", WAV).put("mimeType", "audio/wav"));
            case "test_embedded_resource" -> content(resource("test://embedded-resource", "text/plain", "This is an embedded resource content."));
            case "test_multiple_content_types" -> content(JSON.objectNode().put("type", "text").put("text", "Multiple content types."), image(),
                    resource("test://mixed-content-resource", "application/json", "{\"test\":\"data\",\"value\":123}"));
            case "test_error_handling" -> text("This tool intentionally returns an error for testing").put("isError", true);
            case "test_tool_with_progress" -> progress(meta);
            case "test_missing_capability" -> {
                if (!meta.clientCapabilities().has("sampling")) {
                    ObjectNode data = JSON.objectNode();
                    data.putObject("requiredCapabilities").putObject("sampling");
                    throw new ProtocolError(ProtocolError.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability", data);
                }
                yield text("Sampling is declared.");
            }
            case "test_streaming_elicitation" -> text("No requests are sent on the response stream.");
            case "test_logging_tool" -> text("No log notifications without a requested log level.");
            case "test_trigger_tool_change" -> {
                ProtocolCore c = core;
                if (c != null) c.toolsListChanged();
                yield text("Tools list change announced.");
            }
            case "greet" -> text("Hello, " + arguments.path("name").asText() + "!");
            case "slow_compute" -> {
                try {
                    sleep(arguments.path("seconds").asDouble(0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield text("Computed " + arguments.path("label").asText());
            }
            default -> throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Unknown tool: " + name);
        };
    }

    private ObjectNode task(String name, JsonNode arguments, RequestMeta meta) {
        AtomicReference<Thread> worker = new AtomicReference<>();
        TaskRecord task = tasks.create(meta.owner(), false, "Running " + name, () -> {
            Thread t = worker.get();
            if (t != null) t.interrupt();
        });
        String id = task.taskId;
        workers.execute(() -> {
            worker.set(Thread.currentThread());
            try {
                switch (name) {
                    case "slow_compute" -> {
                        sleep(arguments.path("seconds").asDouble(0));
                        finish(id, text("Computed " + arguments.path("label").asText()));
                    }
                    case "failing_job" -> {
                        sleep(1);
                        finish(id, text("The job failed.").put("isError", true));
                    }
                    case "protocol_error_job" -> tasks.fail(id, ProtocolError.INTERNAL_ERROR, "The job hit a protocol error");
                    case "confirm_delete" -> {
                        ObjectNode answers = ask(id, Map.of("confirm", form("Delete " + arguments.path("filename").asText() + "?", "confirm", "boolean")));
                        if (answers == null) tasks.cancelled(id);
                        else finish(id, text("Deleted: " + answers.path("confirm").path("content").path("confirm").asBoolean()));
                    }
                    case "multi_input" -> {
                        ObjectNode answers = ask(id, Map.of("name", form("Your name?", "name", "string"), "confirm", form("Confirm?", "confirm", "boolean")));
                        if (answers == null) tasks.cancelled(id);
                        else finish(id, text("Hello, " + answers.path("name").path("content").path("name").asText() + "!"));
                    }
                    default -> tasks.fail(id, ProtocolError.INTERNAL_ERROR, "Unknown task tool");
                }
            } catch (InterruptedException e) {
                tasks.cancelled(id);
            }
        });
        return Tasks.createResult(task);
    }

    private void finish(String id, ObjectNode result) {
        result.put("resultType", "complete");
        if (tasks.cancelRequested(id)) tasks.cancelled(id);
        else tasks.complete(id, result);
    }

    private ObjectNode ask(String id, Map<String, ObjectNode> requests) throws InterruptedException {
        ObjectNode all = JSON.objectNode();
        requests.forEach(all::set);
        return tasks.awaitInput(id, all, Instant.now().plusSeconds(60));
    }

    private static ObjectNode form(String message, String field, String type) {
        ObjectNode request = JSON.objectNode();
        request.put("method", "elicitation/create");
        ObjectNode params = request.putObject("params");
        params.put("mode", "form");
        params.put("message", message);
        params.set("requestedSchema", schema(field, type));
        return request;
    }

    private static ObjectNode progress(RequestMeta meta) {
        for (int p = 0; p <= 100; p += 50) {
            if (meta.progressToken() != null) {
                ObjectNode frame = JSON.objectNode();
                frame.put("jsonrpc", "2.0");
                frame.put("method", "notifications/progress");
                frame.putObject("params").put("progress", p).put("total", 100).set("progressToken", meta.progressToken());
                meta.notifications().accept(frame);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return text("Progress reported.");
    }

    private static void sleep(double seconds) throws InterruptedException {
        Thread.sleep((long) (seconds * 1000));
    }

    private static ObjectNode text(String text) {
        return content(JSON.objectNode().put("type", "text").put("text", text));
    }

    private static ObjectNode content(ObjectNode... items) {
        ObjectNode result = JSON.objectNode();
        ArrayNode content = result.putArray("content");
        for (ObjectNode item : items) content.add(item);
        result.put("isError", false);
        return result;
    }

    private static ObjectNode image() {
        return JSON.objectNode().put("type", "image").put("data", PNG).put("mimeType", "image/png");
    }

    private static ObjectNode resource(String uri, String mimeType, String text) {
        ObjectNode item = JSON.objectNode().put("type", "resource");
        item.putObject("resource").put("uri", uri).put("mimeType", mimeType).put("text", text);
        return item;
    }
}
