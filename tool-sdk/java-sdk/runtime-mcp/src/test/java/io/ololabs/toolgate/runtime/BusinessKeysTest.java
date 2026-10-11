// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.StreamableHttpServer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Business keys in standalone mode: the SQLite reservation store behind tools/call (execution.md §4). */
class BusinessKeysTest {
    static ClassLoader ledger;
    static HttpServer upstream;
    static final List<String> forwarded = new CopyOnWriteArrayList<>();
    static Path dev;

    @BeforeAll
    static void start() throws IOException {
        ledger = Fixtures.fixture("keys");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/payments", x -> {
            x.getRequestBody().readAllBytes();
            forwarded.add(String.valueOf(x.getRequestHeaders().getFirst("Idempotency-Key")));
            byte[] body = ("{\"payment\":" + forwarded.size() + "}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        upstream.start();
        dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"destinations\":{\"LedgerApiHost\":\"http://127.0.0.1:" + upstream.getAddress().getPort() + "\"},"
                + "\"claims\":{\"sub\":\"dev-1\"}}");
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    /** A development runtime over a fresh or given state file. */
    static Fixtures.Server server(Path state, String... extra) {
        Map<String, String> env = new HashMap<>(Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString(),
                "TOOLGATE_STATE_DB", state.toString()));
        for (int i = 0; i < extra.length; i += 2) env.put(extra[i], extra[i + 1]);
        return new Fixtures.Server(ledger, env);
    }

    static Path state() throws IOException {
        return Files.createTempDirectory("toolgate-state").resolve("state.db");
    }

    static JsonNode content(Fixtures.Server s, String tool, String args) {
        JsonNode result = s.call(tool, args).path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return result.path("structuredContent");
    }

    @Test
    void aRepeatReturnsTheFirstResultAndOtherArgumentsConflict() throws IOException {
        Fixtures.Server s = server(state());
        JsonNode first = content(s, "ledger.ledger.refund", "{\"orderId\":\"100\",\"refundKey\":\"rk-1\"}");
        assertEquals("rk-1", first.path("key").asText(), "invocation().businessKey()");
        JsonNode again = content(s, "ledger.ledger.refund", "{\"orderId\":\"100\",\"refundKey\":\"rk-1\"}");
        assertEquals(first, again, "EXISTING: the same result, and the handler did not run again");
        JsonNode conflict = s.error("ledger.ledger.refund", "{\"orderId\":\"200\",\"refundKey\":\"rk-1\"}");
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.path("code").asText());
        assertFalse(conflict.path("retryable").asBoolean());
        assertFalse(conflict.path("data").has("run"), "the original result is never returned");
        JsonNode other = content(s, "ledger.ledger.refund", "{\"orderId\":\"200\",\"refundKey\":\"rk-2\"}");
        assertNotEquals(first.path("run"), other.path("run"), "a new key is a new operation");
    }

    @Test
    void reservationsSurviveARestart() throws IOException {
        Path db = state();
        JsonNode first = content(server(db), "ledger.ledger.transfer", "{\"ref\":\"t-1\"}");
        assertEquals(first, content(server(db), "ledger.ledger.transfer", "{\"ref\":\"t-1\"}"));
    }

    @Test
    void aKeyPastItsRetentionIsExpired() throws Exception {
        Fixtures.Server s = server(state(), "TOOLGATE_BUSINESS_KEY_RETENTION", "PT0.5S");
        content(s, "ledger.ledger.refund", "{\"orderId\":\"1\",\"refundKey\":\"old\"}");
        Thread.sleep(700);
        JsonNode e = s.error("ledger.ledger.refund", "{\"orderId\":\"1\",\"refundKey\":\"old\"}");
        assertEquals("EXPIRED_KEY", e.path("code").asText());
        assertFalse(e.path("retryable").asBoolean());
    }

    @Test
    void aRepeatWhileTheFirstRunsIsInProgress() throws Exception {
        Fixtures.Server s = server(state());
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> s.call("ledger.ledger.slow", "{\"ref\":\"s-1\"}"));
        Thread.sleep(250);
        JsonNode e = s.error("ledger.ledger.slow", "{\"ref\":\"s-1\"}");
        assertEquals("IN_PROGRESS", e.path("code").asText());
        assertTrue(e.path("retryable").asBoolean());
        JsonNode done = first.get().path("result").path("structuredContent");
        assertEquals(done, content(s, "ledger.ledger.slow", "{\"ref\":\"s-1\"}"));
    }

    @Test
    void aRepeatOfATaskReturnsTheSameTask() throws Exception {
        Fixtures.Server s = server(state());
        JsonNode created = s.callTask("ledger.ledger.export", "{\"ref\":\"e-1\"}").path("result");
        assertEquals("task", created.path("resultType").asText(), created.toString());
        JsonNode repeat = s.callTask("ledger.ledger.export", "{\"ref\":\"e-1\"}").path("result");
        assertEquals(created.path("taskId"), repeat.path("taskId"));
        ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
        params.put("taskId", created.path("taskId").asText());
        JsonNode task = null;
        for (int i = 0; i < 100; i++) {
            task = s.request("tasks/get", params).path("result");
            if (task.path("status").asText().equals("completed")) break;
            Thread.sleep(50);
        }
        assertEquals("completed", task.path("status").asText(), String.valueOf(task));
        assertEquals(created.path("taskId"), s.callTask("ledger.ledger.export", "{\"ref\":\"e-1\"}").path("result").path("taskId"));
    }

    @Test
    void anMrtrRoundDoesNotHoldTheKey() throws IOException {
        Fixtures.Server s = server(state());
        ObjectNode params = Fixtures.Server.params(null);
        ((ObjectNode) params.path("_meta").path("io.modelcontextprotocol/clientCapabilities")).putObject("elicitation");
        params.put("name", "ledger.ledger.confirm");
        params.set("arguments", Fixtures.JSON.readTree("{\"ref\":\"c-1\"}"));
        JsonNode first = s.request("tools/call", params).path("result");
        assertEquals("input_required", first.path("resultType").asText(), first.toString());
        ObjectNode retry = params.deepCopy();
        retry.putObject("inputResponses").putObject("input-1").put("action", "accept").putObject("content").put("ok", "yes");
        retry.put("requestState", first.path("requestState").asText());
        JsonNode done = s.request("tools/call", retry).path("result");
        assertEquals("yes", done.path("structuredContent").path("ok").asText(), done.toString());
        assertEquals(done.path("structuredContent"), content(s, "ledger.ledger.confirm", "{\"ref\":\"c-1\"}"));
    }

    @Test
    void theKeyIsForwardedUpstreamByNamespace() throws IOException {
        Fixtures.Server s = server(state());
        int before = forwarded.size();
        content(s, "ledger.ledger.pay", "{\"ref\":\"p-1\"}");
        content(s, "ledger.ledger.pay", "{\"ref\":\"p-1\"}");
        assertEquals(before + 1, forwarded.size(), "the repeat never reached the upstream");
        String sent = forwarded.get(before);
        assertTrue(sent.matches("a\\.[A-Za-z0-9_-]{22}\\.p-1"), sent);
        assertFalse(sent.contains("dev-1"), "the agent identity is hashed");

        content(s, "ledger.ledger.charge", "{}");
        content(s, "ledger.ledger.charge", "{}");
        String one = forwarded.get(forwarded.size() - 2);
        String two = forwarded.get(forwarded.size() - 1);
        assertTrue(one.startsWith("i.") && two.startsWith("i."), one + " " + two);
        assertNotEquals(one, two, "PER_INVOCATION: one value per invocation");
    }

    @Test
    void delegatedUserKeysNeedAVerifiedUser() throws IOException {
        Path empty = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(empty, "{\"destinations\":{\"LedgerApiHost\":\"http://127.0.0.1:1\"}}");
        Fixtures.Server s = server(state(), "TOOLGATE_DEV_JSON", empty.toString());
        assertEquals("BUSINESS_KEY_NAMESPACE_MISMATCH", s.error("ledger.ledger.expense", "{\"ref\":\"x-1\"}").path("code").asText());
        Fixtures.Server withUser = server(state());
        JsonNode first = content(withUser, "ledger.ledger.expense", "{\"ref\":\"x-1\"}");
        assertEquals(first, content(withUser, "ledger.ledger.expense", "{\"ref\":\"x-1\"}"));
    }

    @Test
    void aRequiredKeyMayComeFromTheIdempotencyKeyHeader() throws Exception {
        Fixtures.Server stdio = server(state());
        JsonNode missing = stdio.error("ledger.ledger.void", "{}");
        assertEquals("INVALID_ARGUMENT", missing.path("code").asText());
        assertTrue(missing.path("message").asText().contains("Idempotency-Key"), missing.toString());

        Map<String, String> env = new HashMap<>(Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString(),
                "TOOLGATE_STATE_DB", state().toString(), "TOOLGATE_HTTP_ADDRESS", "127.0.0.1:0"));
        StandaloneRuntime runtime = StandaloneRuntime.start(ledger, env, Path.of("build"),
                new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        StreamableHttpServer http = runtime.serveHttp(runtime.httpAddress());
        try {
            URI uri = URI.create("http://127.0.0.1:" + http.port() + "/mcp");
            JsonNode one = post(uri, "v-1");
            assertEquals("v-1", one.path("result").path("structuredContent").path("key").asText(), one.toString());
            assertEquals(one.path("result").path("structuredContent"), post(uri, "v-1").path("result").path("structuredContent"));
        } finally {
            http.close();
        }
    }

    static JsonNode post(URI uri, String key) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ledger.ledger.void\",\"arguments\":{},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call").header("Mcp-Name", "ledger.ledger.void").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return Fixtures.JSON.readTree(response.body());
    }

    @Test
    void aStoreThatCannotOpenRefusesStartup() throws IOException {
        Path directory = Files.createTempDirectory("toolgate-state");
        StartupRefused e = assertThrows(StartupRefused.class, () -> server(directory));
        assertEquals("CONFIGURATION", e.code());
        assertThrows(StartupRefused.class, () -> server(state(), "TOOLGATE_BUSINESS_KEY_RETENTION", "thirty days"));
    }
}
