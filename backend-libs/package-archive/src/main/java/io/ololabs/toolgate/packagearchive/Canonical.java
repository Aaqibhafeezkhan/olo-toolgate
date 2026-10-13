// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.TreeMap;

/** The canonical JSON the Tool SDK, the CLI and the Tool Host digest (tool-sdk/spec/descriptor §3): RFC 8785, keys by
 *  UTF-16 code units, ECMAScript numbers, UTF-8, with every string first put in NFC. The same rules as Control's
 *  {@code Canonical}, over {@link Json}. */
public final class Canonical {
    private Canonical() {}

    /** The canonical bytes, with strings in NFC. Throws {@link IllegalArgumentException} for keys that collide in NFC
     *  or a number that is not finite. */
    public static byte[] bytes(Json value) { return bytes(value, true); }
    public static String digest(Json value) { return sha256(bytes(value)); }

    static byte[] bytes(Json value, boolean nfc) { var out = new StringBuilder(); write(out, value, nfc); return out.toString().getBytes(StandardCharsets.UTF_8); }

    /** {@code "sha256:" + lowercase hex} of exact bytes. */
    public static String sha256(byte[] bytes) {
        try { return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }

    private static void write(StringBuilder out, Json value, boolean nfc) {
        switch (value.type()) {
            case NULL: case MISSING: out.append("null"); break;
            case OBJECT: {
                var keyed = new TreeMap<String, String>(); // String order is UTF-16 code units
                for (var name : value.fieldNames())
                    if (keyed.put(nfc ? Normalizer.normalize(name, Normalizer.Form.NFC) : name, name) != null) throw new IllegalArgumentException("keys collide in NFC");
                out.append('{'); boolean first = true;
                for (var e : keyed.entrySet()) { if (!first) out.append(','); first = false; string(out, e.getKey(), false); out.append(':'); write(out, value.get(e.getValue()), nfc); }
                out.append('}');
                break;
            }
            case ARRAY: {
                out.append('[');
                for (int i = 0; i < value.size(); i++) { if (i > 0) out.append(','); write(out, value.path(i), nfc); }
                out.append(']');
                break;
            }
            case STRING: string(out, value.textValue(), nfc); break;
            case BOOLEAN: out.append(value.booleanValue()); break;
            case INTEGER: out.append(value.bigIntegerValue()); break;
            default: out.append(number(value.doubleValue()));
        }
    }

    private static void string(StringBuilder out, String raw, boolean nfc) {
        String s = nfc ? Normalizer.normalize(raw, Normalizer.Form.NFC) : raw;
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
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
