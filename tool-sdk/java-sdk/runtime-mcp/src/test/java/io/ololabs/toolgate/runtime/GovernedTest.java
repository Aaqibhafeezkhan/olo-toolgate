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
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Governed.run(loader, env, new ByteArrayInputStream((input + "\n").getBytes(StandardCharsets.UTF_8)), out,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n") && text.indexOf('\n') == text.length() - 1, "exactly one frame line: " + text + err);
        return Fixtures.JSON.readTree(text);
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

    @Test
    void routeCallsGoToTheBrokerWithTheCapability() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        Path socket = Files.createTempDirectory("broker").resolve("broker.sock");
        Broker broker = new Broker(socket, request -> {
            seen.add(request);
            if (request.contains("\"424242\"")) return "{\"refused\":\"route orders.get is not in this permit\"}";
            return "{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":{\"id\":\"123456\",\"expand\":\"lines\",\"trace\":\"t-1\",\"keyed\":true}}";
        });
        try {
            Map<String, String> env = Map.of("TOOLGATE_BROKER_SOCKET", socket.toString(), "TOOLGATE_BROKER_CAPABILITY", "cap-1");
            JsonNode frame = run(shop, env, input("acme.shop/shop.order", "{\"id\":\"123456\",\"how\":\"plain\"}"));
            assertFalse(frame.path("isError").asBoolean(), frame.toString());
            assertEquals("123456", frame.path("structuredContent").path("order").path("id").asText());
            String request = seen.get(0);
            assertTrue(request.startsWith("POST /v1/call HTTP/1.1\r\n"), request);
            assertTrue(request.contains("Toolgate-Capability: cap-1\r\n"), request);
            JsonNode body = Fixtures.JSON.readTree(request.substring(request.indexOf("\r\n\r\n") + 4));
            assertEquals("orders.get", body.path("route").asText());
            assertEquals("shop-key", body.path("auth").asText(), "the broker injects the credential the route names");
            assertEquals("123456", body.path("variables").path("orderId").asText());
            JsonNode refused = run(shop, env, input("acme.shop/shop.order", "{\"id\":\"424242\",\"how\":\"plain\"}"));
            assertEquals("ROUTE_REFUSED", refused.path("error").path("code").asText(), refused.toString());
            assertTrue(refused.path("error").path("message").asText().contains("not in this permit"), refused.toString());
        } finally {
            broker.close();
        }
        JsonNode gone = run(shop, Map.of("TOOLGATE_BROKER_SOCKET", socket.toString()), input("acme.shop/shop.order", "{\"id\":\"123456\",\"how\":\"plain\"}"));
        assertEquals("ROUTE_REFUSED", gone.path("error").path("code").asText(), gone.toString());
    }

    /** A one-request-per-connection HTTP/1.1 server on a Unix socket, standing in for the Tool Host broker. */
    static final class Broker implements AutoCloseable {
        private final ServerSocketChannel server;
        private final Thread thread;
        private final Path socket;

        Broker(Path socket, Function<String, String> answer) throws IOException {
            this.socket = socket;
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            thread = new Thread(() -> {
                while (server.isOpen()) {
                    try (SocketChannel c = server.accept()) {
                        ByteBuffer in = ByteBuffer.allocate(64 * 1024);
                        String request = "";
                        while (true) {
                            if (c.read(in) < 0) break;
                            request = new String(in.array(), 0, in.position(), StandardCharsets.UTF_8);
                            int end = request.indexOf("\r\n\r\n");
                            if (end < 0) continue;
                            int length = Integer.parseInt(request.replaceAll("(?s).*Content-Length: (\\d+).*", "$1"));
                            if (in.position() >= end + 4 + length) break;
                        }
                        byte[] body = answer.apply(request).getBytes(StandardCharsets.UTF_8);
                        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        c.write(ByteBuffer.wrap(head.getBytes(StandardCharsets.US_ASCII)));
                        c.write(ByteBuffer.wrap(body));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws IOException {
            server.close();
            Files.deleteIfExists(socket);
        }
    }
}
