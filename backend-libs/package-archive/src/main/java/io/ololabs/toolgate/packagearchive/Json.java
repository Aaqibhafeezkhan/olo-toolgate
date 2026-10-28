// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON value, parsed strictly and with only the JDK, so the library has no dependencies.
 *
 * <p>The parser accepts what Control's codec (Jackson with duplicate detection, trailing-token rejection and a
 * nesting limit of 32) accepts, and the accessors behave like Jackson's {@code JsonNode}: {@link #path} never returns
 * {@code null}, {@link #asText} and {@link #asLong} coerce the same way, and a number keeps whether it was written as
 * an integer or with a fraction or exponent, which decides its canonical form.
 */
public final class Json implements Iterable<Json> {
    /** The kind of a value. {@code MISSING} is what {@link #path} returns for an absent member. */
    public enum Type { OBJECT, ARRAY, STRING, INTEGER, DOUBLE, BOOLEAN, NULL, MISSING }

    /** The largest document, in UTF-8 bytes, and the deepest nesting the parser accepts (Control's codec limits). */
    public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024, MAX_NESTING = 32;
    private static final int MAX_NUMBER_LENGTH = 1000;

    public static final Json MISSING = new Json(Type.MISSING, null), NULL = new Json(Type.NULL, null),
        TRUE = new Json(Type.BOOLEAN, Boolean.TRUE), FALSE = new Json(Type.BOOLEAN, Boolean.FALSE);

    private final Type type;
    private final Object value;

    private Json(Type type, Object value) { this.type = type; this.value = value; }

    public static Json string(String value) { return new Json(Type.STRING, Objects.requireNonNull(value)); }
    public static Json integer(BigInteger value) { return new Json(Type.INTEGER, Objects.requireNonNull(value)); }
    public static Json integer(long value) { return integer(BigInteger.valueOf(value)); }
    public static Json number(double value) { return new Json(Type.DOUBLE, value); }
    public static Json bool(boolean value) { return value ? TRUE : FALSE; }
    /** An object with these members, in this order. */
    public static Json object(Map<String, Json> members) { return new Json(Type.OBJECT, Collections.unmodifiableMap(new LinkedHashMap<>(members))); }
    public static Json array(List<Json> items) { return new Json(Type.ARRAY, List.copyOf(items)); }

    public Type type() { return type; }
    public boolean isObject() { return type == Type.OBJECT; }
    public boolean isArray() { return type == Type.ARRAY; }
    public boolean isTextual() { return type == Type.STRING; }
    public boolean isIntegralNumber() { return type == Type.INTEGER; }
    public boolean isNumber() { return type == Type.INTEGER || type == Type.DOUBLE; }
    public boolean isBoolean() { return type == Type.BOOLEAN; }
    public boolean isNull() { return type == Type.NULL; }
    public boolean isMissing() { return type == Type.MISSING; }

    @SuppressWarnings("unchecked")
    private Map<String, Json> members() { return (Map<String, Json>) value; }
    @SuppressWarnings("unchecked")
    private List<Json> items() { return (List<Json>) value; }

    /** The member, or {@code null} when this is not an object or has no such member. */
    public Json get(String name) { return isObject() ? members().get(name) : null; }
    /** The member, or {@link #MISSING}. */
    public Json path(String name) { var member = get(name); return member == null ? MISSING : member; }
    /** The element, or {@link #MISSING} when this is not an array or the index is out of range. */
    public Json path(int index) { return isArray() && index >= 0 && index < items().size() ? items().get(index) : MISSING; }
    public boolean has(String name) { return isObject() && members().containsKey(name); }
    /** Member names in document order; empty unless this is an object. */
    public List<String> fieldNames() { return isObject() ? List.copyOf(members().keySet()) : List.of(); }
    /** The number of members or elements; 0 for anything else. */
    public int size() { return isObject() ? members().size() : isArray() ? items().size() : 0; }
    /** The elements of an array or the member values of an object, as Jackson iterates them. */
    @Override public Iterator<Json> iterator() {
        return isArray() ? items().iterator() : isObject() ? members().values().iterator() : Collections.emptyIterator();
    }

    public String textValue() { return isTextual() ? (String) value : null; }
    public BigInteger bigIntegerValue() { return isIntegralNumber() ? (BigInteger) value : type == Type.DOUBLE ? java.math.BigDecimal.valueOf((Double) value).toBigInteger() : BigInteger.ZERO; }
    public double doubleValue() { return isIntegralNumber() ? ((BigInteger) value).doubleValue() : type == Type.DOUBLE ? (Double) value : 0; }
    public boolean booleanValue() { return Boolean.TRUE.equals(value); }

    /** Jackson's {@code asText}: the string, a number or boolean as text, {@code "null"}, or {@code ""}. */
    public String asText() {
        switch (type) {
            case STRING: return (String) value;
            case INTEGER: return value.toString();
            case DOUBLE: return Double.toString((Double) value);
            case BOOLEAN: return value.toString();
            case NULL: return "null";
            default: return "";
        }
    }

    public int asInt() { return asInt(0); }
    /** Jackson's {@code asInt(int)}. */
    public int asInt(int otherwise) {
        switch (type) {
            case INTEGER: return ((BigInteger) value).intValue();
            case DOUBLE: return (int) (double) (Double) value;
            case BOOLEAN: return booleanValue() ? 1 : 0;
            case NULL: return 0;
            case STRING: return parseInt((String) value, otherwise);
            default: return otherwise;
        }
    }

    public long asLong() { return asLong(0); }
    /** Jackson's {@code asLong(long)}. */
    public long asLong(long otherwise) {
        switch (type) {
            case INTEGER: return ((BigInteger) value).longValue();
            case DOUBLE: return (long) (double) (Double) value;
            case BOOLEAN: return booleanValue() ? 1 : 0;
            case NULL: return 0;
            case STRING: return parseLong((String) value, otherwise);
            default: return otherwise;
        }
    }

    // Jackson's NumberInput.parseAsInt and parseAsLong.
    private static int parseInt(String text, int otherwise) {
        String s = text.trim();
        if (s.isEmpty()) return otherwise;
        int i = 0;
        if (s.charAt(0) == '+') s = s.substring(1); else if (s.charAt(0) == '-') i = 1;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > '9' || c < '0') {
                try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return otherwise; }
            }
        }
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return otherwise; }
    }

    private static long parseLong(String text, long otherwise) {
        String s = text.trim();
        if (s.isEmpty()) return otherwise;
        int i = 0;
        if (s.charAt(0) == '+') s = s.substring(1); else if (s.charAt(0) == '-') i = 1;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > '9' || c < '0') {
                try { return (long) Double.parseDouble(s); } catch (NumberFormatException e) { return otherwise; }
            }
        }
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return otherwise; }
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Json)) return false;
        var that = (Json) other;
        if (type != that.type) return false;
        if (type == Type.DOUBLE) return Double.compare((Double) value, (Double) that.value) == 0;
        return Objects.equals(value, that.value);
    }

    @Override public int hashCode() { return Objects.hash(type, value); }

    @Override public String toString() { return type == Type.MISSING ? "" : new String(Canonical.bytes(this, false), java.nio.charset.StandardCharsets.UTF_8); }

    /** A JSON text that is not one strict JSON value. */
    public static final class ParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        ParseException(String message, int at) { super(message + " at offset " + at); }
    }

    /** Parses one JSON value; whitespace may surround it, nothing else may follow it. */
    public static Json parse(String text) {
        if (text.length() > MAX_DOCUMENT_BYTES || utf8Length(text) > MAX_DOCUMENT_BYTES) throw new ParseException("document is over " + MAX_DOCUMENT_BYTES + " bytes", 0);
        var parser = new Parser(text);
        parser.space();
        if (parser.at >= text.length()) throw new ParseException("no JSON value", parser.at);
        var value = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw new ParseException("unexpected text after the JSON value", parser.at);
        return value;
    }

    private static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) { this.text = text; }

        private ParseException error(String message) { return new ParseException(message, at); }

        void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                at++;
            }
        }

        Json value(int depth) {
            if (at >= text.length()) throw error("unexpected end of JSON");
            char c = text.charAt(at);
            switch (c) {
                case '{': return object(depth + 1);
                case '[': return array(depth + 1);
                case '"': return string(stringValue());
                case 't': literal("true"); return TRUE;
                case 'f': literal("false"); return FALSE;
                case 'n': literal("null"); return NULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("unexpected character");
            }
        }

        private void literal(String word) {
            if (!text.startsWith(word, at)) throw error("unexpected token");
            at += word.length();
        }

        private Json object(int depth) {
            if (depth > MAX_NESTING) throw error("nesting is deeper than " + MAX_NESTING);
            at++;
            var members = new LinkedHashMap<String, Json>();
            space();
            if (at < text.length() && text.charAt(at) == '}') { at++; return new Json(Type.OBJECT, Collections.unmodifiableMap(members)); }
            while (true) {
                space();
                if (at >= text.length() || text.charAt(at) != '"') throw error("expected a member name");
                String name = stringValue();
                space();
                if (at >= text.length() || text.charAt(at) != ':') throw error("expected ':'");
                at++;
                space();
                if (members.put(name, value(depth)) != null) throw error("duplicate member name");
                space();
                if (at >= text.length()) throw error("unexpected end of JSON");
                char c = text.charAt(at++);
                if (c == '}') return new Json(Type.OBJECT, Collections.unmodifiableMap(members));
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        private Json array(int depth) {
            if (depth > MAX_NESTING) throw error("nesting is deeper than " + MAX_NESTING);
            at++;
            var items = new ArrayList<Json>();
            space();
            if (at < text.length() && text.charAt(at) == ']') { at++; return new Json(Type.ARRAY, Collections.unmodifiableList(items)); }
            while (true) {
                space();
                items.add(value(depth));
                space();
                if (at >= text.length()) throw error("unexpected end of JSON");
                char c = text.charAt(at++);
                if (c == ']') return new Json(Type.ARRAY, Collections.unmodifiableList(items));
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        private String stringValue() {
            at++;
            var out = new StringBuilder();
            while (true) {
                if (at >= text.length()) throw error("unterminated string");
                char c = text.charAt(at++);
                if (c == '"') break;
                if (c < 0x20) throw error("unescaped control character in a string");
                if (c != '\\') { out.append(c); continue; }
                if (at >= text.length()) throw error("unterminated string");
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
                        if (at + 4 > text.length()) throw error("short unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(at++), 16);
                            if (digit < 0 || text.charAt(at - 1) > 'f') throw error("bad unicode escape");
                            code = code * 16 + digit;
                        }
                        out.append((char) code);
                        break;
                    default: throw error("bad escape");
                }
                if (out.length() > MAX_DOCUMENT_BYTES) throw error("string is too long");
            }
            if (out.length() > MAX_DOCUMENT_BYTES) throw error("string is too long");
            return out.toString();
        }

        private Json number() {
            int start = at;
            boolean integral = true;
            if (text.charAt(at) == '-') at++;
            if (at >= text.length()) throw error("bad number");
            char c = text.charAt(at);
            if (c == '0') at++;
            else if (c >= '1' && c <= '9') digits();
            else throw error("bad number");
            if (at < text.length() && text.charAt(at) == '.') {
                integral = false; at++;
                if (digits() == 0) throw error("bad number");
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                integral = false; at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                if (digits() == 0) throw error("bad number");
            }
            if (at - start > MAX_NUMBER_LENGTH) throw error("number is too long");
            if (at < text.length()) {
                char next = text.charAt(at);
                if ((next >= '0' && next <= '9') || next == '.' || Character.isLetter(next)) throw error("bad number");
            }
            String literal = text.substring(start, at);
            return integral ? integer(new BigInteger(literal)) : Json.number(Double.parseDouble(literal));
        }

        private int digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            return at - start;
        }
    }
}
