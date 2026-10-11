// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Adapter A3: standalone {@code requestState}. */
class RequestStatesTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static RequestMeta meta(String owner) {
        return new RequestMeta(ProtocolCore.VERSION, JSON.createObjectNode(), Set.of(), "stdio", owner, null, n -> {}, JSON.createObjectNode());
    }

    static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    static JsonNode payload(String token) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void aTokenIsHs256AndBindsToolArgumentsIdentityAndScopeForAtMostTenMinutes() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        RequestStates states = new RequestStates(new byte[32], now::get);
        ObjectNode responses = JSON.createObjectNode();
        responses.putObject("input-1").put("action", "accept");
        String token = states.mint("acme.tool", json("{\"b\":1,\"a\":[1.5,\"x\"]}"), meta("alice"), responses);

        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertEquals("HS256", header.path("alg").asText());
        JsonNode p = payload(token);
        assertEquals("acme.tool", p.path("toolId").asText());
        assertEquals("alice", p.path("identity").asText());
        assertEquals("standalone", p.path("scope").asText());
        assertEquals(Jcs.digest(json("{\"a\":[1.5,\"x\"],\"b\":1}")), p.path("argsHash").asText());
        assertEquals((1_000_000 + RequestStates.LIFETIME_MS) / 1000, p.path("exp").asLong());

        // The same arguments in another member order verify, and the carried responses come back.
        assertEquals(responses, states.verify(token, "acme.tool", json("{\"a\":[1.5,\"x\"],\"b\":1}"), meta("alice")));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        RequestStates states = new RequestStates(new byte[32], now::get);
        String token = states.mint("t", json("{}"), meta("a"), JSON.createObjectNode());
        now.addAndGet(RequestStates.LIFETIME_MS);
        ProtocolError e = assertThrows(ProtocolError.class, () -> states.verify(token, "t", json("{}"), meta("a")));
        assertEquals(ProtocolError.INVALID_PARAMS, e.code());
    }

    @Test
    void aTokenFromAnotherProcessKeyIsRefused() throws Exception {
        String token = new RequestStates().mint("t", json("{}"), meta("a"), JSON.createObjectNode());
        RequestStates other = new RequestStates();
        assertThrows(ProtocolError.class, () -> other.verify(token, "t", json("{}"), meta("a")));
    }

    @Test
    void aKeyShorterThan256BitsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RequestStates(new byte[31], System::currentTimeMillis));
    }

    @Test
    void aForgedUnsignedOrReSignedPayloadIsRefused() throws Exception {
        RequestStates states = new RequestStates();
        String token = states.mint("t", json("{}"), meta("a"), JSON.createObjectNode());
        String[] parts = token.split("\\.");
        ObjectNode forged = (ObjectNode) payload(token);
        forged.put("identity", "mallory");
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(forged));
        assertThrows(ProtocolError.class, () -> states.verify(parts[0] + "." + body + "." + parts[2], "t", json("{}"), meta("mallory")));
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ProtocolError.class, () -> states.verify(none + "." + parts[1] + ".", "t", json("{}"), meta("a")));
        assertThrows(ProtocolError.class, () -> states.verify("not a token", "t", json("{}"), meta("a")));
    }

    @Test
    void canonicalFormFollowsRfc8785() throws Exception {
        assertEquals("{\"a\":1e+21,\"b\":0.000001,\"c\":\"\\u000f\\n\",\"d\":[true,null],\"e\":123456789012345678901234567890}",
                Jcs.canonical(json("{\"e\":123456789012345678901234567890,\"d\":[true,null],\"c\":\"\\u000f\\n\",\"b\":1e-6,\"a\":1e21}")));
        ObjectNode order = JsonNodeFactory.instance.objectNode();
        order.put("\u20ac", 1);
        order.put("\r", 2);
        order.put("\ud83d\ude00", 3);
        order.put("\u00f6", 4);
        // UTF-16 code unit order: \r, ö, €, then the surrogate pair (RFC 8785 §3.2.3).
        assertEquals("{\"\\r\":2,\"\u00f6\":4,\"\u20ac\":1,\"\ud83d\ude00\":3}", Jcs.canonical(order));
    }
}
