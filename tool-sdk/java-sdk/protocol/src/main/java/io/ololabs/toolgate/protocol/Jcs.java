// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 8785 canonical JSON of a Jackson tree, for {@code argsHash} and business-key fingerprints: keys by UTF-16 code units, ECMAScript numbers and
 * strings, UTF-8. Integers keep every digit, so two different large integers never share a digest.
 */
public final class Jcs {
    private Jcs() {}

    /** The {@code sha256:} digest of the canonical form. */
    public static String digest(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The canonical form. */
    public static String canonical(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            out.append("null");
        } else if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(null); // String.compareTo orders by UTF-16 code units
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                string(out, names.get(i));
                out.append(':');
                write(out, value.get(names.get(i)));
            }
            out.append('}');
        } else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) out.append(',');
                write(out, value.get(i));
            }
            out.append(']');
        } else if (value.isTextual()) {
            string(out, value.textValue());
        } else if (value.isBoolean()) {
            out.append(value.booleanValue() ? "true" : "false");
        } else if (value.isIntegralNumber()) {
            out.append(value.bigIntegerValue());
        } else if (value.isNumber()) {
            out.append(number(value.doubleValue()));
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getNodeType());
        }
    }

    private static void string(StringBuilder out, String s) {
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
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** ECMAScript Number::toString with the shortest round-tripping digits. */
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("not a finite number");
        if (v == 0) return "0";
        String sign = v < 0 ? "-" : "";
        double a = Math.abs(v);
        BigDecimal shortest = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal candidate = new BigDecimal(a).round(new MathContext(p));
            if (candidate.doubleValue() == a) {
                shortest = candidate.stripTrailingZeros();
                break;
            }
        }
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale();
        StringBuilder out = new StringBuilder(sign);
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            out.append(digits.charAt(0));
            if (k > 1) out.append('.').append(digits, 1, k);
            out.append('e').append(n - 1 >= 0 ? "+" : "-").append(Math.abs(n - 1));
        }
        return out.toString();
    }
}
