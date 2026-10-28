// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.StreamableHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Production mode with the identity verifier (adapter A4): bearer tokens over HTTP, claims only from the token. */
class IdentityTest {
    static final String ISSUER = "https://login.acme.test";
    static final String AUDIENCE = "https://tools.acme.test/mcp";
    static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{\"io.modelcontextprotocol/tasks\":{}}}}";
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static ClassLoader demo;
    static KeyPair key;
    static Map<String, String> env;
    static ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    static StreamableHttpServer server;
    static URI uri;

    @BeforeAll
    static void start() throws Exception {
        demo = Fixtures.fixture("demo");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        Path keys = Files.createTempFile("toolgate.keys", ".json");
        Files.writeString(keys, "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\",\"n\":\"" + b64(unsigned(pub.getModulus()))
                + "\",\"e\":\"" + b64(unsigned(pub.getPublicExponent())) + "\"}]}");
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"claims\":{\"preferred_username\":\"dev.user\",\"sub\":\"dev-1\"}}");
        env = new HashMap<>(Map.of("TOOLGATE_MODE", "production", "TOOLGATE_AUTH_ISSUER", ISSUER, "TOOLGATE_AUTH_AUDIENCE", AUDIENCE,
                "TOOLGATE_AUTH_KEYS_FILE", keys.toString(), "TOOLGATE_HTTP_ADDRESS", "127.0.0.1:0", "TOOLGATE_DEV_JSON", dev.toString()));
        StandaloneRuntime runtime = StandaloneRuntime.start(demo, env, Path.of("build"), new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
        server = runtime.serveHttp(runtime.httpAddress());
        uri = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    static byte[] unsigned(BigInteger n) {
        byte[] raw = n.toByteArray();
        return raw[0] == 0 ? java.util.Arrays.copyOfRange(raw, 1, raw.length) : raw;
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** An RS256 token for {@code sub}, valid from a minute ago for {@code lifetimeSeconds}. */
    static String token(String sub, long lifetimeSeconds) throws GeneralSecurityException {
        long now = System.currentTimeMillis() / 1000;
        ObjectNode claims = Fixtures.JSON.createObjectNode();
        claims.put("iss", ISSUER).put("aud", AUDIENCE).put("sub", sub).put("preferred_username", sub + ".name")
                .put("nbf", now - 60).put("exp", now + lifetimeSeconds);
        String input = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(s.sign());
    }

    static HttpResponse<String> post(String bearer, String method, String name, String params) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Content-Type", "application/json").header("Accept", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method).header("Mcp-Name", name)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> whoami(String bearer) throws Exception {
        return post(bearer, "tools/call", "demo.demo.whoami", "{\"name\":\"demo.demo.whoami\",\"arguments\":{}," + META + "}");
    }

    @Test
    void claimsComeFromTheVerifiedToken() throws Exception {
        HttpResponse<String> r = whoami(token("alice", 300));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode result = Fixtures.JSON.readTree(r.body()).path("result");
        assertEquals("alice.name", result.path("structuredContent").path("user").asText(), r.body());
        assertEquals("alice", result.path("structuredContent").path("subject").asText());
        assertFalse(result.path("_meta").has("io.ololabs.toolgate/identity"), "no fixture marker outside development");
    }

    @Test
    void aMissingOrInvalidTokenGets401AtTheTransport() throws Exception {
        assertEquals(401, whoami(null).statusCode());
        assertEquals(401, whoami(token("alice", -61)).statusCode());
        assertEquals(401, whoami("not.a.token").statusCode());
        String good = token("alice", 300);
        assertEquals(401, whoami(good.substring(0, good.length() - 4) + "AAAA").statusCode());
    }

    @Test
    void tasksBelongToTheCallerThatCreatedThem() throws Exception {
        String alice = token("alice", 300);
        JsonNode created = Fixtures.JSON.readTree(post(alice, "tools/call", "demo.demo.refuse", "{\"name\":\"demo.demo.refuse\",\"arguments\":{}," + META + "}").body());
        String id = created.path("result").path("taskId").asText();
        assertFalse(id.isEmpty(), created.toString());
        String get = "{\"taskId\":\"" + id + "\"," + META + "}";
        assertEquals(id, Fixtures.JSON.readTree(post(alice, "tasks/get", id, get).body()).path("result").path("taskId").asText());
        JsonNode other = Fixtures.JSON.readTree(post(token("bob", 300), "tasks/get", id, get).body());
        assertEquals(-32602, other.path("error").path("code").asInt(), other.toString());
    }

    @Test
    void overStdioThereIsNoTokenSoIdentityToolsGetClaimsUnavailable() {
        Fixtures.Server stdio = new Fixtures.Server(demo, env);
        JsonNode error = stdio.error("demo.demo.whoami", "{}");
        assertEquals("CLAIMS_UNAVAILABLE", error.path("code").asText());
        // The dev file's fixture claims are ignored outside development.
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("read only with TOOLGATE_MODE=development"));
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("HTTP requests need a bearer token"));
    }

    @Test
    void verifierConfigurationIsCheckedAtStartup() throws IOException {
        assertTrue(refused(with("TOOLGATE_AUTH_ALGORITHMS", "none")).contains("never accepted"));
        assertTrue(refused(with("TOOLGATE_AUTH_ALGORITHMS", "RS256,HS256")).contains("HS256 is never accepted"));
        assertTrue(refused(with("TOOLGATE_AUTH_JWKS_URL", "https://login.acme.test/jwks")).contains("exactly one of"));
        Map<String, String> http = with("TOOLGATE_AUTH_JWKS_URL", "http://login.acme.test/jwks");
        http.remove("TOOLGATE_AUTH_KEYS_FILE");
        assertTrue(refused(http).contains("must be HTTPS"));
        Map<String, String> noAudience = with("TOOLGATE_AUTH_AUDIENCE", "");
        assertTrue(refused(noAudience).contains("needs TOOLGATE_AUTH_ISSUER and TOOLGATE_AUTH_AUDIENCE"));
        Map<String, String> noSource = new HashMap<>(env);
        noSource.remove("TOOLGATE_AUTH_KEYS_FILE");
        assertTrue(refused(noSource).contains("needs TOOLGATE_AUTH_JWKS_URL or TOOLGATE_AUTH_KEYS_FILE"));
        Map<String, String> introspection = new HashMap<>(env);
        introspection.remove("TOOLGATE_AUTH_KEYS_FILE");
        introspection.put("TOOLGATE_AUTH_INTROSPECTION_URL", "https://login.acme.test/introspect");
        assertTrue(refused(introspection).contains("client credentials"));
        Map<String, String> development = with("TOOLGATE_MODE", "development");
        assertTrue(refused(development).contains("production issuer"));

        // With a verifier, production may bind a non-loopback address.
        Map<String, String> wide = with("TOOLGATE_HTTP_ADDRESS", "0.0.0.0:0");
        assertNotNull(StandaloneRuntime.start(demo, wide, Path.of("build"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).httpAddress());
    }

    static Map<String, String> with(String name, String value) {
        Map<String, String> copy = new HashMap<>(env);
        copy.put(name, value);
        return copy;
    }

    static String refused(Map<String, String> environment) {
        try {
            StandaloneRuntime.start(demo, environment, Path.of("build"), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        } catch (StartupRefused e) {
            assertEquals("CONFIGURATION", e.code());
            return e.getMessage();
        }
        throw new AssertionError("the runtime started");
    }
}
