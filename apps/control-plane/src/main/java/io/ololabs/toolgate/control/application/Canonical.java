// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;

/** The canonical JSON the Tool SDK, the CLI and the Tool Host digest (tool-sdk/spec/descriptor §3): RFC 8785, keys by
 *  UTF-16 code units, ECMAScript numbers, UTF-8. {@link #digest} first puts every string in NFC, as descriptors, service
 *  profiles, globals and runtime declarations are digested; {@link #arguments} does not, as the permit's
 *  {@code argumentsDigest} is the RFC 8785 form of the exact arguments. */
public final class Canonical {
    private Canonical() {}

    public static String digest(JsonNode value) { return sha256(bytes(value)); }
    public static byte[] bytes(JsonNode value) { var out = new StringBuilder(); write(out, value, true); return out.toString().getBytes(StandardCharsets.UTF_8); }
    public static String arguments(JsonNode value) { var out = new StringBuilder(); write(out, value, false); return sha256(out.toString().getBytes(StandardCharsets.UTF_8)); }
    /** {@code "sha256:" + lowercase hex} of exact bytes. */
    public static String sha256(byte[] bytes) {
        try { return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void write(StringBuilder out, JsonNode value, boolean nfc) {
        if (value == null || value.isNull() || value.isMissingNode()) out.append("null");
        else if (value.isObject()) {
            var names = new ArrayList<String>(); value.fieldNames().forEachRemaining(names::add);
            var keyed = new java.util.TreeMap<String, String>(); // String order is UTF-16 code units
            for (var name : names) if (keyed.put(nfc ? Normalizer.normalize(name, Normalizer.Form.NFC) : name, name) != null) throw new IllegalArgumentException("keys collide in NFC");
            out.append('{'); boolean first = true;
            for (var e : keyed.entrySet()) { if (!first) out.append(','); first = false; string(out, e.getKey(), false); out.append(':'); write(out, value.get(e.getValue()), nfc); }
            out.append('}');
        } else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) { if (i > 0) out.append(','); write(out, value.get(i), nfc); }
            out.append(']');
        } else if (value.isTextual()) string(out, value.textValue(), nfc);
        else if (value.isBoolean()) out.append(value.booleanValue());
        else if (value.isIntegralNumber()) out.append(value.bigIntegerValue());
        else if (value.isNumber()) out.append(number(value.doubleValue()));
        else throw new IllegalArgumentException("not a JSON value");
    }

    private static void string(StringBuilder out, String raw, boolean nfc) {
        String s = nfc ? Normalizer.normalize(raw, Normalizer.Form.NFC) : raw;
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    /** ECMAScript Number::toString with the shortest round-tripping digits. */
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("not a finite number");
        if (v == 0) return "0";
        String sign = v < 0 ? "-" : ""; double a = Math.abs(v); BigDecimal shortest = null;
        for (int p = 1; p <= 17; p++) { var candidate = new BigDecimal(a).round(new MathContext(p)); if (candidate.doubleValue() == a) { shortest = candidate.stripTrailingZeros(); break; } }
        String digits = shortest.unscaledValue().toString(); int k = digits.length(), n = k - shortest.scale();
        var out = new StringBuilder(sign);
        if (k <= n && n <= 21) out.append(digits).append("0".repeat(n - k));
        else if (0 < n && n <= 21) out.append(digits, 0, n).append('.').append(digits, n, k);
        else if (-6 < n && n <= 0) out.append("0.").append("0".repeat(-n)).append(digits);
        else { out.append(digits.charAt(0)); if (k > 1) out.append('.').append(digits, 1, k); out.append('e').append(n - 1 >= 0 ? "+" : "-").append(Math.abs(n - 1)); }
        return out.toString();
    }
}
