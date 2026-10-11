// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The TCP connector (http.md §6) against a local line server, in development mode over loopback. */
class TcpTest {
    static ClassLoader cache;
    static ServerSocket upstream;
    static Fixtures.Server server;

    @BeforeAll
    static void start() throws IOException {
        cache = Fixtures.fixture("tcp");
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (!upstream.isClosed()) {
                try (Socket s = upstream.accept()) {
                    String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    s.getOutputStream().write(("PING".equals(line) ? "+PONG" : "-ERR").concat("\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    // the next connection
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
        server = new Fixtures.Server(cache, env("TOOLGATE_DESTINATION_CACHEHOST", "127.0.0.1:" + upstream.getLocalPort()));
    }

    @AfterAll
    static void stop() throws IOException {
        upstream.close();
    }

    static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>(Map.of("TOOLGATE_MODE", "development"));
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    static JsonNode reply(Fixtures.Server s, String tool, String arguments) {
        JsonNode result = s.call(tool, arguments).path("result");
        assertTrue(!result.path("isError").asBoolean(), result.toString());
        return result.path("structuredContent");
    }

    @Test
    void aToolConnectsOnlyToItsDeclaredDestination() {
        assertEquals("+PONG", reply(server, "cache.cache.ping", "{}").path("reply").asText());
        JsonNode stray = server.error("cache.cache.stray", "{}");
        assertEquals("ROUTE_REFUSED", stray.path("code").asText());
        assertTrue(stray.path("message").asText().contains("not declared for this tool"), stray.toString());
    }

    @Test
    void theSocketFactoryConnectsOnlyToTheResolvedAddress() {
        String port = String.valueOf(upstream.getLocalPort());
        assertEquals("+PONG", reply(server, "cache.cache.driver", "{\"host\":\"127.0.0.1\",\"port\":" + port + "}").path("reply").asText());
        JsonNode elsewhere = server.error("cache.cache.driver", "{\"host\":\"192.0.2.10\",\"port\":" + port + "}");
        assertEquals("ROUTE_REFUSED", elsewhere.path("code").asText(), elsewhere.toString());
        JsonNode otherPort = server.error("cache.cache.driver", "{\"host\":\"127.0.0.1\",\"port\":1}");
        assertEquals("ROUTE_REFUSED", otherPort.path("code").asText(), otherPort.toString());
    }

    @Test
    void aMissingHostFailsTheCallAndAWrongPortRefusesStartup() {
        Fixtures.Server unset = new Fixtures.Server(cache, env());
        assertEquals("CONFIGURATION", unset.error("cache.cache.ping", "{}").path("code").asText());
        assertTrue(unset.diagnostics().contains("TCP destination cache has no host"), unset.diagnostics());

        StartupRefused refused = assertThrows(StartupRefused.class,
                () -> new Fixtures.Server(cache, Map.of("TOOLGATE_DESTINATION_CACHEHOST", "cache.internal:6380")));
        assertTrue(refused.getMessage().contains("declares port 6379"), refused.getMessage());
        assertThrows(StartupRefused.class, () -> new Fixtures.Server(cache, env("TOOLGATE_DESTINATION_CACHEHOST", "redis://cache.internal")));
    }
}
