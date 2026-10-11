// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.ProtocolCore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** MRTR in sync calls: {@code requestInput()} with {@code requestState} (adapter A3). */
class MrtrTest {
    static Fixtures.Server server;

    @BeforeAll
    static void start() {
        server = new Fixtures.Server(Fixtures.fixture("demo"), Map.of("TOOLGATE_MODE", "development"));
    }

    /** A tools/call from a client that declares elicitation, with optional answers and state. */
    private static JsonNode call(String tool, String arguments, ObjectNode responses, String state, ProtocolCore.Connection on) {
        ObjectNode params = Fixtures.Server.params(null);
        ((ObjectNode) params.path("_meta").path("io.modelcontextprotocol/clientCapabilities")).putObject("elicitation");
        params.put("name", tool);
        try {
            params.set("arguments", Fixtures.JSON.readTree(arguments));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (responses != null) params.set("inputResponses", responses);
        if (state != null) params.put("requestState", state);
        return server.request("tools/call", params, on);
    }

    private static JsonNode call(String tool, String arguments, ObjectNode responses, String state) {
        return call(tool, arguments, responses, state, server.connection);
    }

    private static ObjectNode accept(String key, String field, String value) {
        ObjectNode responses = Fixtures.JSON.createObjectNode();
        ObjectNode answer = responses.putObject(key);
        answer.put("action", "accept");
        answer.putObject("content").put(field, value);
        return responses;
    }

    @Test
    void eachRoundAsksForTheNextAnswerAndTheLastCompletes() {
        JsonNode first = call("demo.demo.inline", "{}", null, null).path("result");
        assertEquals("input_required", first.path("resultType").asText(), first.toString());
        JsonNode ask = first.path("inputRequests").path("input-1");
        assertEquals("elicitation/create", ask.path("method").asText());
        assertEquals("What is your name?", ask.path("params").path("message").asText());
        assertEquals("form", ask.path("params").path("mode").asText());
        String state1 = first.path("requestState").asText();
        assertEquals(3, state1.split("\\.").length, "a compact JWS");
        assertFalse(first.has("taskId"));

        JsonNode second = call("demo.demo.inline", "{}", accept("input-1", "name", "Ada"), state1).path("result");
        assertEquals("input_required", second.path("resultType").asText(), second.toString());
        assertTrue(second.path("inputRequests").has("input-2"));
        assertFalse(second.path("inputRequests").has("input-1"));
        String state2 = second.path("requestState").asText();
        assertNotEquals(state1, state2);

        // The first answer rides in the state; the retry sends only the new one.
        JsonNode done = call("demo.demo.inline", "{}", accept("input-2", "colour", "Blue"), state2).path("result");
        assertEquals("complete", done.path("resultType").asText(), done.toString());
        assertEquals("Hello, Ada! Blue it is.", done.path("structuredContent").path("greeting").asText());
        assertFalse(done.has("requestState"));
    }

    @Test
    void aStateIsRefusedWhenTamperedReplayedOrPresentedWithOtherArgumentsOrByAnotherIdentity() {
        String state = call("demo.demo.inline", "{\"greeting\":\"Hi\"}", null, null).path("result").path("requestState").asText();
        ObjectNode answer = accept("input-1", "name", "Ada");

        String[] parts = state.split("\\.");
        char c = parts[1].charAt(5);
        String tampered = parts[0] + "." + parts[1].substring(0, 5) + (c == 'A' ? 'B' : 'A') + parts[1].substring(6) + "." + parts[2];
        assertEquals(-32602, call("demo.demo.inline", "{\"greeting\":\"Hi\"}", answer, tampered).path("error").path("code").asInt());
        assertEquals(-32602, call("demo.demo.inline", "{\"greeting\":\"Hey\"}", answer, state).path("error").path("code").asInt());
        assertEquals(-32602, call("demo.demo.inline", "{\"greeting\":\"Hi\"}", answer, state, new ProtocolCore.Connection("stdio"))
                .path("error").path("code").asInt());
        assertEquals(-32602, call("demo.demo.swallow", "{\"greeting\":\"Hi\"}", answer, state).path("error").path("code").asInt());

        // None of the refusals spent the nonce; the first good retry does, and a second is refused.
        JsonNode good = call("demo.demo.inline", "{\"greeting\":\"Hi\"}", answer, state).path("result");
        assertEquals("input_required", good.path("resultType").asText(), good.toString());
        JsonNode replay = call("demo.demo.inline", "{\"greeting\":\"Hi\"}", answer, state);
        assertEquals(-32602, replay.path("error").path("code").asInt());
        assertEquals("Invalid requestState", replay.path("error").path("message").asText());
    }

    @Test
    void answersSentWithoutAStateAreTheClientsOwnAndExtraOrMissingKeysAreHandled() {
        ObjectNode both = accept("input-1", "name", "Ada");
        both.setAll(accept("input-2", "colour", "Red"));
        both.setAll(accept("unrelated", "x", "y"));
        JsonNode done = call("demo.demo.inline", "{}", both, null).path("result");
        assertEquals("Hello, Ada! Red it is.", done.path("structuredContent").path("greeting").asText(), done.toString());

        JsonNode again = call("demo.demo.inline", "{}", accept("wrong", "name", "Ada"), null).path("result");
        assertEquals("input_required", again.path("resultType").asText());
        assertTrue(again.path("inputRequests").has("input-1"));
    }

    @Test
    void malformedInputResponsesAreAProtocolError() {
        ObjectNode params = Fixtures.Server.params(null);
        ((ObjectNode) params.path("_meta").path("io.modelcontextprotocol/clientCapabilities")).putObject("elicitation");
        params.put("name", "demo.demo.inline");
        params.putObject("arguments");
        params.putObject("inputResponses").put("input-1", 12345);
        assertEquals(-32602, server.request("tools/call", params).path("error").path("code").asInt());
        params.putNull("inputResponses");
        assertEquals(-32602, server.request("tools/call", params).path("error").path("code").asInt());
        params.remove("inputResponses");
        params.put("requestState", 7);
        assertEquals(-32602, server.request("tools/call", params).path("error").path("code").asInt());
    }

    @Test
    void aClientWithoutElicitationGetsMissingCapability() {
        JsonNode error = server.call("demo.demo.inline", "{}").path("error");
        assertEquals(-32021, error.path("code").asInt(), error.toString());
        assertTrue(error.path("data").path("requiredCapabilities").path("elicitation").isObject());
    }

    @Test
    void declinedOrInvalidAnswersEndTheTool() {
        ObjectNode decline = Fixtures.JSON.createObjectNode();
        decline.putObject("input-1").put("action", "decline");
        JsonNode declined = call("demo.demo.inline", "{}", decline, null).path("result");
        assertEquals("CANCELLED", declined.path("structuredContent").path("error").path("code").asText(), declined.toString());

        ObjectNode wrong = Fixtures.JSON.createObjectNode();
        ObjectNode answer = wrong.putObject("input-1");
        answer.put("action", "accept");
        answer.putObject("content").put("name", 42);
        JsonNode invalid = call("demo.demo.inline", "{}", wrong, null).path("result");
        assertEquals("INVALID_ARGUMENT", invalid.path("structuredContent").path("error").path("code").asText(), invalid.toString());
    }

    @Test
    void aHandlerThatCatchesTheSignalStillEndsTheRoundAskingForInput() {
        JsonNode first = call("demo.demo.swallow", "{}", null, null).path("result");
        assertEquals("input_required", first.path("resultType").asText(), first.toString());
        JsonNode done = call("demo.demo.swallow", "{}", accept("input-1", "name", "Ada"), first.path("requestState").asText()).path("result");
        assertEquals("Ada", done.path("structuredContent").path("name").asText(), done.toString());
    }
}
