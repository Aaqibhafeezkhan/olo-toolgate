// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Adapter A4 for JWTs: keys from a JWKS URL (fetched over HTTPS, cached, refreshed on an unknown {@code kid} at most
 * once per 30 s) or pinned; exact issuer and audience; an algorithm allowlist that never admits {@code none} or HMAC;
 * {@code exp} and {@code nbf} required with at most 60 s of skew.
 */
public final class JwtVerifier implements IdentityVerifier {
    public static final long SKEW_MS = 60_000;
    public static final long REFRESH_INTERVAL_MS = 30_000;
    /** Keys older than this are fetched again on the next verification. */
    public static final long MAX_KEY_AGE_MS = 60 * 60 * 1000;
    static final int MAX_TOKEN_CHARS = 16 * 1024;
    private static final Set<String> SUPPORTED = Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private final String issuer;
    private final String audience;
    private final Set<String> algorithms;
    private final Keys keys;
    private final LongSupplier clock;

    /**
     * @param algorithms the allowlist; see {@link #algorithms(String)}
     * @param keys where the verification keys come from
     * @param clock epoch milliseconds
     */
    public JwtVerifier(String issuer, String audience, Set<String> algorithms, Keys keys, LongSupplier clock) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) throw new IllegalArgumentException("issuer and audience are required");
        this.issuer = issuer;
        this.audience = audience;
        this.algorithms = Set.copyOf(algorithms);
        for (String a : this.algorithms) check(a);
        this.keys = keys;
        this.clock = clock;
    }

    /** Parses a comma-separated allowlist such as {@code RS256,ES256}; {@code none}, HMAC and unknown names are refused. */
    public static Set<String> algorithms(String list) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : list.split(",")) {
            String a = raw.trim();
            if (a.isEmpty()) continue;
            check(a);
            out.add(a);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("the algorithm allowlist is empty");
        return out;
    }

    private static void check(String algorithm) {
        String upper = algorithm.toUpperCase(Locale.ROOT);
        if (upper.equals("NONE") || upper.startsWith("HS")) throw new IllegalArgumentException(algorithm + " is never accepted");
        if (!SUPPORTED.contains(algorithm)) throw new IllegalArgumentException("unsupported algorithm " + algorithm);
    }

    @Override
    public VerifiedIdentity verify(String bearer) {
        if (bearer == null || bearer.length() > MAX_TOKEN_CHARS) throw new Rejected("missing or oversized token");
        String[] parts = bearer.split("\\.", -1);
        if (parts.length != 3) throw new Rejected("not a compact JWS");
        JsonNode header = decode(parts[0]);
        JsonNode payload = decode(parts[1]);
        byte[] signature = bytes(parts[2]);
        String alg = header.path("alg").asText("");
        if (!algorithms.contains(alg)) throw new Rejected("algorithm " + alg + " is not allowed");
        if (header.has("crit")) throw new Rejected("critical header parameters are not supported");
        PublicKey key = keys.key(header.path("kid").asText(null), clock.getAsLong());
        if (key == null) throw new Rejected("unknown key id");
        if (!verifies(alg, key, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature)) throw new Rejected("bad signature");

        long now = clock.getAsLong();
        if (!issuer.equals(payload.path("iss").asText(null))) throw new Rejected("wrong issuer");
        JsonNode aud = payload.path("aud");
        boolean audienceMatches = aud.isTextual() ? audience.equals(aud.asText()) : false;
        if (aud.isArray()) for (JsonNode a : aud) audienceMatches |= a.isTextual() && audience.equals(a.asText());
        if (!audienceMatches) throw new Rejected("wrong audience");
        JsonNode exp = payload.path("exp");
        JsonNode nbf = payload.path("nbf");
        if (!exp.isNumber() || !nbf.isNumber()) throw new Rejected("exp and nbf are required");
        long expMs = (long) (exp.asDouble() * 1000);
        if (now >= expMs + SKEW_MS) throw new Rejected("expired");
        if (now < (long) (nbf.asDouble() * 1000) - SKEW_MS) throw new Rejected("not yet valid");
        JsonNode sub = payload.path("sub");
        if (!sub.isTextual() || sub.asText().isEmpty()) throw new Rejected("sub is required");
        return new VerifiedIdentity(issuer, sub.asText(), (ObjectNode) payload, Instant.ofEpochMilli(expMs));
    }

    private static boolean verifies(String alg, PublicKey key, byte[] input, byte[] signature) {
        try {
            Signature s;
            int bits = Integer.parseInt(alg.substring(2));
            switch (alg.substring(0, 2)) {
                case "RS" -> {
                    if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) return false;
                    s = switch (alg) { case "RS256" -> Signature.getInstance("SHA256withRSA"); case "RS384" -> Signature.getInstance("SHA384withRSA"); case "RS512" -> Signature.getInstance("SHA512withRSA"); default -> throw new IllegalArgumentException("Unsupported RSA algorithm"); };
                }
                case "PS" -> {
                    if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) return false;
                    s = Signature.getInstance("RSASSA-PSS");
                    s.setParameter(switch (alg) { case "PS256" -> new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1); case "PS384" -> new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1); case "PS512" -> new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1); default -> throw new IllegalArgumentException("Unsupported PSS algorithm"); });
                }
                case "ES" -> {
                    int field = bits == 512 ? 521 : bits;
                    if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != field) return false;
                    if (signature.length != 2 * ((field + 7) / 8)) return false;
                    s = switch (alg) { case "ES256" -> Signature.getInstance("SHA256withECDSAinP1363Format"); case "ES384" -> Signature.getInstance("SHA384withECDSAinP1363Format"); case "ES512" -> Signature.getInstance("SHA512withECDSAinP1363Format"); default -> throw new IllegalArgumentException("Unsupported EC algorithm"); };
                }
                default -> {
                    return false;
                }
            }
            s.initVerify(key);
            s.update(input);
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static JsonNode decode(String part) {
        try {
            JsonNode node = JSON.readTree(bytes(part));
            if (node == null || !node.isObject()) throw new Rejected("not a JSON object");
            return node;
        } catch (IOException e) {
            throw new Rejected("not JSON");
        }
    }

    private static byte[] bytes(String part) {
        if (part.indexOf('=') >= 0) throw new Rejected("padded base64url");
        try {
            return B64.decode(part);
        } catch (IllegalArgumentException e) {
            throw new Rejected("not base64url");
        }
    }

    /** Where verification keys come from. */
    public interface Keys {
        /** The key for {@code kid} (null: the only key, when there is exactly one), or null when unknown. */
        PublicKey key(String kid, long now);
    }

    /** Keys fixed at startup from a JWKS document ({@code TOOLGATE_AUTH_KEYS_FILE}). */
    public static Keys pinned(String jwks) {
        Map<String, PublicKey> parsed = parse(jwks);
        if (parsed.isEmpty()) throw new IllegalArgumentException("the key set has no usable keys");
        return (kid, now) -> select(parsed, kid);
    }

    /** Keys from a JWKS URL, which must be HTTPS. */
    public static Keys jwks(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme())) throw new IllegalArgumentException("the JWKS URL must be HTTPS: " + url);
        return new RemoteKeys(url, Http::get);
    }

    /** Keys from {@code fetch}, for tests and custom transports. */
    static Keys jwks(URI url, Function<URI, String> fetch) {
        return new RemoteKeys(url, fetch);
    }

    private static PublicKey select(Map<String, PublicKey> keys, String kid) {
        if (kid != null) return keys.get(kid);
        return keys.size() == 1 ? keys.values().iterator().next() : null;
    }

    /** A JWKS that is fetched lazily, again after an hour, and on an unknown kid at most once per 30 s. */
    static final class RemoteKeys implements Keys {
        private final URI url;
        private final Function<URI, String> fetch;
        private Map<String, PublicKey> current;
        private long fetchedAt = Long.MIN_VALUE;
        int fetches;

        RemoteKeys(URI url, Function<URI, String> fetch) {
            this.url = url;
            this.fetch = fetch;
        }

        @Override
        public synchronized PublicKey key(String kid, long now) {
            if (current == null || now - fetchedAt >= MAX_KEY_AGE_MS) refresh(now);
            PublicKey key = current == null ? null : select(current, kid);
            if (key == null && now - fetchedAt >= REFRESH_INTERVAL_MS) {
                refresh(now);
                key = current == null ? null : select(current, kid);
            }
            return key;
        }

        private void refresh(long now) {
            fetchedAt = now; // a failed fetch also waits out the interval
            fetches++;
            try {
                current = parse(fetch.apply(url));
            } catch (RuntimeException e) {
                // keep the last good keys
            }
        }
    }

    /** The usable signing keys of a JWKS document, by kid ({@code ""} when a key has none). */
    static Map<String, PublicKey> parse(String jwks) {
        Map<String, PublicKey> out = new HashMap<>();
        JsonNode root;
        try {
            root = JSON.readTree(jwks);
        } catch (IOException e) {
            throw new IllegalArgumentException("the key set is not JSON");
        }
        for (JsonNode k : root.path("keys")) {
            if (k.has("use") && !"sig".equals(k.path("use").asText())) continue;
            try {
                PublicKey key = switch (k.path("kty").asText()) {
                    case "RSA" -> KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(big(k, "n"), big(k, "e")));
                    case "EC" -> {
                        String curve = switch (k.path("crv").asText()) {
                            case "P-256" -> "secp256r1";
                            case "P-384" -> "secp384r1";
                            case "P-521" -> "secp521r1";
                            default -> null;
                        };
                        if (curve == null) yield null;
                        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                        params.init(new ECGenParameterSpec(curve));
                        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
                        yield KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(big(k, "x"), big(k, "y")), spec));
                    }
                    default -> null;
                };
                if (key != null) out.put(k.path("kid").asText(""), key);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // an unusable key is skipped; the others still verify
            }
        }
        return out;
    }

    private static BigInteger big(JsonNode key, String member) {
        return new BigInteger(1, B64.decode(key.path(member).asText()));
    }
}
