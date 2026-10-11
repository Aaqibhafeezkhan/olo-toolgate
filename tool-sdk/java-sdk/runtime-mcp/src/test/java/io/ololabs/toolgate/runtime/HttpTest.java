// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.protocol.StreamableHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The standalone runtime over Streamable HTTP. */
class HttpTest {
    static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{\"io.modelcontextprotocol/tasks\":{}}}";
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static ClassLoader demo;
    static Map<String, String> env;
    static StreamableHttpServer server;
    static URI uri;

    @BeforeAll
    static void start() throws IOException {
        demo = Fixtures.fixture("demo");
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"secrets\":{\"ApiKey\":\"sk-live-123\"},\"claims\":{\"preferred_username\":\"dev.user\",\"sub\":\"dev-1\"}}");
        env = new HashMap<>(Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString(), "TOOLGATE_HTTP_ADDRESS", "127.0.0.1:0"));
        StandaloneRuntime runtime = StandaloneRuntime.start(demo, env, Path.of("build"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        server = runtime.serveHttp(runtime.httpAddress());
        uri = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<String> post(String method, String name, String params) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"));
        if (name != null) b.header("Mcp-Name", name);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void toolsAreCalledOverHttp() throws Exception {
        HttpResponse<String> r = post("tools/call", "demo.demo.echo", "{\"name\":\"demo.demo.echo\",\"arguments\":{\"text\":\"hi\",\"mood\":\"HAPPY\"}," + META + "}}");
        assertEquals(200, r.statusCode());
        JsonNode result = Fixtures.JSON.readTree(r.body()).path("result");
        assertEquals("hi", result.path("structuredContent").path("text").asText());
        assertEquals("fixture", result.path("_meta").path("io.ololabs.toolgate/identity").asText());
    }

    @Test
    void aTaskCreatedByOnePostIsPolledByTheNext() throws Exception {
        JsonNode created = Fixtures.JSON.readTree(post("tools/call", "demo.demo.refuse", "{\"name\":\"demo.demo.refuse\",\"arguments\":{}," + META + "}}").body());
        String id = created.path("result").path("taskId").asText();
        String status = "";
        for (int i = 0; i < 50 && !status.equals("completed"); i++) {
            Thread.sleep(50);
            status = Fixtures.JSON.readTree(post("tasks/get", id, "{\"taskId\":\"" + id + "\"," + META + "}}").body()).path("result").path("status").asText();
        }
        assertEquals("completed", status);
    }

    @Test
    void syncProgressIsSentAsNotificationsBeforeTheResult() throws Exception {
        HttpResponse<String> r = post("tools/call", "demo.demo.tick", "{\"name\":\"demo.demo.tick\",\"arguments\":{}," + META + ",\"progressToken\":7}}");
        List<JsonNode> frames = new ArrayList<>();
        for (String line : r.body().split("\n")) if (line.startsWith("data: ")) frames.add(Fixtures.JSON.readTree(line.substring(6)));
        assertEquals(3, frames.size(), r.body());
        assertEquals(25, frames.get(0).path("params").path("progress").asInt());
        assertEquals("a quarter", frames.get(0).path("params").path("message").asText());
        assertEquals(7, frames.get(0).path("params").path("progressToken").asInt());
        assertEquals(100, frames.get(1).path("params").path("progress").asInt());
        assertTrue(frames.get(2).path("result").path("structuredContent").path("ticked").asBoolean());
    }

    @Test
    void syncProgressWithoutATokenSendsNothing() throws Exception {
        HttpResponse<String> r = post("tools/call", "demo.demo.tick", "{\"name\":\"demo.demo.tick\",\"arguments\":{}," + META + "}}");
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void onlyLoopbackAddressesStart() {
        Map<String, String> wide = new HashMap<>(env);
        wide.put("TOOLGATE_HTTP_ADDRESS", "0.0.0.0:8080");
        assertTrue(refused(wide).contains("development mode binds only to stdio or a loopback address"));
        ClassLoader plain = Fixtures.compile(Map.of("acme/plain/Tools.java", """
                package acme.plain;
                import io.ololabs.toolgate.sdk.annotations.*;
                @ToolPackage(id = "acme.plain", name = "P", publisher = "Acme")
                public class Tools {
                    @ServerTool(name = "orders.list", title = "T", description = "D", effect = EffectSafety.READ_ONLY)
                    public java.util.Map<String, Object> list() { return java.util.Map.of(); }
                }
                """));
        try {
            StandaloneRuntime.start(plain, Map.of("TOOLGATE_HTTP_ADDRESS", "0.0.0.0:8080"), Path.of("build"),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            throw new AssertionError("the runtime started");
        } catch (StartupRefused e) {
            assertTrue(e.getMessage().contains("binds a non-loopback address only with an identity verifier"), e.getMessage());
        }
        wide.put("TOOLGATE_HTTP_ADDRESS", "nonsense");
        assertTrue(refused(wide).contains("must be host:port"));
        Map<String, String> none = new HashMap<>(env);
        none.remove("TOOLGATE_HTTP_ADDRESS");
        assertNull(StandaloneRuntime.start(demo, none, Path.of("build"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).httpAddress());
    }

    private static String refused(Map<String, String> environment) {
        try {
            StandaloneRuntime.start(demo, environment, Path.of("build"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        } catch (StartupRefused e) {
            return e.getMessage();
        }
        throw new AssertionError("the runtime started");
    }
}
