// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical form of descriptor §3: every string NFC, then RFC 8785 (keys by UTF-16 code units, ECMAScript
 * numbers and strings), UTF-8. Values are Map, List, String, Boolean, Long, Integer or Double.
 */
public final class Jcs {
    private Jcs() {}

    public static byte[] bytes(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(StringBuilder out, Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = nfc((String) e.getKey());
                if (sorted.put(key, e.getValue()) != null) {
                    throw new IllegalArgumentException("keys are equal after NFC: " + key);
                }
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, e.getKey());
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof String s) {
            string(out, nfc(s));
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer) {
            long l = ((Number) value).longValue();
            if (Math.abs(l) > 9007199254740991L) throw new IllegalArgumentException("integer beyond 2^53-1: " + l);
            out.append(l);
        } else if (value instanceof Double d) {
            out.append(number(d));
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value);
        }
    }

    static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** Sorts strings by UTF-16 code units after NFC, the RFC 8785 order. */
    static List<String> sorted(Iterable<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(nfc(v));
        out.sort(null);
        return out;
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
