// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Governed mode: one invocation over tool protocol v2, with values and claims from the host and routes through its broker. */
class GovernedTest {
    static ClassLoader demo;
    static ClassLoader shop;

    @BeforeAll
    static void compile() {
        demo = Fixtures.fixture("demo");
        shop = Fixtures.fixture("routes");
    }

    private static ObjectNode input(String toolId, String arguments) throws IOException {
        ObjectNode input = Fixtures.JSON.createObjectNode();
        input.put("protocolVersion", 2).put("invocationId", "inv-1").put("toolId", toolId).put("entryPoint", "io.ololabs.toolgate.runtime.Main");
        input.set("arguments", Fixtures.JSON.readTree(arguments));
        input.putObject("globals");
        input.putObject("claims");
        input.putArray("secretHandles");
        input.put("deadlineUnixMs", System.currentTimeMillis() + 30_000).put("catalogRevision", "r1");
        return input;
    }

    private static JsonNode run(ClassLoader loader, Map<String, String> env, JsonNode input) throws IOException {
        var responses = frames(loader, env, input);
        return responses.get(responses.size() - 1);
    }

    private static List<JsonNode> frames(ClassLoader loader, Map<String, String> env, JsonNode input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Governed.run(loader, env, new ByteArrayInputStream((input + "\n").getBytes(StandardCharsets.UTF_8)), out,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"), "newline-terminated response frames: " + text + err);
        var result = new java.util.ArrayList<JsonNode>();
        for (String line : text.lines().toList()) result.add(Fixtures.JSON.readTree(line));
        assertEquals(1, result.stream().filter(frame -> frame.path("kind").asText().equals("RESULT")).count());
        assertEquals("RESULT", result.get(result.size() - 1).path("kind").asText(), "terminal result is last");
        return List.copyOf(result);
    }

    @Test void governedResponsesPreserveRealOrderAndRedactBeforeWriting() throws IOException {
        var input = input("acme.demo/demo.events", "{\"token\":\"private-input\"}");
        input.put("deadlineUnixMs", System.currentTimeMillis() + 10000);
        var frames = frames(demo, Map.of(), input);
        assertEquals(List.of("LOG", "PROGRESS", "PROGRESS", "RESULT"), frames.stream().map(frame -> frame.path("kind").asText()).toList());
        assertEquals("received [REDACTED]", frames.get(0).path("message").asText());
        assertEquals("processing [REDACTED]", frames.get(1).path("message").asText());
        assertEquals(0.5, frames.get(1).path("fraction").asDouble());
        assertEquals(1.0, frames.get(2).path("fraction").asDouble());
        assertFalse(frames.toString().contains("private-input"));
        assertTrue(frames.get(frames.size() - 1).path("structuredContent").path("done").asBoolean());
        var result = frames.get(frames.size() - 1).path("structuredContent");
        assertEquals("inv-1", result.path("invocation").asText());
        assertEquals("GOVERNED", result.path("mode").asText());
        assertEquals(input.path("deadlineUnixMs").asLong(), result.path("deadline").asLong());
        var monotonic = frames(demo, Map.of(), input("acme.demo/demo.tick", "{}"));
        assertEquals(List.of(0.25, 1.0), monotonic.stream().filter(frame -> frame.path("kind").asText().equals("PROGRESS")).map(frame -> frame.path("fraction").asDouble()).toList());
    }

    @Test void missingBindingAndExpiredInputsCannotStartTools() throws IOException {
        var missing = input("acme.demo/demo.events", "{\"token\":\"private\"}"); missing.remove("invocationId");
        var rejected = frames(demo, Map.of(), missing);
        assertEquals(1, rejected.size()); assertEquals("INVALID_INPUT", rejected.get(0).path("error").path("code").asText());
        var expired = input("acme.demo/demo.events", "{\"token\":\"private\"}"); expired.put("deadlineUnixMs", 1);
        var timedOut = frames(demo, Map.of(), expired);
        assertEquals(1, timedOut.size()); assertEquals("DEADLINE_EXCEEDED", timedOut.get(0).path("error").path("code").asText());
    }

    @Test
    void aCallAnswersWithOneResultFrame() throws IOException {
        JsonNode frame = run(demo, Map.of(), input("acme.demo/demo.echo", "{\"text\":\"ab\",\"count\":2,\"mood\":\"HAPPY\"}"));
        assertEquals("RESULT", frame.path("kind").asText());
        assertEquals("complete", frame.path("resultType").asText());
        assertFalse(frame.path("isError").asBoolean(), frame.toString());
        assertEquals("abab", frame.path("structuredContent").path("text").asText(), frame.toString());
        assertFalse(frame.has("_meta"), "no fixture identity mark under a Tool Host");
    }

    @Test
    void claimsAndGlobalsComeFromTheInput() throws IOException {
        ObjectNode input = input("acme.demo/demo.whoami", "{}");
        input.withObjectProperty("claims").put("preferred_username", "ann").put("sub", "u-1");
        JsonNode frame = run(demo, Map.of(), input);
        assertEquals("ann", frame.path("structuredContent").path("user").asText(), frame.toString());
        assertEquals("u-1", frame.path("structuredContent").path("subject").asText());
    }

    @Test
    void verifiedPrincipalComesFromTheHostAndNeverFromClaimsOrArguments() throws IOException {
        ObjectNode input = input("acme.demo/demo.principal", "{}");
        ObjectNode principal = input.withObjectProperty("claims").putObject("toolgate");
        principal.put("tenantId", "10000000-0000-4000-8000-000000000001").put("mode", "DELEGATED").put("userId", "user-42")
                .put("agentId", "agent-planner").put("operation", "read");
        principal.putArray("chain").add("agent-router");
        principal.putArray("resources").addObject().put("kind", "CUSTOM").put("locator", "olo://memory/user-42");
        JsonNode frame = run(demo, Map.of(), input);
        JsonNode result = frame.path("structuredContent");
        assertEquals("user-42", result.path("user").asText(), frame.toString());
        assertEquals("10000000-0000-4000-8000-000000000001", result.path("tenant").asText());
        assertEquals("agent-planner", result.path("agent").asText());
        assertEquals("read", result.path("operation").asText());
        assertEquals("agent-router", result.path("chain").path(0).asText());
        assertEquals("CUSTOM:olo://memory/user-42", result.path("resources").path(0).asText());
    }

    @Test
    void userScopedToolsRefuseWithoutAVerifiedUser() throws IOException {
        JsonNode frame = run(demo, Map.of(), input("acme.demo/demo.principal", "{}"));
        assertEquals("CLAIMS_UNAVAILABLE", frame.path("error").path("code").asText(), frame.toString());
        ObjectNode service = input("acme.demo/demo.principal", "{}");
        service.withObjectProperty("claims").putObject("toolgate").put("tenantId", "10000000-0000-4000-8000-000000000001").put("mode", "SERVICE");
        assertEquals("CLAIMS_UNAVAILABLE", run(demo, Map.of(), service).path("error").path("code").asText());
    }

    @Test
    void errorsAndTasksBecomeTerminalFrames() throws IOException {
        JsonNode failed = run(demo, Map.of(), input("acme.demo/demo.fail", "{\"kind\":\"retryable\"}"));
        assertTrue(failed.path("isError").asBoolean());
        assertEquals("BUSY", failed.path("error").path("code").asText(), failed.toString());
        assertTrue(failed.path("error").path("retryable").asBoolean());
        JsonNode invalid = run(demo, Map.of(), input("acme.demo/demo.echo", "{}"));
        assertTrue(invalid.path("isError").asBoolean(), invalid.toString());
        JsonNode task = run(demo, Map.of(), input("acme.demo/demo.export", "{}"));
        assertFalse(task.path("isError").asBoolean(), task.toString());
        assertEquals("complete", task.path("resultType").asText());
        assertEquals("TOOL_NOT_FOUND", run(demo, Map.of(), input("acme.demo/demo.nope", "{}")).path("error").path("code").asText());
        ObjectNode v1 = input("acme.demo/demo.echo", "{}");
        v1.put("protocolVersion", 1);
        assertEquals("UNSUPPORTED_PROTOCOL", run(demo, Map.of(), v1).path("error").path("code").asText());
    }

    /** Runs one invocation, answering each BROKER_REQUEST on stdin as the executor would; returns every frame. */
    private static List<JsonNode> converse(ClassLoader loader, Map<String, String> env, JsonNode input, Function<JsonNode, JsonNode> executor) throws Exception {
        var toTool = new java.io.PipedOutputStream(); var toolIn = new java.io.PipedInputStream(toTool, 1 << 20);
        var fromTool = new java.io.PipedInputStream(1 << 20); var toolOut = new java.io.PipedOutputStream(fromTool);
        toTool.write((input + "\n").getBytes(StandardCharsets.UTF_8)); toTool.flush();
        var tool = new Thread(() -> {
            try { Governed.run(loader, env, toolIn, toolOut, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)); toolOut.close(); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        tool.start();
        var frames = new java.util.ArrayList<JsonNode>();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(fromTool, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null; ) {
            JsonNode frame = Fixtures.JSON.readTree(line); frames.add(frame);
            if (frame.path("kind").asText().equals("BROKER_REQUEST")) {
                ObjectNode response = Fixtures.JSON.createObjectNode().put("kind", "BROKER_RESPONSE").put("requestId", frame.path("requestId").asText());
                JsonNode answer = executor.apply(frame);
                if (answer.has("error")) response.set("error", answer.get("error")); else response.set("result", answer);
                toTool.write((response + "\n").getBytes(StandardCharsets.UTF_8)); toTool.flush();
            }
        }
        tool.join(30_000);
        assertEquals("RESULT", frames.get(frames.size() - 1).path("kind").asText(), "terminal result is last: " + frames);
        return frames;
    }
    private static JsonNode refusal(String message) {
        ObjectNode error = Fixtures.JSON.createObjectNode(); error.putObject("error").put("code", "BROKER_REFUSED").put("message", message).put("retryable", false); return error;
    }

    @Test
    void routeCallsAndSecretsGoThroughTheOneStdioBroker() throws Exception {
        List<JsonNode> seen = new CopyOnWriteArrayList<>();
        Function<JsonNode, JsonNode> host = frame -> {
            seen.add(frame);
            if (!frame.path("operation").asText().equals("ROUTE_CALL")) return refusal("not a route call");
            if (frame.path("request").toString().contains("\"424242\"")) return refusal("route orders.get is not in this permit");
            try { return Fixtures.JSON.readTree("{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":{\"id\":\"123456\",\"expand\":\"lines\",\"trace\":\"t-1\",\"keyed\":true}}"); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        var frames = converse(shop, Map.of(), input("acme.shop/shop.order", "{\"id\":\"123456\",\"how\":\"plain\"}"), host);
        JsonNode result = frames.get(frames.size() - 1);
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals("123456", result.path("structuredContent").path("order").path("id").asText());
        JsonNode call = seen.get(0).path("request");
        assertEquals("ROUTE_CALL", seen.get(0).path("operation").asText());
        assertEquals("orders.get", call.path("route").asText());
        assertEquals("shop-key", call.path("auth").asText(), "the executor injects the credential the route names");
        assertEquals("123456", call.path("variables").path("orderId").asText());
        var refused = converse(shop, Map.of(), input("acme.shop/shop.order", "{\"id\":\"424242\",\"how\":\"plain\"}"), host);
        JsonNode error = refused.get(refused.size() - 1).path("error");
        assertEquals("ROUTE_REFUSED", error.path("code").asText(), refused.toString());
        assertTrue(error.path("message").asText().contains("not in this permit"), refused.toString());
        // With no executor answering, the call is refused, never left hanging or retried.
        JsonNode gone = run(shop, Map.of(), input("acme.shop/shop.order", "{\"id\":\"123456\",\"how\":\"plain\"}"));
        assertEquals("ROUTE_REFUSED", gone.path("error").path("code").asText(), gone.toString());
    }

    @Test
    void deviceFilesAreBrokeredOnlyForDeclaredClientToolsWithTheDeclaredAccess() throws Exception {
        ClassLoader notes = Fixtures.fixture("files");
        List<JsonNode> seen = new CopyOnWriteArrayList<>();
        Function<JsonNode, JsonNode> device = frame -> {
            seen.add(frame);
            var result = Fixtures.JSON.createObjectNode();
            switch (frame.path("operation").asText()) {
                case "FILES_READ" -> result.put("text", "hello");
                case "FILES_LIST" -> result.putArray("paths").add("a.txt");
                case "FILES_WRITE" -> { }
                default -> { return refusal("not provided on a device"); }
            }
            return result;
        };
        Map<String, String> client = Map.of("TOOLGATE_EXECUTION_TARGET", "CLIENT");
        var read = converse(notes, client, input("acme.notes/notes.read", "{\"path\":\"a.txt\"}"), device);
        JsonNode result = read.get(read.size() - 1);
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals("hello", result.path("structuredContent").path("text").asText());
        assertEquals(List.of("FILES_READ", "FILES_LIST"), seen.stream().map(f -> f.path("operation").asText()).toList());
        assertEquals("a.txt", seen.get(0).path("request").path("path").asText());
        seen.clear();
        var save = converse(notes, client, input("acme.notes/notes.save", "{\"path\":\"b.txt\",\"text\":\"hi\"}"), device);
        assertFalse(save.get(save.size() - 1).path("isError").asBoolean(), save.toString());
        assertEquals("hi", seen.get(0).path("request").path("text").asText());
        seen.clear();
        // Read-only declaration: the write never reaches the executor.
        var sneak = converse(notes, client, input("acme.notes/notes.sneak", "{\"path\":\"b.txt\"}"), device);
        assertEquals("FILES_REFUSED", sneak.get(sneak.size() - 1).path("error").path("code").asText(), sneak.toString());
        assertTrue(seen.isEmpty());
        // Undeclared: no files area at all.
        var peek = converse(notes, client, input("acme.notes/notes.peek", "{}"), device);
        assertTrue(peek.get(peek.size() - 1).path("isError").asBoolean(), peek.toString());
        assertTrue(seen.isEmpty());
        // A Tool Host has no device files: the same declared tool there gets none.
        var server = converse(notes, Map.of(), input("acme.notes/notes.read", "{\"path\":\"a.txt\"}"), device);
        assertTrue(server.get(server.size() - 1).path("isError").asBoolean(), server.toString());
    }
}
