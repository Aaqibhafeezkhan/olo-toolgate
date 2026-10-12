// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small strict JSON reader and writer, so the helper needs nothing beyond the JDK.
 *
 * <p>Objects read as {@link LinkedHashMap}, arrays as {@link List}, numbers as {@link Long} or {@link BigDecimal}.
 * {@link #canonical} writes RFC 8785 (JCS) bytes for the values the release record holds: strings, integers,
 * booleans, null, arrays and objects.
 */
public final class Json {
    /** Nesting and size bounds for untrusted input. */
    static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        json.space();
        Object value = json.value(0);
        json.space();
        if (json.at != text.length()) {
            throw json.error("trailing data");
        }
        return value;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON at " + at + ": " + message);
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                at++;
            } else {
                break;
            }
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("unexpected token");
        }
        at += word.length();
        return value;
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) {
            at++;
            return map;
        }
        while (true) {
            space();
            if (!peek('"')) {
                throw error("expected a member name");
            }
            String name = string();
            space();
            expect(':');
            space();
            if (map.containsKey(name)) {
                throw error("duplicate member " + name);
            }
            map.put(name, value(depth + 1));
            space();
            if (peek(',')) {
                at++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek(']')) {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value(depth + 1));
            space();
            if (peek(',')) {
                at++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private boolean peek(char c) {
        return at < text.length() && text.charAt(at) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(at++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) {
                        throw error("short unicode escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    at += 4;
                    break;
                default:
                    throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = at;
        if (peek('-')) {
            at++;
        }
        while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String literal = text.substring(start, at);
        if (!literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw error("bad number");
        }
        BigDecimal value = new BigDecimal(literal);
        if (literal.matches("-?(0|[1-9][0-9]*)")) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException tooBig) {
                return value;
            }
        }
        return value;
    }

    // ---- writing

    /** Compact JSON in insertion order. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, false);
        return out.toString();
    }

    /** RFC 8785 canonical bytes: members sorted by UTF-16 code units, no whitespace, minimal escapes. */
    public static byte[] canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, true);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, boolean canonical) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof BigDecimal d) {
            if (canonical) {
                throw new IllegalArgumentException("canonical form supports integers only");
            }
            out.append(d.toString());
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> members = canonical ? new TreeMap<>((Map<String, Object>) map) : (Map<String, Object>) map;
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : members.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, e.getKey());
                out.append(':');
                write(out, e.getValue(), canonical);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i), canonical);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass());
        }
    }

    private static void quote(StringBuilder out, String s) {
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
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ---- access helpers for parsed trees

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    public static String str(Object value) {
        return value instanceof String s ? s : null;
    }

    public static Object at(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }
}
