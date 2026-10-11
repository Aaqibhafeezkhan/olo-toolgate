// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.MemoryTaskStore;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.Tasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Tasks extension (adapter A2) in the standalone runtime. */
class TasksTest {
    static final long POLL_MS = 100;
    static ClassLoader demo;
    static Fixtures.Server server;
    static Map<String, String> env;

    @BeforeAll
    static void start() throws IOException {
        demo = Fixtures.fixture("demo");
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"secrets\":{\"ApiKey\":\"sk-live-123\"},\"claims\":{\"preferred_username\":\"dev.user\",\"sub\":\"dev-1\"}}");
        env = Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString());
        server = new Fixtures.Server(demo, env, new Tasks(new MemoryTaskStore(), 60_000, POLL_MS));
    }

    private static ObjectNode taskParams(String taskId) {
        ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
        params.put("taskId", taskId);
        return params;
    }

    private static JsonNode get(Fixtures.Server s, String taskId) {
        return s.request("tasks/get", taskParams(taskId));
    }

    /** Polls until the task status matches, or fails after the timeout. */
    private static JsonNode until(Fixtures.Server s, String taskId, Predicate<JsonNode> done, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        JsonNode last = null;
        while (System.currentTimeMillis() < end) {
            last = get(s, taskId).path("result");
            if (done.test(last)) return last;
            Thread.sleep(POLL_MS / 2);
        }
        throw new AssertionError("task did not settle: " + last);
    }

    private static Predicate<JsonNode> status(String status) {
        return r -> r.path("status").asText().equals(status);
    }

    private static String start(String tool, String arguments) {
        JsonNode created = server.callTask(tool, arguments).path("result");
        assertEquals("task", created.path("resultType").asText(), created.toString());
        return created.path("taskId").asText();
    }

    @Test
    void tasksAreOfferedOnlyToRequestsThatDeclareThem() {
        JsonNode discover = server.request("server/discover", Fixtures.Server.params(null)).path("result");
        assertTrue(discover.path("capabilities").path("extensions").has(RequestMeta.TASKS));
        assertFalse(discover.path("capabilities").has("tasks"));

        List<String> plain = new ArrayList<>();
        server.request("tools/list", Fixtures.Server.params(null)).path("result").path("tools").forEach(t -> plain.add(t.path("name").asText()));
        assertFalse(plain.contains("demo.demo.report"));
        JsonNode report = null;
        for (JsonNode t : server.request("tools/list", Fixtures.Server.params(RequestMeta.TASKS)).path("result").path("tools")) {
            if (t.path("name").asText().equals("demo.demo.report")) report = t;
        }
        assertEquals("required", report.path("execution").path("taskSupport").asText());

        JsonNode refused = server.call("demo.demo.report", "{\"ms\":10}").path("error");
        assertEquals(-32021, refused.path("code").asInt());
        assertTrue(refused.path("data").path("requiredCapabilities").path("extensions").has(RequestMeta.TASKS));
    }

    @Test
    void theTasksMethodsNeedTheExtensionBeforeAnyLookup() {
        for (String method : List.of("tasks/get", "tasks/update", "tasks/cancel")) {
            ObjectNode params = Fixtures.Server.params(null);
            params.put("taskId", "gate-test");
            params.putObject("inputResponses");
            JsonNode error = server.request(method, params).path("error");
            assertEquals(-32021, error.path("code").asInt(), method);
            assertTrue(error.path("data").path("requiredCapabilities").path("extensions").has(RequestMeta.TASKS), method);
        }
        for (String method : List.of("tasks/result", "tasks/list")) {
            assertEquals(-32601, server.request(method, taskParams("x")).path("error").path("code").asInt(), method);
        }
    }

    @Test
    void aTaskIsCreatedFlatAndCompletesWithTheToolResult() throws InterruptedException {
        JsonNode created = server.callTask("demo.demo.report", "{\"ms\":600}").path("result");
        assertEquals("task", created.path("resultType").asText());
        assertEquals("working", created.path("status").asText());
        assertTrue(created.path("createdAt").asText().matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
        assertTrue(created.path("lastUpdatedAt").asText().matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
        assertEquals(60_000, created.path("ttlMs").asLong());
        assertEquals(POLL_MS, created.path("pollIntervalMs").asLong());
        for (String forbidden : List.of("task", "result", "error", "inputRequests", "requestState", "ttl", "pollInterval")) {
            assertFalse(created.has(forbidden), forbidden);
        }
        String id = created.path("taskId").asText();
        assertTrue(id.matches("[0-9a-f]{32}"), id);

        JsonNode working = until(server, id, r -> r.path("statusMessage").asText().contains("%"), 2_000);
        assertEquals("complete", working.path("resultType").asText());
        assertEquals(id, working.path("taskId").asText());
        assertFalse(working.has("result"));
        JsonNode done = until(server, id, status("completed"), 5_000);
        JsonNode result = done.path("result");
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("structuredContent").path("steps").asInt() > 0);
        assertTrue(result.path("content").size() > 0);
        assertFalse(done.has("requestState"));
        assertFalse(result.path("_meta").has("io.modelcontextprotocol/related-task"));
    }

    @Test
    void aToolErrorCompletesTheTaskWithIsError() throws InterruptedException {
        String id = start("demo.demo.refuse", "{}");
        JsonNode done = until(server, id, status("completed"), 5_000);
        assertTrue(done.path("result").path("isError").asBoolean());
        assertEquals("NOPE", done.path("result").path("structuredContent").path("error").path("code").asText());
        assertFalse(done.has("error"));
    }

    @Test
    void invalidArgumentsAreRefusedBeforeATaskExists() {
        JsonNode result = server.callTask("demo.demo.report", "{\"ms\":\"soon\"}").path("result");
        assertEquals("complete", result.path("resultType").asText());
        assertEquals("INVALID_ARGUMENT", result.path("structuredContent").path("error").path("code").asText());
    }

    @Test
    void syncToolsStaySyncAndTheOldTaskHintIsIgnored() {
        ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
        params.put("name", "demo.demo.echo");
        params.putObject("arguments").put("text", "hi").put("mood", "HAPPY");
        params.putObject("task").put("ttl", 60_000).put("pollInterval", 100);
        JsonNode result = server.request("tools/call", params).path("result");
        assertEquals("complete", result.path("resultType").asText());
        assertFalse(result.has("taskId"));
    }

    @Test
    void anotherOwnerGetsNotFoundExactlyAsForAnUnknownId() throws InterruptedException {
        String id = start("demo.demo.report", "{\"ms\":2000}");
        ProtocolCore.Connection other = new ProtocolCore.Connection("stdio");
        JsonNode unknown = server.request("tasks/get", taskParams("0".repeat(32))).path("error");
        assertEquals(-32602, unknown.path("code").asInt());
        for (String method : List.of("tasks/get", "tasks/update", "tasks/cancel")) {
            ObjectNode params = taskParams(id);
            params.putObject("inputResponses");
            JsonNode error = server.request(method, params, other).path("error");
            assertEquals(unknown.path("code"), error.path("code"), method);
            assertEquals(unknown.path("message"), error.path("message"), method);
        }
        assertEquals("working", get(server, id).path("result").path("status").asText());
        server.request("tasks/cancel", taskParams(id));
        until(server, id, status("cancelled"), 3_000);
    }

    @Test
    void requestInputMovesTheTaskToInputRequiredUntilTasksUpdate() throws InterruptedException {
        String id = start("demo.demo.ask", "{}");
        JsonNode waiting = until(server, id, status("input_required"), 3_000);
        JsonNode requests = waiting.path("inputRequests");
        assertEquals(1, requests.size());
        String key = requests.fieldNames().next();
        assertEquals("elicitation/create", requests.path(key).path("method").asText());
        assertEquals("form", requests.path(key).path("params").path("mode").asText());
        assertEquals("What is your name?", requests.path(key).path("params").path("message").asText());

        ObjectNode unknown = taskParams(id);
        unknown.putObject("inputResponses").putObject("unknown-key").put("ignored", true);
        JsonNode ack = server.request("tasks/update", unknown).path("result");
        assertEquals("complete", ack.path("resultType").asText());
        for (String forbidden : List.of("taskId", "status", "result", "error", "inputRequests")) assertFalse(ack.has(forbidden), forbidden);
        assertEquals("input_required", get(server, id).path("result").path("status").asText());

        ObjectNode answer = taskParams(id);
        ObjectNode response = answer.putObject("inputResponses").putObject(key);
        response.put("action", "accept");
        response.putObject("content").put("name", "Alice");
        server.request("tasks/update", answer);
        JsonNode done = until(server, id, status("completed"), 3_000);
        assertEquals("Hello, Alice!", done.path("result").path("structuredContent").path("greeting").asText());
    }

    @Test
    void aDeclinedInputEndsTheToolCancelled() throws InterruptedException {
        String id = start("demo.demo.ask", "{}");
        JsonNode waiting = until(server, id, status("input_required"), 3_000);
        String key = waiting.path("inputRequests").fieldNames().next();
        ObjectNode decline = taskParams(id);
        decline.putObject("inputResponses").putObject(key).put("action", "decline");
        server.request("tasks/update", decline);
        JsonNode done = until(server, id, status("completed"), 3_000);
        assertEquals("CANCELLED", done.path("result").path("structuredContent").path("error").path("code").asText());
    }

    @Test
    void requestInputInASyncCallNeedsRequestState() {
        assertEquals("CONFIGURATION", server.error("demo.demo.inline", "{}").path("code").asText());
    }

    @Test
    void cancelStopsTheToolAndIsIdempotentOnceTerminal() throws InterruptedException {
        String id = start("demo.demo.report", "{\"ms\":10000}");
        until(server, id, status("working"), 1_000);
        long start = System.currentTimeMillis();
        JsonNode ack = server.request("tasks/cancel", taskParams(id)).path("result");
        assertEquals("complete", ack.path("resultType").asText());
        assertFalse(ack.has("status"));
        until(server, id, status("cancelled"), 3_000);
        assertTrue(System.currentTimeMillis() - start < 3_000);
        assertEquals("complete", server.request("tasks/cancel", taskParams(id)).path("result").path("resultType").asText());
        assertFalse(get(server, id).path("result").has("result"));
    }

    @Test
    void aToolThatIgnoresCancellationIsAbandonedAfterTheGrace() throws InterruptedException {
        String id = start("demo.demo.report", "{\"ms\":20000,\"stubborn\":true}");
        until(server, id, status("working"), 1_000);
        long start = System.currentTimeMillis();
        server.request("tasks/cancel", taskParams(id));
        until(server, id, status("cancelled"), StandaloneRuntime.CANCEL_GRACE_MS + 3_000);
        long took = System.currentTimeMillis() - start;
        assertTrue(took >= StandaloneRuntime.CANCEL_GRACE_MS - 200, "took " + took);
        assertTrue(server.diagnostics().contains("did not stop within the cancellation grace"));
    }

    @Test
    void cancelIfAbandonedCancelsAfterThreeMissedPolls() throws InterruptedException {
        String abandoned = start("demo.demo.watch", "{}");
        String kept = start("demo.demo.report", "{\"ms\":1500}");
        Thread.sleep(POLL_MS * Tasks.ABANDONED_AFTER_POLLS + 600);
        assertEquals("cancelled", get(server, abandoned).path("result").path("status").asText());
        assertEquals("working", get(server, kept).path("result").path("status").asText());
        until(server, kept, status("completed"), 3_000);
    }

    @Test
    void tasksArePurgedAfterTheirTtl() throws InterruptedException {
        Fixtures.Server shortLived = new Fixtures.Server(demo, env, new Tasks(new MemoryTaskStore(), 400, 50));
        String id = shortLived.callTask("demo.demo.refuse", "{}").path("result").path("taskId").asText();
        until(shortLived, id, status("completed"), 300);
        Thread.sleep(150);
        assertEquals("completed", get(shortLived, id).path("result").path("status").asText(), "still there before the TTL");
        Thread.sleep(700);
        assertEquals(-32602, get(shortLived, id).path("error").path("code").asInt());
    }
}
