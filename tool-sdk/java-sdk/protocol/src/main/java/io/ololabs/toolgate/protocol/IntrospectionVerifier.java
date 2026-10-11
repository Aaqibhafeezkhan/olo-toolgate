// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Adapter A4 for opaque tokens: RFC 7662 introspection with client credentials. {@code active} is required, and a
 * positive answer is cached for at most 5 minutes and never past the token's {@code exp}. Negative answers and failed
 * calls are not cached, and a failed call never admits a token.
 */
public final class IntrospectionVerifier implements IdentityVerifier {
    public static final long MAX_CACHE_MS = 5 * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI url;
    private final String authorization;
    private final String issuer;
    private final String audience;
    private final BiFunction<String, String, String> post;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    int calls;

    private record Cached(VerifiedIdentity identity, long until) {}

    /**
     * @param issuer when set, an answer whose {@code iss} differs is refused
     * @param audience when set, an answer whose {@code aud} names other audiences only is refused
     */
    public static IntrospectionVerifier create(URI url, String clientId, String clientSecret, String issuer, String audience) {
        if (!"https".equalsIgnoreCase(url.getScheme())) throw new IllegalArgumentException("the introspection URL must be HTTPS: " + url);
        return new IntrospectionVerifier(url, clientId, clientSecret, issuer, audience, (auth, form) -> Http.postForm(url, auth, form),
                System::currentTimeMillis);
    }

    /** @param post sends the form with the Authorization value and returns the answer body */
    IntrospectionVerifier(URI url, String clientId, String clientSecret, String issuer, String audience,
            BiFunction<String, String, String> post, LongSupplier clock) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("introspection needs client credentials");
        }
        this.url = url;
        this.issuer = issuer == null || issuer.isBlank() ? null : issuer;
        this.audience = audience == null || audience.isBlank() ? null : audience;
        this.post = post;
        this.clock = clock;
        this.authorization = basic(clientId, clientSecret);
    }

    private static String basic(String id, String secret) {
        String pair = URLEncoder.encode(id, StandardCharsets.UTF_8) + ":" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public VerifiedIdentity verify(String bearer) {
        if (bearer == null || bearer.isEmpty() || bearer.length() > JwtVerifier.MAX_TOKEN_CHARS) throw new Rejected("missing or oversized token");
        long now = clock.getAsLong();
        String key = digest(bearer);
        purge(now);
        Cached hit = cache.get(key);
        if (hit != null && hit.until > now) return hit.identity;

        JsonNode answer;
        try {
            calls++;
            answer = JSON.readTree(post.apply(authorization, "token=" + URLEncoder.encode(bearer, StandardCharsets.UTF_8) + "&token_type_hint=access_token"));
        } catch (IOException | RuntimeException e) {
            throw new Rejected("introspection failed: " + e.getMessage());
        }
        if (answer == null || !answer.isObject() || !answer.path("active").asBoolean(false) || !answer.path("active").isBoolean()) {
            throw new Rejected("inactive");
        }
        String iss = answer.path("iss").isTextual() ? answer.path("iss").asText() : issuer;
        if (issuer != null && !issuer.equals(iss)) throw new Rejected("wrong issuer");
        JsonNode aud = answer.path("aud");
        if (audience != null && !aud.isMissingNode()) {
            boolean matches = aud.isTextual() && audience.equals(aud.asText());
            if (aud.isArray()) for (JsonNode a : aud) matches |= audience.equals(a.asText());
            if (!matches) throw new Rejected("wrong audience");
        }
        long until = now + MAX_CACHE_MS;
        JsonNode exp = answer.path("exp");
        if (exp.isNumber()) {
            long expMs = (long) (exp.asDouble() * 1000);
            if (expMs <= now) throw new Rejected("expired");
            until = Math.min(until, expMs);
        }
        String sub = answer.path("sub").asText(answer.path("username").asText(answer.path("client_id").asText("")));
        if (sub.isEmpty()) throw new Rejected("no subject");
        VerifiedIdentity identity = new VerifiedIdentity(iss == null ? url.toString() : iss, sub, (ObjectNode) answer,
                exp.isNumber() ? Instant.ofEpochMilli((long) (exp.asDouble() * 1000)) : Instant.ofEpochMilli(until));
        cache.put(key, new Cached(identity, until));
        return identity;
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Cached>> it = cache.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().until <= now) it.remove();
    }

    private static String digest(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
