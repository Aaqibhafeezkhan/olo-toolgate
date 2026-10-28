// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class StdioServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    /** A catalog with one tool that echoes its arguments. */
    private static final ToolCatalog CATALOG = new ToolCatalog() {
        @Override
        public ObjectNode serverInfo() {
            ObjectNode info = JSON.createObjectNode();
            info.put("name", "test");
            info.put("version", "1");
            return info;
        }

        @Override
        public ObjectNode discoverMeta() {
            return JSON.createObjectNode();
        }

        @Override
        public ArrayNode tools(RequestMeta meta) {
            ArrayNode tools = JSON.createArrayNode();
            tools.addObject().put("name", "echo");
            return tools;
        }

        @Override
        public ObjectNode call(String name, JsonNode arguments, RequestMeta meta) {
            if (!name.equals("echo")) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Unknown tool");
            ObjectNode result = JSON.createObjectNode();
            result.set("structuredContent", arguments);
            result.put("isError", false);
            return result;
        }
    };

    private static List<JsonNode> serve(String... lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioServer(new ProtocolCore(CATALOG)).serve(
                new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)), out);
        List<JsonNode> responses = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty()) responses.add(JSON.readTree(line));
        responses.sort(Comparator.comparing(r -> r.path("id").asText("")));
        return responses;
    }

    @Test
    void servesEachRequestAsOneLine() throws IOException {
        List<JsonNode> responses = serve(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + META + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"a\":1}," + META + "}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\",\"params\":{}}");
        assertEquals(2, responses.size());
        JsonNode list = responses.get(0).path("result");
        assertEquals("echo", list.path("tools").path(0).path("name").asText());
        assertEquals("complete", list.path("resultType").asText());
        assertEquals("test", list.path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asText());
        assertEquals("{\"a\":1}", responses.get(1).path("result").path("structuredContent").toString());
    }

    @Test
    void parseErrorsAndBadEnvelopes() throws IOException {
        List<JsonNode> responses = serve("{not json", "{\"jsonrpc\":\"1.0\",\"id\":\"x\",\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"y\",\"method\":\"tools/list\",\"params\":{},\"extra\":1}");
        assertEquals(3, responses.size());
        assertEquals(-32700, responses.get(0).path("error").path("code").asInt());
        assertTrue(responses.get(0).path("id").isNull());
        assertEquals(-32600, responses.get(1).path("error").path("code").asInt());
        assertEquals(-32600, responses.get(2).path("error").path("code").asInt());
    }

    @Test
    void theConnectionIsLockedToItsFirstEra() throws IOException {
        ProtocolCore core = new ProtocolCore(CATALOG);
        ProtocolCore.Connection connection = new ProtocolCore.Connection("stdio");
        JsonNode first = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + META + "}}"), connection);
        assertTrue(first.has("result"));
        JsonNode initialize = core.handle(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}"), connection);
        assertEquals(-32022, initialize.path("error").path("code").asInt());
        assertEquals("[\"2026-07-28\"]", initialize.path("error").path("data").path("supported").toString());
        assertEquals("2025-11-25", initialize.path("error").path("data").path("requested").asText());
    }

    @Test
    void perRequestMetaIsRequired() throws IOException {
        ProtocolCore core = new ProtocolCore(CATALOG);
        ProtocolCore.Connection connection = new ProtocolCore.Connection("stdio");
        JsonNode missing = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"), connection);
        assertEquals(-32602, missing.path("error").path("code").asInt());
        JsonNode wrong = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{\"_meta\":"
                + "{\"io.modelcontextprotocol/protocolVersion\":\"2099-01-01\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}"), connection);
        assertEquals(-32022, wrong.path("error").path("code").asInt());
        JsonNode extra = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\","
                + "\"cursor\":\"x\"," + META + "}}"), connection);
        assertTrue(extra.has("result"), "unrecognised tools/call members are ignored");
        JsonNode noName = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{" + META + "}}"), connection);
        assertEquals(-32602, noName.path("error").path("code").asInt());
        JsonNode unknown = core.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\",\"params\":{" + META + "}}"), connection);
        assertEquals(-32601, unknown.path("error").path("code").asInt());
    }

    @Test
    void anOverlongLineIsRefusedAndServingContinues() throws IOException {
        String big = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"a\":\""
                + "x".repeat(StdioServer.MAX_LINE) + "\"}," + META + "}}";
        List<JsonNode> responses = serve(big, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + META + "}}");
        assertEquals(2, responses.size());
        assertEquals(-32600, responses.get(0).path("error").path("code").asInt());
        assertEquals("[\"2026-07-28\"]", responses.get(1).path("result").path("supportedVersions").toString());
    }
}
