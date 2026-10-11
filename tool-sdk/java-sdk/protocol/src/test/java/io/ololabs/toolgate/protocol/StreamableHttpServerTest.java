// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StreamableHttpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    static Tasks tasks;
    static ProtocolCore core;
    static StreamableHttpServer server;
    static URI uri;

    /** One sync tool that reports progress, one that echoes, and Tasks. */
    static final class Catalog implements ToolCatalog {
        @Override public ObjectNode serverInfo() { return JSON.createObjectNode().put("name", "test").put("version", "1"); }
        @Override public ObjectNode discoverMeta() { return JSON.createObjectNode(); }
        @Override public Tasks tasks() { return tasks; }

        @Override
        public ArrayNode tools(RequestMeta meta) {
            ArrayNode tools = JSON.createArrayNode();
            tools.addObject().put("name", "echo");
            tools.addObject().put("name", "count");
            return tools;
        }

        @Override
        public ObjectNode call(String name, JsonNode arguments, RequestMeta meta) {
            if (name.equals("count")) {
                for (int i = 1; i <= 3; i++) {
                    ObjectNode frame = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/progress");
                    frame.putObject("params").put("progress", i).put("total", 3).set("progressToken", meta.progressToken());
                    meta.notifications().accept(frame);
                }
            }
            ObjectNode result = JSON.createObjectNode();
            result.set("structuredContent", arguments);
            result.putArray("content").addObject().put("type", "text").put("text", name);
            return result;
        }
    }

    @BeforeAll
    static void start() throws IOException {
        tasks = new Tasks(new MemoryTaskStore(), 60_000, 100);
        core = new ProtocolCore(new Catalog());
        server = StreamableHttpServer.start(core, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), "/mcp");
        uri = URI.create("http://localhost:" + server.port() + "/mcp");
    }

    @AfterAll
    static void stop() {
        server.close();
        tasks.close();
    }

    private static String body(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static String meta(String version, String extension) {
        return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\",\"io.modelcontextprotocol/clientCapabilities\":"
                + (extension == null ? "{}" : "{\"extensions\":{\"" + extension + "\":{}}}") + "}";
    }

    private static HttpRequest.Builder post(String body, String method, String name) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28");
        if (method != null) b.header("Mcp-Method", method);
        if (name != null) b.header("Mcp-Name", name);
        return b;
    }

    private static HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws IOException {
        return JSON.readTree(r.body());
    }

    @Test
    void aRequestGetsAJsonResultWithResultType() throws Exception {
        HttpResponse<String> r = send(post(body("tools/call", "{\"name\":\"echo\",\"arguments\":{\"a\":1}," + meta("2026-07-28", null) + "}"),
                "tools/call", "  echo  "));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode result = json(r).path("result");
        assertEquals("complete", result.path("resultType").asText());
        assertEquals("{\"a\":1}", result.path("structuredContent").toString());
        assertEquals(7, json(r).path("id").asInt());
    }

    @Test
    void headerAndMetaErrorsFollowTheSpecifiedOrderAndStatuses() throws Exception {
        String list = body("tools/list", "{" + meta("2026-07-28", null) + "}");
        HttpResponse<String> noHeader = send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(list))
                .header("Content-Type", "application/json").header("Mcp-Method", "tools/list"));
        assertEquals(400, noHeader.statusCode());
        assertEquals(-32020, json(noHeader).path("error").path("code").asInt());

        HttpResponse<String> noMeta = send(post(body("server/discover", "{}"), "server/discover", null));
        assertEquals(400, noMeta.statusCode());
        assertEquals(-32602, json(noMeta).path("error").path("code").asInt());
        assertEquals(7, json(noMeta).path("id").asInt());

        HttpResponse<String> mismatch = send(post(body("tools/list", "{" + meta("v999.0.0", null) + "}"), "tools/list", null));
        assertEquals(400, mismatch.statusCode());
        assertEquals(-32020, json(mismatch).path("error").path("code").asInt());

        HttpResponse<String> unsupported = send(post(body("tools/list", "{" + meta("v999.0.0", null) + "}"), "tools/list", null)
                .setHeader("MCP-Protocol-Version", "v999.0.0"));
        assertEquals(400, unsupported.statusCode());
        JsonNode error = json(unsupported).path("error");
        assertEquals(-32022, error.path("code").asInt());
        assertEquals("v999.0.0", error.path("data").path("requested").asText());
        assertEquals("[\"2026-07-28\"]", error.path("data").path("supported").toString());

        HttpResponse<String> wrongMethod = send(post(list, "TOOLS/LIST", null));
        assertEquals(-32020, json(wrongMethod).path("error").path("code").asInt());
        HttpResponse<String> wrongName = send(post(body("tools/call", "{\"name\":\"echo\"," + meta("2026-07-28", null) + "}"), "tools/call", "count"));
        assertEquals(400, wrongName.statusCode());
        assertEquals(-32020, json(wrongName).path("error").path("code").asInt());

        for (String method : List.of("initialize", "ping", "resources/subscribe", "unknown/method")) {
            HttpResponse<String> r = send(post(body(method, "{" + meta("2026-07-28", null) + "}"), method, null));
            assertEquals(404, r.statusCode(), method);
            assertEquals(-32601, json(r).path("error").path("code").asInt(), method);
        }
        HttpResponse<String> gate = send(post(body("tasks/get", "{\"taskId\":\"gate-test\"," + meta("2026-07-28", null) + "}"), "tasks/get", "gate-test"));
        assertEquals(400, gate.statusCode());
        assertEquals(-32021, json(gate).path("error").path("code").asInt());
    }

    @Test
    void transportRefusals() throws Exception {
        assertEquals(405, CLIENT.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, CLIENT.send(HttpRequest.newBuilder(uri.resolve("/other")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> parse = send(post("{not json", "tools/list", null));
        assertEquals(400, parse.statusCode());
        assertEquals(-32700, json(parse).path("error").path("code").asInt());
        HttpResponse<String> batch = send(post("[" + body("tools/list", "{}") + "]", "tools/list", null));
        assertEquals(-32600, json(batch).path("error").path("code").asInt());
        HttpResponse<String> notification = send(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", null, null));
        assertEquals(202, notification.statusCode());
        HttpResponse<String> big = send(post("x".repeat(StreamableHttpServer.MAX_BODY + 1), "tools/list", null));
        assertEquals(413, big.statusCode());
    }

    @Test
    void foreignHostsAndOriginsAreRefused() throws Exception {
        assertEquals(403, raw("evil.example.com", "http://evil.example.com"));
        assertEquals(403, raw("localhost:" + server.port(), "http://evil.example.com"));
        assertEquals(200, raw("localhost:" + server.port(), "http://localhost:" + server.port()));
        assertEquals(200, raw("127.0.0.1:" + server.port(), null));
    }

    /** A raw request, since java.net.http does not let a client set Host. */
    private static int raw(String host, String origin) throws IOException {
        String body = body("server/discover", "{" + meta("2026-07-28", null) + "}");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            OutputStream out = socket.getOutputStream();
            String request = "POST /mcp HTTP/1.1\r\nHost: " + host + "\r\n" + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "Content-Type: application/json\r\nMCP-Protocol-Version: 2026-07-28\r\nMcp-Method: server/discover\r\n"
                    + "Connection: close\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void progressStreamsAsSseBeforeTheResult() throws Exception {
        String call = body("tools/call", "{\"name\":\"count\",\"arguments\":{},\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":\"p-1\"}}");
        HttpResponse<String> r = send(post(call, "tools/call", "count"));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        List<JsonNode> frames = new ArrayList<>();
        for (String line : r.body().split("\n")) if (line.startsWith("data: ")) frames.add(JSON.readTree(line.substring(6)));
        assertEquals(4, frames.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("notifications/progress", frames.get(i).path("method").asText());
            assertEquals("p-1", frames.get(i).path("params").path("progressToken").asText());
            assertEquals(i + 1, frames.get(i).path("params").path("progress").asInt());
        }
        assertEquals("complete", frames.get(3).path("result").path("resultType").asText());
    }

    /** Opens a listen stream; frames arrive on the returned queue. */
    private static BlockingQueue<JsonNode> listen(int id, String filter, String extension) {
        BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"subscriptions/listen\",\"params\":{\"notifications\":" + filter + ","
                + meta("2026-07-28", extension) + "}}";
        CLIENT.sendAsync(post(body, "subscriptions/listen", null).build(), HttpResponse.BodyHandlers.ofLines()).thenAccept(r -> {
            try (Stream<String> lines = r.body()) {
                lines.filter(l -> l.startsWith("data: ")).forEach(l -> {
                    try {
                        frames.add(JSON.readTree(l.substring(6)));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        });
        return frames;
    }

    @Test
    void listenAcknowledgesTagsAndFilters() throws Exception {
        BlockingQueue<JsonNode> tools = listen(41, "{\"toolsListChanged\":true}", null);
        BlockingQueue<JsonNode> prompts = listen(42, "{\"promptsListChanged\":true}", null);
        JsonNode ack = tools.poll(5, TimeUnit.SECONDS);
        assertEquals("notifications/subscriptions/acknowledged", ack.path("method").asText());
        assertEquals("{\"toolsListChanged\":true}", ack.path("params").path("notifications").toString());
        assertEquals(41, ack.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asInt());
        JsonNode promptsAck = prompts.poll(5, TimeUnit.SECONDS);
        assertEquals("{}", promptsAck.path("params").path("notifications").toString());

        core.toolsListChanged();
        JsonNode changed = tools.poll(5, TimeUnit.SECONDS);
        assertEquals("notifications/tools/list_changed", changed.path("method").asText());
        assertEquals(41, changed.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asInt());
        assertEquals(null, prompts.poll(500, TimeUnit.MILLISECONDS));

        JsonNode discover = json(send(post(body("server/discover", "{" + meta("2026-07-28", null) + "}"), "server/discover", null)));
        assertTrue(discover.path("result").path("capabilities").path("tools").path("listChanged").asBoolean());
    }

    @Test
    void taskStatusChangesArePushedToListenersThatDeclaredTasks() throws Exception {
        BlockingQueue<JsonNode> withTasks = listen(51, "{}", RequestMeta.TASKS);
        BlockingQueue<JsonNode> without = listen(52, "{}", null);
        withTasks.poll(5, TimeUnit.SECONDS);
        without.poll(5, TimeUnit.SECONDS);
        TaskRecord t = tasks.create("http:anonymous", false, "Running", () -> {});
        tasks.progress(t.taskId, "half");
        tasks.complete(t.taskId, JSON.createObjectNode());
        JsonNode first = withTasks.poll(5, TimeUnit.SECONDS);
        assertEquals("notifications/tasks", first.path("method").asText());
        assertEquals(t.taskId, first.path("params").path("taskId").asText());
        assertEquals("half", first.path("params").path("statusMessage").asText());
        JsonNode done = withTasks.poll(5, TimeUnit.SECONDS);
        assertEquals("completed", done.path("params").path("status").asText());
        assertEquals(51, done.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asInt());
        assertEquals(null, without.poll(300, TimeUnit.MILLISECONDS));
        TaskRecord foreign = tasks.create("someone-else", false, "Running", () -> {});
        tasks.cancelled(foreign.taskId);
        assertEquals(null, withTasks.poll(300, TimeUnit.MILLISECONDS));
        assertFalse(Instant.now().isBefore(t.createdAt));
    }

    @Test
    void onlyLoopbackAddressesCanBeBound() {
        assertThrows(IllegalArgumentException.class, () -> StreamableHttpServer.start(core, new InetSocketAddress("0.0.0.0", 0), "/mcp"));
    }
}
