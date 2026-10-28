// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Adapter A4 against the shared identity fixtures (tool-sdk/conformance/fixtures/identity). */
class IdentityTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final URI JWKS = URI.create("https://issuer.fixtures.toolgate.invalid/jwks");
    static JsonNode fixtures;
    static String jwks;
    static String rotated;
    static long now;

    @BeforeAll
    static void load() throws IOException {
        Path dir = Path.of(System.getProperty("toolgate.conformance"), "fixtures", "identity");
        fixtures = JSON.readTree(dir.resolve("tokens.json").toFile());
        jwks = Files.readString(dir.resolve("jwks.json"));
        rotated = Files.readString(dir.resolve("jwks-rotated.json"));
        now = fixtures.path("now").asLong() * 1000;
    }

    static JwtVerifier verifier(JwtVerifier.Keys keys, AtomicLong clock) {
        return new JwtVerifier(fixtures.path("issuer").asText(), fixtures.path("audience").asText(),
                JwtVerifier.algorithms("RS256,ES256"), keys, clock::get);
    }

    static String token(String name) {
        for (JsonNode t : fixtures.path("tokens")) if (t.path("name").asText().equals(name)) return t.path("token").asText();
        throw new AssertionError("no fixture token " + name);
    }

    @Test
    void everyFixtureTokenGetsItsExpectedOutcomeWithPinnedKeys() {
        JwtVerifier v = verifier(JwtVerifier.pinned(jwks), new AtomicLong(now));
        for (JsonNode t : fixtures.path("tokens")) {
            String name = t.path("name").asText();
            boolean accept = t.path("expect").asText().equals("accept");
            try {
                VerifiedIdentity id = v.verify(t.path("token").asText());
                if (!accept) fail(name + " was accepted: " + t.path("why").asText());
                assertEquals("user-1", id.subject(), name);
                assertEquals(fixtures.path("issuer").asText(), id.issuer(), name);
                assertEquals("fixture.user", id.claims().path("preferred_username").asText(), name);
            } catch (IdentityVerifier.Rejected e) {
                if (accept) fail(name + " was rejected (" + e.getMessage() + "): " + t.path("why").asText());
            }
        }
    }

    @Test
    void anUnknownKidRefreshesTheKeySetAtMostOncePer30Seconds() {
        AtomicLong clock = new AtomicLong(now);
        AtomicReference<String> served = new AtomicReference<>(jwks);
        JwtVerifier.RemoteKeys keys = (JwtVerifier.RemoteKeys) JwtVerifier.jwks(JWKS, url -> served.get());
        JwtVerifier v = verifier(keys, clock);

        v.verify(token("good-rs256"));
        assertEquals(1, keys.fetches);
        served.set(rotated);
        // Within 30 s of the last fetch an unknown kid is refused without another fetch.
        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify(token("unknown-kid")));
        clock.addAndGet(29_000);
        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify(token("unknown-kid")));
        assertEquals(1, keys.fetches);

        clock.addAndGet(1_000);
        assertEquals("user-1", v.verify(token("unknown-kid")).subject());
        assertEquals(2, keys.fetches);
        v.verify(token("unknown-kid"));
        assertEquals(2, keys.fetches, "a known kid needs no fetch");
    }

    @Test
    void aFailedFetchKeepsTheLastKeysAndWaitsOutTheInterval() {
        AtomicLong clock = new AtomicLong(now);
        AtomicReference<String> served = new AtomicReference<>(jwks);
        JwtVerifier.RemoteKeys keys = (JwtVerifier.RemoteKeys) JwtVerifier.jwks(JWKS, url -> {
            String body = served.get();
            if (body == null) throw new IllegalStateException("down");
            return body;
        });
        JwtVerifier v = verifier(keys, clock);
        v.verify(token("good-es256"));
        served.set(null);
        clock.addAndGet(JwtVerifier.MAX_KEY_AGE_MS);
        assertEquals("user-1", v.verify(token("good-es256")).subject());
        assertEquals(2, keys.fetches);
    }

    @Test
    void theAllowlistNeverAdmitsNoneOrHmac() {
        for (String bad : List.of("none", "NONE", "HS256", "hs512", "RS256,HS384", "XS256", "")) {
            assertThrows(IllegalArgumentException.class, () -> JwtVerifier.algorithms(bad), bad);
        }
        assertEquals(2, JwtVerifier.algorithms(" RS256 , ES256 ").size());
        assertThrows(IllegalArgumentException.class, () -> JwtVerifier.jwks(URI.create("http://issuer.invalid/jwks")));
    }

    @Test
    void aTokenOnlyRs256AllowsSignedWithEs256IsRefused() {
        JwtVerifier v = new JwtVerifier(fixtures.path("issuer").asText(), fixtures.path("audience").asText(),
                JwtVerifier.algorithms("RS256"), JwtVerifier.pinned(jwks), () -> now);
        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify(token("good-es256")));
        assertEquals("user-1", v.verify(token("good-rs256")).subject());
    }

    /** An introspection endpoint answering from the fixtures, recording the requests it saw. */
    static final class Endpoint {
        final Map<String, JsonNode> answers = new HashMap<>();
        String authorization;
        int calls;

        Endpoint() {
            for (JsonNode r : fixtures.path("introspection").path("responses")) answers.put(r.path("opaque").asText(), r.path("response"));
        }

        String post(String auth, String form) {
            calls++;
            authorization = auth;
            String token = null;
            for (String pair : form.split("&")) {
                if (pair.startsWith("token=")) token = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
            JsonNode answer = answers.get(token);
            return answer == null ? "{\"active\":false}" : answer.toString();
        }
    }

    @Test
    void introspectionAcceptsOnlyActiveMatchingTokensAndSendsClientCredentials() {
        Endpoint endpoint = new Endpoint();
        IntrospectionVerifier v = new IntrospectionVerifier(URI.create("https://issuer.invalid/introspect"), "fixture-client", "s3cret:x",
                fixtures.path("issuer").asText(), fixtures.path("audience").asText(), endpoint::post, () -> now);
        for (JsonNode r : fixtures.path("introspection").path("responses")) {
            String name = r.path("name").asText();
            boolean accept = r.path("expect").asText().equals("accept");
            try {
                VerifiedIdentity id = v.verify(r.path("opaque").asText());
                if (!accept) fail(name + " was accepted");
                assertEquals("user-1", id.subject());
            } catch (IdentityVerifier.Rejected e) {
                if (accept) fail(name + " was rejected: " + e.getMessage());
            }
        }
        String pair = new String(Base64.getDecoder().decode(endpoint.authorization.substring(6)), StandardCharsets.UTF_8);
        assertEquals("fixture-client:s3cret%3Ax", pair);
    }

    @Test
    void positiveAnswersAreCachedUntilExpOrFiveMinutesAndNegativesNever() {
        Endpoint endpoint = new Endpoint();
        AtomicLong clock = new AtomicLong(now);
        IntrospectionVerifier v = new IntrospectionVerifier(URI.create("https://issuer.invalid/introspect"), "c", "s",
                null, null, endpoint::post, clock::get);
        v.verify("opaque-active-0001");
        v.verify("opaque-active-0001");
        assertEquals(1, endpoint.calls);
        clock.addAndGet(120_000); // the fixture's exp
        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify("opaque-active-0001"));
        assertEquals(2, endpoint.calls);

        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify("opaque-revoked-0002"));
        assertThrows(IdentityVerifier.Rejected.class, () -> v.verify("opaque-revoked-0002"));
        assertEquals(4, endpoint.calls);

        // Without exp the cache still ends after five minutes.
        endpoint.answers.put("no-exp", JSON.createObjectNode().put("active", true).put("sub", "u"));
        v.verify("no-exp");
        clock.addAndGet(IntrospectionVerifier.MAX_CACHE_MS - 1);
        v.verify("no-exp");
        assertEquals(5, endpoint.calls);
        clock.addAndGet(1);
        v.verify("no-exp");
        assertEquals(6, endpoint.calls);
    }

    @Test
    void anUnreachableEndpointNeverAdmitsAToken() {
        IntrospectionVerifier v = new IntrospectionVerifier(URI.create("https://issuer.invalid/introspect"), "c", "s", null, null,
                (auth, form) -> {
                    throw new IllegalStateException("down");
                }, () -> now);
        IdentityVerifier.Rejected e = assertThrows(IdentityVerifier.Rejected.class, () -> v.verify("opaque-active-0001"));
        assertTrue(e.getMessage().contains("down"));
        assertThrows(IllegalArgumentException.class,
                () -> IntrospectionVerifier.create(URI.create("http://issuer.invalid/introspect"), "c", "s", null, null));
    }
}
