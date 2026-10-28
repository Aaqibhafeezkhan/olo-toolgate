// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Adapter A3 (tool-sdk/spec/protocol-layer.md §3): MRTR {@code requestState} in standalone mode. A compact JWS, HS256,
 * with a random per-process key of 256 bits, so a token from another process never verifies. The payload binds the
 * tool, the JCS digest of the arguments, the identity and the scope, lives at most 10 minutes, and its nonce is accepted
 * once. It also carries the input responses of earlier rounds, which the client sent and may read.
 */
public final class RequestStates {
    public static final long LIFETIME_MS = 10 * 60 * 1000;
    public static final String SCOPE = "standalone";
    private static final String HEADER = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] key;
    private final LongSupplier clock;
    private final Map<String, Long> spent = new ConcurrentHashMap<>();

    /** A fresh process key from {@link SecureRandom}. */
    public RequestStates() {
        this(randomKey(), System::currentTimeMillis);
    }

    RequestStates(byte[] key, LongSupplier clock) {
        if (key.length < 32) throw new IllegalArgumentException("the requestState key must be at least 256 bits");
        this.key = key.clone();
        this.clock = clock;
    }

    /** Mints the state for the next round of {@code toolId} with {@code arguments}, carrying {@code responses}. */
    public String mint(String toolId, JsonNode arguments, RequestMeta meta, ObjectNode responses) {
        long now = clock.getAsLong();
        ObjectNode payload = JSON.createObjectNode();
        payload.put("toolId", toolId);
        payload.put("argsHash", Jcs.digest(arguments));
        payload.put("identity", meta.owner());
        payload.put("scope", SCOPE);
        payload.put("exp", (now + LIFETIME_MS) / 1000);
        payload.put("nonce", UUID.randomUUID().toString());
        payload.set("responses", responses);
        String body = b64(Jcs.canonical(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER + "." + body;
        return signingInput + "." + b64(sign(signingInput));
    }

    /**
     * Verifies a retry's state before anything runs and spends its nonce; returns the responses it carries. Every
     * refusal is the same {@code -32602}, so a caller learns nothing about why or about another identity's call.
     */
    public ObjectNode verify(String token, String toolId, JsonNode arguments, RequestMeta meta) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals(HEADER)) throw refused();
        byte[] signature;
        JsonNode payload;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
            payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IllegalArgumentException | IOException e) {
            throw refused();
        }
        if (!MessageDigest.isEqual(signature, sign(parts[0] + "." + parts[1])) || payload == null || !payload.isObject()) throw refused();
        long now = clock.getAsLong();
        long exp = payload.path("exp").asLong(0);
        if (exp * 1000 <= now
                || !toolId.equals(payload.path("toolId").asText())
                || !Jcs.digest(arguments).equals(payload.path("argsHash").asText())
                || !meta.owner().equals(payload.path("identity").asText())
                || !SCOPE.equals(payload.path("scope").asText())
                || !payload.path("responses").isObject()) {
            throw refused();
        }
        purge(now);
        if (spent.putIfAbsent(payload.path("nonce").asText(), exp * 1000) != null) throw refused();
        return (ObjectNode) payload.get("responses");
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Long>> it = spent.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) it.remove();
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProtocolError refused() {
        return new ProtocolError(ProtocolError.INVALID_PARAMS, "Invalid requestState");
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
