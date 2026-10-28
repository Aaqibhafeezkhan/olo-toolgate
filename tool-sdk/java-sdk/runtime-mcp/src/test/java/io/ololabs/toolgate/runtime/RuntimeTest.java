// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Conformance: the java target of the runtime, error-model and context cases. */
class RuntimeTest {
    static ClassLoader demo;
    static Path devJson;
    static Fixtures.Server server;
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    @BeforeAll
    static void start() throws IOException {
        demo = Fixtures.fixture("demo");
        devJson = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(devJson, """
                {"mode":"development","secrets":{"ApiKey":"sk-live-123"},"claims":{"preferred_username":"dev.user","sub":"dev-1"}}
                """);
        server = new Fixtures.Server(demo, env());
    }

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>(Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", devJson.toString()));
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    static String sha256(String resource) throws IOException {
        try (InputStream in = demo.getResourceAsStream(resource)) {
            return StandaloneRuntime.sha256(in.readAllBytes());
        }
    }

    // ---- results ----

    @Test
    void echoReturnsStructuredContentWithDefaultsApplied() {
        JsonNode result = server.call("demo.demo.echo", "{\"text\":\"hi\",\"mood\":\"HAPPY\"}").path("result");
        assertEquals("complete", result.path("resultType").asText());
        assertFalse(result.path("isError").asBoolean());
        assertEquals("{\"text\":\"hi\",\"count\":1,\"mood\":\"HAPPY\",\"tags\":[]}", result.path("structuredContent").toString());
        assertEquals(result.path("structuredContent").toString(), result.path("content").get(0).path("text").asText());
        assertEquals("fixture", result.path("_meta").path("io.ololabs.toolgate/identity").asText());
        JsonNode dated = server.call("demo.demo.echo", "{\"text\":\"a\",\"count\":3,\"on\":\"2026-10-11\",\"mood\":\"SAD\",\"tags\":[\"x\"]}");
        assertEquals("{\"text\":\"aaa\",\"count\":3,\"on\":\"2026-10-11\",\"mood\":\"SAD\",\"tags\":[\"x\"]}",
                dated.path("result").path("structuredContent").toString());
    }

    @Test
    void invalidArgumentsNeverReachTheHandler() {
        for (String args : List.of("{\"text\":\"\",\"mood\":\"HAPPY\"}", "{\"text\":\"a\",\"mood\":\"ANGRY\"}",
                "{\"text\":\"a\",\"mood\":\"HAPPY\",\"extra\":1}", "{\"text\":\"a\",\"mood\":\"HAPPY\",\"count\":11}",
                "{\"mood\":\"HAPPY\"}", "{\"text\":\"a\",\"mood\":\"HAPPY\",\"on\":\"yesterday\"}")) {
            JsonNode error = server.error("demo.demo.echo", args);
            assertEquals("INVALID_ARGUMENT", error.path("code").asText(), args);
            assertFalse(error.path("retryable").asBoolean());
        }
    }

    @Test
    void longOutsideTheSafeRangeIsRejected() {
        assertEquals("INVALID_ARGUMENT", server.error("demo.demo.grouped", "{\"name\":\"n\",\"size\":9007199254740992}").path("code").asText());
        JsonNode ok = server.call("demo.demo.grouped", "{\"name\":\"n\",\"size\":9007199254740991}").path("result").path("structuredContent");
        assertEquals(9007199254740991L, ok.path("size").asLong());
    }

    @Test
    void groupedInputsReadThroughTheContext() {
        JsonNode out = server.call("demo.demo.grouped", "{\"name\":\"n\"}").path("result").path("structuredContent");
        assertEquals(7, out.path("size").asLong());
        assertEquals("acme.demo/demo.grouped", out.path("tool").asText());
        assertEquals("STANDALONE", out.path("mode").asText());
    }

    @Test
    void sizeLimitsAreEnforced() {
        String big = "a".repeat(StandaloneRuntime.MAX_INPUT_BYTES);
        assertEquals("INVALID_ARGUMENT", server.error("demo.demo.echo", "{\"text\":\"" + big + "\",\"mood\":\"HAPPY\"}").path("code").asText());
        JsonNode error = server.error("demo.demo.big", "{\"bytes\":" + (StandaloneRuntime.MAX_OUTPUT_BYTES + 1) + "}");
        assertEquals("INTERNAL_TOOL_ERROR", error.path("code").asText());
        assertEquals("The tool failed.", error.path("message").asText());
        assertFalse(server.call("demo.demo.big", "{\"bytes\":1000}").path("result").path("isError").asBoolean());
    }

    // ---- error model ----

    @Test
    void errorsMapToTheirCodes() {
        record Case(String kind, String code, boolean retryable) {}
        for (Case c : List.of(new Case("input", "INVALID_ARGUMENT", false), new Case("business", "NOT_FOUND", false),
                new Case("undeclared", "UNDECLARED_ERROR", false), new Case("retryable", "BUSY", true),
                new Case("upstream", "UPSTREAM_5XX", true), new Case("config", "CONFIGURATION", false),
                new Case("platform", "ROUTE_REFUSED", false), new Case("null", "INTERNAL_TOOL_ERROR", false),
                new Case("bad-data", "INTERNAL_TOOL_ERROR", false), new Case("undeclared-global", "CONFIGURATION", false),
                new Case("wrong-type", "CONFIGURATION", false))) {
            JsonNode result = server.call("demo.demo.fail", "{\"kind\":\"" + c.kind + "\"}").path("result");
            JsonNode error = result.path("structuredContent").path("error");
            assertTrue(result.path("isError").asBoolean(), c.kind);
            assertEquals("complete", result.path("resultType").asText());
            assertEquals(c.code, error.path("code").asText(), c.kind);
            assertEquals(c.retryable, error.path("retryable").asBoolean(), c.kind);
            assertEquals(error.path("message").asText(), result.path("content").get(0).path("text").asText());
            assertTrue(error.path("data").isObject(), c.kind);
        }
        assertEquals("{\"id\":\"123456\"}", server.error("demo.demo.fail", "{\"kind\":\"business\"}").path("data").toString());
        assertEquals("SURPRISE", server.error("demo.demo.fail", "{\"kind\":\"undeclared\"}").path("data").path("originalCode").asText());
        JsonNode upstream = server.error("demo.demo.fail", "{\"kind\":\"upstream\"}").path("data");
        assertEquals(503, upstream.path("status").asInt());
        assertFalse(upstream.path("effectUncertain").asBoolean());
        JsonNode uncaught = server.error("demo.demo.fail", "{\"kind\":\"null\"}");
        assertEquals("The tool failed.", uncaught.path("message").asText());
        assertFalse(uncaught.toString().contains("NullPointerException"));
        assertTrue(server.diagnostics().contains("NullPointerException"));
    }

    @Test
    void retryableIsDowngradedForLedgerTools() {
        JsonNode error = server.error("demo.demo.write", "{}");
        assertEquals("BUSY", error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(server.diagnostics().contains("BUSY reported as not retryable"));
    }

    @Test
    void outputMustMatchTheOutputSchema() {
        assertFalse(server.call("demo.demo.strict", "{\"v\":\"x\"}").path("result").path("isError").asBoolean());
        assertEquals("INTERNAL_TOOL_ERROR", server.error("demo.demo.strict", "{\"v\":\"y\"}").path("code").asText());
    }

    @Test
    void messagesAndDataAreCapped() {
        JsonNode error = server.error("demo.demo.fail", "{\"kind\":\"long\"}");
        assertEquals(StandaloneRuntime.MAX_MESSAGE_BYTES, error.path("message").asText().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("{\"truncated\":true}", error.path("data").toString());
    }

    @Test
    void secretsAndSensitiveInputsAreRedacted() {
        JsonNode error = server.error("demo.demo.secret", "{\"token\":\"fail\"}");
        assertEquals("key [REDACTED] and token [REDACTED]", error.path("message").asText());
        server.call("demo.demo.secret", "{\"token\":\"tok-987\"}");
        String log = server.diagnostics();
        assertTrue(log.contains("key [REDACTED] token [REDACTED]"), log);
        assertFalse(log.contains("sk-live-123"));
        assertFalse(log.contains("tok-987"));
    }

    // ---- execution ----

    @Test
    void concurrencyLimitFailsAfterAboutOneSecond() throws Exception {
        Future<JsonNode> first = POOL.submit(() -> server.call("demo.demo.slow", "{\"ms\":2000}"));
        Thread.sleep(200);
        long start = System.nanoTime();
        JsonNode second = server.error("demo.demo.slow", "{\"ms\":10}");
        long waited = (System.nanoTime() - start) / 1_000_000;
        assertEquals("CONCURRENCY_LIMIT", second.path("code").asText());
        assertTrue(second.path("retryable").asBoolean());
        assertTrue(waited >= 900 && waited < 1900, "waited " + waited);
        assertFalse(first.get().path("result").path("isError").asBoolean());
    }

    @Test
    void deadlineExceeded() {
        long start = System.nanoTime();
        JsonNode error = server.error("demo.demo.slow", "{\"ms\":6000}");
        long waited = (System.nanoTime() - start) / 1_000_000;
        assertEquals("DEADLINE_EXCEEDED", error.path("code").asText());
        assertTrue(error.path("retryable").asBoolean());
        assertTrue(waited >= 2900 && waited < 4500, "waited " + waited);
    }

    @Test
    void concurrentInvocationsSeeOnlyTheirOwnArguments() throws Exception {
        List<Future<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String text = "t" + i;
            calls.add(POOL.submit(() -> server.call("demo.demo.echo", "{\"text\":\"" + text + "\",\"mood\":\"HAPPY\"}")));
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("t" + i, calls.get(i).get().path("result").path("structuredContent").path("text").asText());
        }
    }

    // ---- configuration and identity ----

    @Test
    void globalsComeFromTheEnvironmentAndAreValidated() {
        assertEquals(5, server.call("demo.demo.secret", "{\"token\":\"t\"}").path("result").path("structuredContent").path("limit").asInt());
        Fixtures.Server nine = new Fixtures.Server(demo, env("TOOLGATE_GLOBAL_LIMIT", "9"));
        assertEquals(9, nine.call("demo.demo.secret", "{\"token\":\"t\"}").path("result").path("structuredContent").path("limit").asInt());
        StartupRefused refused = refused(env("TOOLGATE_GLOBAL_LIMIT", "0"));
        assertTrue(refused.getMessage().contains("global Limit is invalid"), refused.getMessage());
        assertTrue(refused(env("TOOLGATE_GLOBAL_LIMIT", "many")).getMessage().contains("not a valid INT"));
    }

    @Test
    void developmentClaimsComeFromFixtures() {
        JsonNode out = server.call("demo.demo.whoami", "{}").path("result").path("structuredContent");
        assertEquals("dev.user", out.path("user").asText());
        assertEquals("dev-1", out.path("subject").asText());
    }

    @Test
    void startupRefusals() throws IOException {
        StartupRefused production = refused(env("TOOLGATE_MODE", "production"));
        assertEquals("CONFIGURATION", production.code());
        assertTrue(production.getMessage().contains("acme.demo/demo.whoami"), production.getMessage());
        assertEquals("CONFIGURATION", refused(env("TOOLGATE_MODE", "test")).code());
        assertTrue(refused(env("TOOLGATE_AUTH_ISSUER", "https://login.example")).getMessage().contains("production issuer"));

        ClassLoader client = Fixtures.compile(Map.of("acme/c/Tools.java", """
                package acme.c;
                import io.ololabs.toolgate.sdk.annotations.*;
                @ToolPackage(id = "acme.client", name = "C", publisher = "Acme")
                public class Tools {
                    @ClientTool(name = "files.read", title = "T", description = "D")
                    public java.util.Map<String, Object> read() { return java.util.Map.of(); }
                }
                """));
        assertEquals("CLIENT_TOOLS_UNAVAILABLE", refused(client, env()).code());

        ClassLoader keyed = Fixtures.compile(Map.of("acme/k/Tools.java", """
                package acme.k;
                import io.ololabs.toolgate.sdk.annotations.*;
                @ToolPackage(id = "acme.keyed", name = "K", publisher = "Acme")
                @Global(name = "Region", type = ValueType.STRING)
                public class Tools {
                    @ServerTool(name = "orders.cancel", title = "T", description = "D", effect = EffectSafety.NON_IDEMPOTENT, destructive = true,
                            reconciliation = Reconciliation.MANUAL)
                    public java.util.Map<String, Object> cancel() { return java.util.Map.of(); }
                }
                """));
        // A tool that needs reservations never runs unreserved: a store that cannot open refuses startup.
        String notAFile = Files.createTempDirectory("toolgate-state").toString();
        assertTrue(refused(keyed, env("TOOLGATE_STATE_DB", notAFile)).getMessage().contains("business-key store"));

        ClassLoader required = Fixtures.compile(Map.of("acme/r/Tools.java", """
                package acme.r;
                import io.ololabs.toolgate.sdk.annotations.*;
                @ToolPackage(id = "acme.required", name = "R", publisher = "Acme")
                @Global(name = "Region", type = ValueType.STRING)
                public class Tools {
                    @ServerTool(name = "orders.list", title = "T", description = "D")
                    public java.util.Map<String, Object> list() { return java.util.Map.of(); }
                }
                """));
        assertTrue(refused(required, env()).getMessage().contains("global Region is required"));
        new Fixtures.Server(required, env("TOOLGATE_GLOBAL_REGION", "eu"));
    }

    @Test
    void theStartupBannerSaysThereIsNoAudit() {
        assertTrue(server.diagnostics().contains("There is no enterprise audit in standalone mode"));
        assertTrue(server.diagnostics().contains("WARNING development mode"));
    }

    private static StartupRefused refused(Map<String, String> env) {
        return refused(demo, env);
    }

    private static StartupRefused refused(ClassLoader loader, Map<String, String> env) {
        try {
            new Fixtures.Server(loader, env);
        } catch (StartupRefused e) {
            return e;
        }
        throw new AssertionError("the runtime started");
    }

    // ---- protocol ----

    @Test
    void discoverReportsVersionsAndDigests() throws IOException {
        JsonNode result = server.request("server/discover", Fixtures.Server.params(null)).path("result");
        assertEquals("[\"2026-07-28\"]", result.path("supportedVersions").toString());
        assertEquals("complete", result.path("resultType").asText());
        assertEquals("acme.demo", result.path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asText());
        JsonNode digests = result.path("_meta").path("io.ololabs.toolgate/descriptorDigests");
        assertEquals(sha256("META-INF/toolgate/tools/demo.echo.json"), digests.path("acme.demo/demo.echo").asText());
        assertTrue(result.path("_meta").path("io.ololabs.toolgate/descriptorSetDigest").asText().startsWith("sha256:"));
    }

    @Test
    void toolsListServesTheDescriptorSchemasExactly() throws IOException {
        JsonNode tools = server.request("tools/list", Fixtures.Server.params(null)).path("result").path("tools");
        JsonNode echo = null;
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) {
            names.add(t.path("name").asText());
            if (t.path("name").asText().equals("demo.demo.echo")) echo = t;
        }
        assertFalse(names.contains("demo.demo.export"), "async tools are hidden without Tasks");
        JsonNode descriptor;
        try (InputStream in = demo.getResourceAsStream("META-INF/toolgate/tools/demo.echo.json")) {
            descriptor = Fixtures.JSON.readTree(in);
        }
        assertEquals(descriptor.path("inputSchema"), echo.path("inputSchema"));
        assertEquals(descriptor.path("outputSchema"), echo.path("outputSchema"));
        assertTrue(echo.path("annotations").path("readOnlyHint").asBoolean());
    }

    @Test
    void perRequestMetaIsRequired() {
        ObjectNode noVersion = Fixtures.JSON.createObjectNode();
        noVersion.putObject("_meta").putObject("io.modelcontextprotocol/clientCapabilities");
        assertEquals(-32602, server.request("tools/list", noVersion).path("error").path("code").asInt());
        ObjectNode noCapabilities = Fixtures.JSON.createObjectNode();
        noCapabilities.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        assertEquals(-32602, server.request("tools/list", noCapabilities).path("error").path("code").asInt());
        ObjectNode old = Fixtures.Server.params(null);
        ((ObjectNode) old.get("_meta")).put("io.modelcontextprotocol/protocolVersion", "2024-11-05");
        JsonNode error = server.request("tools/list", old).path("error");
        assertEquals(-32022, error.path("code").asInt());
        assertEquals("[\"2026-07-28\"]", error.path("data").path("supported").toString());
        assertEquals(-32601, server.request("ping", Fixtures.Server.params(null)).path("error").path("code").asInt());
        ObjectNode unknown = Fixtures.Server.params(null);
        unknown.put("name", "demo.nope");
        assertEquals(-32602, server.request("tools/call", unknown).path("error").path("code").asInt());
    }

    @Test
    void asyncToolsNeedTheTasksExtension() {
        JsonNode error = server.call("demo.demo.export", "{}").path("error");
        assertEquals(-32021, error.path("code").asInt());
        assertTrue(error.path("data").path("requiredCapabilities").path("extensions").has("io.modelcontextprotocol/tasks"), error.toString());
    }
}
