// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR 061 signed tenant audit-chain anchors. A dedicated AUDIT_ANCHOR key domain signs
 * {@code DOMAIN || canonical claims}; verification needs a pinned, externally supplied keyring.
 */
public final class AuditAnchors {
    public static final String PURPOSE = "AUDIT_ANCHOR";
    static final byte[] DOMAIN = "toolgate/audit-anchor/v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern TENANT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final long LIMIT = 9007199254740991L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditAnchors() {}

    /** Signed claims. The canonical document is the sorted-key compact JSON produced by {@link #document}. */
    public record Claims(String tenantId, long chainIndex, String headDigest, long issuedAtUnixMs, String keyId) {
        public Claims {
            if (tenantId == null || !TENANT.matcher(tenantId).matches() || chainIndex < 1 || chainIndex > LIMIT || headDigest == null
                || !HEX.matcher(headDigest).matches() || issuedAtUnixMs < 0 || issuedAtUnixMs > LIMIT || keyId == null || !KEY_ID.matcher(keyId).matches())
                throw new Refused(Refusal.MALFORMED);
        }
        public AuditChain.Head head() { return new AuditChain.Head(chainIndex, headDigest); }
    }

    /** One anchor exactly as published: the canonical document text and its base64url signature. */
    public record Anchor(String document, String signature) {}

    public enum KeyState { ACTIVE, RETIRED, REVOKED }

    /**
     * A pinned verification key. {@code retiredAtUnixMs} bounds which issue times it may still verify;
     * RETIRED keys keep old anchors verifiable, REVOKED keys verify nothing.
     */
    public record TrustedKey(String keyId, RSAPublicKey key, KeyState state, long activatedAtUnixMs, Long retiredAtUnixMs) {
        boolean covers(long issuedAt) {
            return issuedAt >= activatedAtUnixMs && (retiredAtUnixMs == null || issuedAt < retiredAtUnixMs);
        }
    }

    public record Keyring(Map<String, TrustedKey> keys) {
        public Keyring { keys = Map.copyOf(keys); }
        public TrustedKey active(String keyId) {
            var key = keys.get(keyId);
            if (key == null || key.state() != KeyState.ACTIVE) throw new Refused(Refusal.UNKNOWN_KEY);
            return key;
        }
    }

    public enum Refusal { MALFORMED, UNKNOWN_KEY, REVOKED_KEY, KEY_NOT_VALID_AT_ISSUE, BAD_SIGNATURE, WRONG_TENANT, CHAIN_MISMATCH }

    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Refusal refusal;
        public Refused(Refusal refusal) { super(refusal.name(), null, false, false); this.refusal = refusal; }
        public Refusal refusal() { return refusal; }
    }

    /** The private half lives only in the anchor custody; the application sees an opaque signer. */
    public interface Signer {
        String keyId();
        byte[] sign(byte[] message);
    }

    public static String document(Claims claims) {
        // Field names are fixed and emitted in sorted order; values are validated above.
        return "{\"chainIndex\":" + claims.chainIndex() + ",\"formatVersion\":1,\"headDigest\":\"" + claims.headDigest()
            + "\",\"issuedAtUnixMs\":" + claims.issuedAtUnixMs() + ",\"keyId\":\"" + claims.keyId() + "\",\"purpose\":\"" + PURPOSE
            + "\",\"tenantId\":\"" + claims.tenantId() + "\"}";
    }

    public static byte[] message(String document) {
        var body = document.getBytes(StandardCharsets.UTF_8);
        var message = new byte[DOMAIN.length + body.length];
        System.arraycopy(DOMAIN, 0, message, 0, DOMAIN.length); System.arraycopy(body, 0, message, DOMAIN.length, body.length);
        return message;
    }

    public static Anchor sign(Signer signer, String tenantId, AuditChain.Head head, long now) {
        if (head.chainIndex() < 1) throw new Refused(Refusal.MALFORMED);
        var document = document(new Claims(tenantId, head.chainIndex(), head.digest(), now, signer.keyId()));
        return new Anchor(document, Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign(message(document))));
    }

    public static Claims parse(String document) {
        try {
            if (document == null || document.length() > 1024) throw new Refused(Refusal.MALFORMED);
            JsonNode node = JSON.readTree(document);
            if (!node.isObject() || node.size() != 7 || node.path("formatVersion").asLong(-1) != 1 || !PURPOSE.equals(node.path("purpose").asText(null))
                || !node.path("chainIndex").isIntegralNumber() || !node.path("issuedAtUnixMs").isIntegralNumber()
                || !node.path("tenantId").isTextual() || !node.path("headDigest").isTextual() || !node.path("keyId").isTextual())
                throw new Refused(Refusal.MALFORMED);
            var claims = new Claims(node.get("tenantId").asText(), node.get("chainIndex").asLong(), node.get("headDigest").asText(),
                node.get("issuedAtUnixMs").asLong(), node.get("keyId").asText());
            if (!document(claims).equals(document)) throw new Refused(Refusal.MALFORMED);
            return claims;
        } catch (java.io.IOException malformed) { throw new Refused(Refusal.MALFORMED); }
    }

    /** Verifies domain, signature, key lifecycle and tenant binding; never consults the database. */
    public static Claims verify(Anchor anchor, Keyring keyring, String expectedTenant) {
        var claims = parse(anchor.document());
        var key = keyring.keys().get(claims.keyId());
        if (key == null) throw new Refused(Refusal.UNKNOWN_KEY);
        if (key.state() == KeyState.REVOKED) throw new Refused(Refusal.REVOKED_KEY);
        if (!key.covers(claims.issuedAtUnixMs())) throw new Refused(Refusal.KEY_NOT_VALID_AT_ISSUE);
        try {
            byte[] signature = Base64.getUrlDecoder().decode(anchor.signature());
            var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(key.key()); verifier.update(message(anchor.document()));
            if (!verifier.verify(signature)) throw new Refused(Refusal.BAD_SIGNATURE);
        } catch (IllegalArgumentException | GeneralSecurityException invalid) { throw new Refused(Refusal.BAD_SIGNATURE); }
        if (!claims.tenantId().equals(expectedTenant)) throw new Refused(Refusal.WRONG_TENANT);
        return claims;
    }

    /** Verifies a full tenant chain against a verified anchor; any rewrite before the anchored head is a mismatch. */
    public static AuditChain.Result verifyChain(Claims claims, Iterable<AuditChain.Entry> entries) {
        return AuditChain.verify(claims.tenantId(), AuditChain.Head.EMPTY, entries, claims.head());
    }

    /** Object name for a sink: one anchor per (tenant, head), so retries converge on the same object. */
    public static String objectName(String tenantId, AuditChain.Head head) {
        if (!TENANT.matcher(tenantId).matches() || head.chainIndex() < 1) throw new Refused(Refusal.MALFORMED);
        return "tenants/" + tenantId + "/audit-anchors/" + String.format("%019d", head.chainIndex()) + "-" + head.digest() + ".json";
    }

    /** Published object bytes: the document and signature in one sorted-key JSON value. */
    public static byte[] object(Anchor anchor) {
        parse(anchor.document());
        if (anchor.signature() == null || !anchor.signature().matches("[A-Za-z0-9_-]{1,1024}")) throw new Refused(Refusal.MALFORMED);
        try { return JSON.writeValueAsBytes(new java.util.TreeMap<>(Map.of("document", anchor.document(), "signature", anchor.signature()))); }
        catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Anchor fromObject(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (!node.isObject() || node.size() != 2 || !node.path("document").isTextual() || !node.path("signature").isTextual()) throw new Refused(Refusal.MALFORMED);
            var anchor = new Anchor(node.get("document").asText(), node.get("signature").asText());
            if (!java.util.Arrays.equals(object(anchor), bytes)) throw new Refused(Refusal.MALFORMED);
            return anchor;
        } catch (java.io.IOException malformed) { throw new Refused(Refusal.MALFORMED); }
    }

    /**
     * Parses a pinned keyring: {"formatVersion":1,"purpose":"AUDIT_ANCHOR","keys":[{"keyId","n","e","state",
     * "activatedAtUnixMs","retiredAtUnixMs"}]}. Every key must be an RSA 3072/4096-bit, e=65537 key of this domain.
     */
    public static Keyring keyring(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject() || !exact(root, Set.of("formatVersion", "purpose", "keys")) || root.path("formatVersion").asLong(-1) != 1
                || !PURPOSE.equals(root.path("purpose").asText(null)) || !root.path("keys").isArray() || root.path("keys").isEmpty() || root.path("keys").size() > 64)
                throw new Refused(Refusal.MALFORMED);
            var keys = new LinkedHashMap<String, TrustedKey>(); var moduli = new java.util.HashSet<BigInteger>(); int active = 0;
            for (var entry : root.path("keys")) {
                if (!exact(entry, Set.of("keyId", "n", "e", "state", "activatedAtUnixMs", "retiredAtUnixMs")) || !entry.path("keyId").isTextual()
                    || !KEY_ID.matcher(entry.path("keyId").asText()).matches() || !entry.path("activatedAtUnixMs").isIntegralNumber()) throw new Refused(Refusal.MALFORMED);
                var state = KeyState.valueOf(entry.path("state").asText(""));
                Long retired = entry.path("retiredAtUnixMs").isNull() || entry.path("retiredAtUnixMs").isMissingNode() ? null : entry.path("retiredAtUnixMs").asLong();
                if ((state == KeyState.ACTIVE) != (retired == null) || retired != null && retired <= entry.path("activatedAtUnixMs").asLong()) throw new Refused(Refusal.MALFORMED);
                var n = new BigInteger(1, Base64.getUrlDecoder().decode(entry.path("n").asText()));
                var e = new BigInteger(1, Base64.getUrlDecoder().decode(entry.path("e").asText()));
                if (!Set.of(3072, 4096).contains(n.bitLength()) || !e.equals(BigInteger.valueOf(65537)) || !moduli.add(n)) throw new Refused(Refusal.MALFORMED);
                var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                if (keys.put(entry.path("keyId").asText(), new TrustedKey(entry.path("keyId").asText(), key, state, entry.path("activatedAtUnixMs").asLong(), retired)) != null)
                    throw new Refused(Refusal.MALFORMED);
                if (state == KeyState.ACTIVE) active++;
            }
            if (active > 1) throw new Refused(Refusal.MALFORMED);
            return new Keyring(keys);
        } catch (java.io.IOException | IllegalArgumentException | GeneralSecurityException malformed) { throw new Refused(Refusal.MALFORMED); }
    }

    private static boolean exact(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        for (var name : (Iterable<String>) node::fieldNames) if (!fields.contains(name)) return false;
        return true;
    }
}
