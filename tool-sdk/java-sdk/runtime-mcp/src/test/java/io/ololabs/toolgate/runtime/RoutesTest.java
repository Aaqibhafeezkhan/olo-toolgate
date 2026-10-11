// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.sdk.context.HttpClient;
import io.ololabs.toolgate.sdk.context.RouteCall;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The routes HTTP client (http.md) against a local upstream, in development mode over plain loopback HTTP. */
class RoutesTest {
    static final String KEY = "fixture-api-value";
    static final String TOKEN = "fixture-access-value";
    static ClassLoader shop;
    static HttpServer upstream;
    static Fixtures.Server server;
    static final List<String> seen = new CopyOnWriteArrayList<>();
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static final AtomicInteger refunds = new AtomicInteger();

    @BeforeAll
    static void start() throws IOException {
        shop = Fixtures.fixture("routes");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", RoutesTest::handle);
        upstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        upstream.start();
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"destinations\":{\"ShopApiHost\":\"" + base + "\"},\"secrets\":{\"ShopKey\":\"" + KEY + "\","
                + "\"ShopBasic\":{\"username\":\"ann\",\"password\":\"pw-secret-42\"},"
                + "\"ShopClient\":{\"tokenUrl\":\"" + base + "/token\",\"clientId\":\"shop-client\",\"clientSecret\":\"cs-secret-77\"}}}");
        server = new Fixtures.Server(shop, Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString()));
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    static void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getRawPath();
        String query = x.getRequestURI().getRawQuery();
        byte[] in = x.getRequestBody().readAllBytes();
        seen.add(x.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + new String(in, StandardCharsets.UTF_8));
        String auth = x.getRequestHeaders().getFirst("Authorization");
        try {
            if (path.equals("/token")) {
                tokenRequests.incrementAndGet();
                String form = new String(in, StandardCharsets.UTF_8);
                boolean ok = ("Basic " + b64("shop-client:cs-secret-77")).equals(auth) && form.contains("grant_type=client_credentials")
                        && form.contains("scope=refunds%3Awrite") && form.contains("audience=https%3A%2F%2Fshop.test");
                reply(x, ok ? 200 : 401, ok ? "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600}" : "{}");
            } else if (path.startsWith("/files/")) {
                reply(x, ("Basic " + b64("ann:pw-secret-42")).equals(auth) ? 200 : 401, "file " + path.substring(7));
            } else if (path.endsWith("/refunds")) {
                if (!("Bearer " + TOKEN).equals(auth)) {
                    reply(x, 401, "{}");
                } else if (path.contains("222222")) {
                    refunds.incrementAndGet();
                    sleep(3000); // accepted the request and never answers in time
                    reply(x, 200, "{}");
                } else {
                    refunds.incrementAndGet();
                    reply(x, 200, "{\"refunded\":true,\"body\":" + new String(in, StandardCharsets.UTF_8) + "}");
                }
            } else if (path.startsWith("/orders/")) {
                String id = path.substring("/orders/".length());
                boolean keyed = KEY.equals(x.getRequestHeaders().getFirst("X-Api-Key"));
                switch (id) {
                    case "404404" -> {
                        x.getResponseHeaders().add("X-Request-Id", "req-404");
                        reply(x, 404, "{\"message\":\"no such order\"}");
                    }
                    case "503503" -> reply(x, 503, "busy");
                    case "777777" -> {
                        sleep(2500);
                        reply(x, 200, "{}");
                    }
                    case "888888" -> reply(x, 200, "\"" + "z".repeat(10_000) + "\"");
                    default -> {
                        String expand = query != null && query.startsWith("expand=") ? query.substring(7) : null;
                        String trace = x.getRequestHeaders().getFirst("X-Trace");
                        reply(x, 200, "{\"id\":\"" + id + "\",\"expand\":" + (expand == null ? "null" : "\"" + expand + "\"")
                                + ",\"trace\":" + (trace == null ? "null" : "\"" + trace + "\"") + ",\"keyed\":" + keyed + "}");
                    }
                }
            } else {
                reply(x, 200, "\"admin\"");
            }
        } catch (IOException e) {
            // the client gave up first
        }
    }

    static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode result(String tool, String args) {
        return server.call(tool, args).path("result");
    }

    static JsonNode order(String id, String how) {
        return result("shop.shop.order", "{\"id\":\"" + id + "\",\"how\":\"" + how + "\"}");
    }

    static JsonNode orderError(String id, String how) {
        return server.error("shop.shop.order", "{\"id\":\"" + id + "\",\"how\":\"" + how + "\"}");
    }

    @Test
    void aDeclaredRouteCallsTheUpstreamWithItsCredential() {
        JsonNode order = order("123456", "plain").path("structuredContent").path("order");
        assertEquals("123456", order.path("id").asText());
        assertEquals("lines", order.path("expand").asText(), "a null query value is omitted");
        assertEquals("t-1", order.path("trace").asText());
        assertTrue(order.path("keyed").asBoolean(), "the API key comes from the local secret");
        assertEquals("file abc", result("shop.shop.file", "{\"name\":\"abc\"}").path("structuredContent").path("text").asText(), "basic auth");
        assertEquals(200, order("123456", "raw").path("structuredContent").path("status").asInt());
    }

    @Test
    void theConvenienceFormResolvesToExactlyOneListedRoute() {
        assertEquals("123456", order("123456", "convenience").path("structuredContent").path("order").path("id").asText());
        JsonNode e = orderError("123456", "unlisted-template");
        assertEquals("ROUTE_REFUSED", e.path("code").asText());
        assertTrue(e.path("message").asText().contains("matches no route"), e.toString());
    }

    @Test
    void undeclaredRoutesAreRefused() {
        int before = seen.size();
        JsonNode e = orderError("123456", "undeclared");
        assertEquals("ROUTE_REFUSED", e.path("code").asText());
        assertFalse(e.path("retryable").asBoolean());
        assertEquals("ROUTE_REFUSED", orderError("123456", "unlisted-query").path("code").asText());
        assertEquals("ROUTE_REFUSED", orderError("123456", "missing-var").path("code").asText());
        assertEquals(before, seen.size(), "nothing reached the upstream");
    }

    @Test
    void pathVariablesMustMatchTheirPatternAndNeverMatchASlash() {
        int before = seen.size();
        assertEquals("ROUTE_REFUSED", orderError("12ab56", "plain").path("code").asText());
        assertEquals("ROUTE_REFUSED", orderError("123456/../admin", "plain").path("code").asText());
        // files.get's pattern [a-z/]+ admits "/" but a variable never matches it.
        JsonNode e = server.error("shop.shop.file", "{\"name\":\"a/b\"}");
        assertEquals("ROUTE_REFUSED", e.path("code").asText());
        assertEquals(before, seen.size());
    }

    @Test
    void forbiddenAndUnlistedHeadersAreRefused() {
        for (String h : List.of("Authorization", "Proxy-Authorization", "Cookie", "Host", "X-Forwarded-For", "X-Api-Key", "X-Unlisted")) {
            JsonNode e = orderError("123456", h);
            assertEquals("ROUTE_REFUSED", e.path("code").asText(), h);
        }
    }

    @Test
    void upstreamFailuresMapToTheirCodes() {
        JsonNode e = orderError("404404", "plain");
        assertEquals("UPSTREAM_4XX", e.path("code").asText());
        assertEquals(404, e.path("data").path("status").asInt());
        assertEquals("req-404", e.path("data").path("upstreamRequestId").asText());
        assertEquals("orders.get", e.path("data").path("route").asText());
        assertTrue(e.path("retryable").asBoolean(), "READ_ONLY tools keep retryable");
        assertEquals("UPSTREAM_5XX", orderError("503503", "plain").path("code").asText());
        JsonNode caught = order("404404", "caught").path("structuredContent");
        assertEquals("UPSTREAM_4XX", caught.path("caught").asText());
        assertEquals("req-404", caught.path("requestId").asText());
        assertEquals("UPSTREAM_CONNECTION", orderError("888888", "plain").path("code").asText(), "maxResponseBytes");
    }

    @Test
    void aCallTimeoutNeverExtendsTheRouteTimeout() {
        long started = System.nanoTime();
        JsonNode e = orderError("777777", "long-timeout");
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertEquals("UPSTREAM_TIMEOUT", e.path("code").asText());
        assertTrue(ms < 2000, "the route's 1000 ms applied, took " + ms);
        assertTrue(e.path("data").path("effectUncertain").asBoolean(), "a GET counts as sent once connected");
    }

    @Test
    void aConnectionThatFailsBeforeSendIsNotUncertain() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"destinations\":{\"ShopApiHost\":\"http://127.0.0.1:" + closed + "\"},\"secrets\":{"
                + "\"ShopClient\":{\"tokenUrl\":\"http://127.0.0.1:" + upstream.getAddress().getPort() + "/token\",\"clientId\":\"shop-client\","
                + "\"clientSecret\":\"cs-secret-77\"}}}");
        Fixtures.Server down = new Fixtures.Server(shop, Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString()));
        JsonNode e = down.error("shop.shop.refund", "{\"id\":\"111111\"}");
        assertEquals("UPSTREAM_CONNECTION", e.path("code").asText());
        assertFalse(e.path("data").path("effectUncertain").asBoolean());
        assertFalse(e.path("retryable").asBoolean(), "a ledger tool is never retryable");
    }

    @Test
    void aLedgerToolWhoseRequestWasSentEndsOutcomeUnknown() throws Exception {
        JsonNode ok = result("shop.shop.refund", "{\"id\":\"111111\"}").path("structuredContent");
        assertTrue(ok.path("refunded").asBoolean());
        assertEquals("LATE", ok.path("body").path("reason").asText(), "the body is sent as JSON");
        int before = refunds.get();
        JsonNode e = server.error("shop.shop.refund", "{\"id\":\"222222\"}");
        assertEquals("OUTCOME_UNKNOWN", e.path("code").asText());
        assertFalse(e.path("retryable").asBoolean());
        assertTrue(e.path("data").path("effectUncertain").asBoolean());
        assertEquals("UPSTREAM_TIMEOUT", e.path("data").path("upstreamCode").asText());
        assertEquals(before + 1, refunds.get(), "never retried");

        JsonNode created = server.callTask("shop.shop.refund-later", "{\"id\":\"222222\"}").path("result");
        String taskId = created.path("taskId").asText();
        JsonNode task = null;
        for (int i = 0; i < 100; i++) {
            ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
            params.put("taskId", taskId);
            task = server.request("tasks/get", params).path("result");
            if (task.path("status").asText().equals("failed")) break;
            Thread.sleep(50);
        }
        assertEquals("failed", task.path("status").asText(), String.valueOf(task));
        assertEquals("OUTCOME_UNKNOWN", task.path("error").path("data").path("code").asText(), String.valueOf(task));
    }

    @Test
    void anOAuthTokenIsFetchedOnceAndRedacted() {
        assertTrue(result("shop.shop.refund", "{\"id\":\"111112\"}").path("structuredContent").path("refunded").asBoolean());
        int fetched = tokenRequests.get();
        assertTrue(fetched >= 1);
        result("shop.shop.refund", "{\"id\":\"111113\"}");
        assertEquals(fetched, tokenRequests.get(), "the token is cached until shortly before it expires");
        assertFalse(server.diagnostics().contains(TOKEN));
        assertFalse(server.diagnostics().contains(KEY));
    }

    @Test
    void destinationsAreCheckedAtStartup() throws IOException {
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"destinations\":{\"ShopApiHost\":\"http://127.0.0.1:8080\"}}");
        StartupRefused plain = assertThrows(StartupRefused.class, () -> start(Map.of("TOOLGATE_DEV_JSON", dev.toString())));
        assertTrue(plain.getMessage().contains("http://"), plain.getMessage());
        StartupRefused port = assertThrows(StartupRefused.class, () -> start(Map.of("TOOLGATE_DESTINATION_SHOPAPIHOST", "shop.example.test:8443")));
        assertTrue(port.getMessage().contains("declares port 443"), port.getMessage());
        assertThrows(StartupRefused.class, () -> start(Map.of("TOOLGATE_DESTINATION_SHOPAPIHOST", "https://shop.example.test/api")));
        start(Map.of("TOOLGATE_DESTINATION_SHOPAPIHOST", "shop.example.test"));
        start(Map.of("TOOLGATE_DESTINATION_SHOPAPIHOST", "shop.example.test:443"));

        // A missing host is a warning; calls on it fail with CONFIGURATION.
        Fixtures.Server unset = new Fixtures.Server(shop, Map.of("TOOLGATE_MODE", "development", "TOOLGATE_SECRET_SHOPKEY", KEY));
        assertTrue(unset.diagnostics().contains("TOOLGATE_DESTINATION_SHOPAPIHOST"), unset.diagnostics());
        assertEquals("CONFIGURATION", unset.error("shop.shop.order", "{\"id\":\"123456\",\"how\":\"plain\"}").path("code").asText());
    }

    static void start(Map<String, String> extra) {
        Map<String, String> env = new java.util.HashMap<>(extra);
        env.put("TOOLGATE_MODE", "production");
        StandaloneRuntime.start(shop, env, Path.of("build"), new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void spkiPinsDecideWhichServersAreTrusted() throws Exception {
        Path dir = Files.createTempDirectory("toolgate-tls");
        Path store = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                "-alias", "upstream", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1",
                "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
                .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertEquals(0, keytool.waitFor());
        java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        String pin = Egress.pin((java.security.cert.X509Certificate) keys.getCertificate("upstream"));
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "changeit".toCharArray());
        javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        https.createContext("/", x -> reply(x, 200, "{\"pinned\":true}"));
        https.start();
        try {
            int port = https.getAddress().getPort();
            assertTrue(pinned(port, pin).path("structuredContent").path("pinned").asBoolean(), "the pinned server is trusted");
            JsonNode refused = pinned(port, "sha256/" + Base64.getEncoder().encodeToString(new byte[32])).path("structuredContent").path("error");
            assertEquals("UPSTREAM_CONNECTION", refused.path("code").asText(), "a server without a matching pin is not");
            assertFalse(refused.path("data").path("effectUncertain").asBoolean());
        } finally {
            https.stop(0);
        }
    }

    /** Calls a tool whose destination pins {@code pin}, in production mode, against the local HTTPS server. */
    static JsonNode pinned(int port, String pin) {
        ClassLoader loader = Fixtures.compile(Map.of("acme/tls/TlsTools.java", """
                package acme.tls;

                import io.ololabs.toolgate.sdk.annotations.*;
                import io.ololabs.toolgate.sdk.context.ToolContext;
                import java.util.Map;

                @ToolPackage(id = "acme.tls", name = "Tls", publisher = "Acme")
                @Destination(id = "tls-api", hostSetting = "TlsApiHost", port = %d, spkiPins = "%s")
                @HttpRoute(id = "status.get", destination = "tls-api", method = HttpMethod.GET, path = "/status")
                public class TlsTools {
                    @ServerTool(name = "tls.status", title = "Status", description = "Reads status.", effect = EffectSafety.READ_ONLY,
                            routes = "status.get")
                    public Map<String, Object> status(ToolContext context) {
                        return context.http().route("status.get").send(Map.class);
                    }
                }
                """.formatted(port, pin)));
        Fixtures.Server s = new Fixtures.Server(loader, Map.of("TOOLGATE_MODE", "production", "TOOLGATE_DESTINATION_TLSAPIHOST", "127.0.0.1:" + port));
        return s.call("tls.tls.status", "{}").path("result");
    }

    @Test
    void thereIsNoFreeFormUrlApi() {
        for (Class<?> type : List.of(HttpClient.class, RouteCall.class)) {
            for (Method m : type.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("url") || name.contains("uri") || name.contains("host") || name.contains("base"), type + "#" + m.getName());
            }
        }
    }
}
