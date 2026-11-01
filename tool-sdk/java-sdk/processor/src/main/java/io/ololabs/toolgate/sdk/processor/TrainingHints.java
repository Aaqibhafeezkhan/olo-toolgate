// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/**
 * Turns {@link Training} and {@link TrainingValues} into one tool's entry of {@code training/annotations.json}, the
 * format {@code toolgate pack} folds into the tool schema (tool-sdk/spec/authoring/training.md). Every hint is checked
 * against the tool's inputs, so a renamed input or a changed type fails the build instead of producing stale data.
 */
final class TrainingHints {
    /** The resource the processor writes, beside the descriptors; the package build moves it out of the jar. */
    static final String RESOURCE = "META-INF/toolgate/training/annotations.json";
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    static final int MAX_ITEMS = 200;
    static final int MAX_TEXT = 1024;

    private TrainingHints() {}

    /**
     * The entry for one tool, or {@code null} when it declares no hints.
     *
     * @param properties the tool's input schemas by input name
     */
    static Map<String, Object> of(String tool, ExecutableElement method, Map<String, Object> properties) {
        Training training = method.getAnnotation(Training.class);
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (VariableElement p : method.getParameters()) {
            TrainingValues values = p.getAnnotation(TrainingValues.class);
            if (values == null) continue;
            Param param = p.getAnnotation(Param.class);
            if (param == null) throw new Problem(p, "@TrainingValues needs @Param on the same parameter");
            String input = param.name().isEmpty() ? p.getSimpleName().toString() : param.name();
            if (!values.name().isEmpty() && !values.name().equals(input)) {
                throw new Problem(p, "@TrainingValues name " + values.name() + " differs from the input name " + input + "; leave it out");
            }
            put(parameters, tool, input, values, properties, p);
        }
        if (training != null) {
            for (TrainingValues values : training.parameters()) {
                if (values.name().isEmpty()) throw new Problem(method, "@Training parameters of tool " + tool + " need a name");
                put(parameters, tool, values.name(), values, properties, method);
            }
        }
        if (training == null && parameters.isEmpty()) return null;

        Map<String, Object> entry = new LinkedHashMap<>();
        if (training != null) {
            texts(entry, "phrasings", training.phrasings(), tool, properties, method);
            texts(entry, "defaultPhrasings", training.defaultPhrasings(), tool, properties, method);
            texts(entry, "negatives", training.negatives(), tool, null, method);
        }
        if (!parameters.isEmpty()) entry.put("parameters", parameters);
        if (training != null && training.prompts().length > 0) {
            List<Object> prompts = new ArrayList<>();
            for (TrainingPrompt p : training.prompts()) {
                text(p.prompt(), "a prompt of tool " + tool, method);
                Object arguments = json(p.arguments(), "the arguments of prompt \"" + p.prompt() + "\"", method);
                if (!(arguments instanceof Map<?, ?> map)) throw new Problem(method, "the arguments of prompt \"" + p.prompt() + "\" must be a JSON object");
                for (Object key : map.keySet()) {
                    if (!properties.containsKey(key)) throw new Problem(method, "prompt \"" + p.prompt() + "\" passes " + key + ", which is not an input of tool " + tool);
                }
                Map<String, Object> prompt = new LinkedHashMap<>();
                prompt.put("prompt", p.prompt());
                prompt.put("arguments", arguments);
                prompts.add(prompt);
            }
            entry.put("examples", prompts);
        }
        return entry;
    }

    private static void put(Map<String, Object> parameters, String tool, String input, TrainingValues values,
            Map<String, Object> properties, Element at) {
        if (!(properties.get(input) instanceof Map<?, ?> schema)) {
            throw new Problem(at, "training values name " + input + ", which is not an input of tool " + tool);
        }
        if (parameters.containsKey(input)) throw new Problem(at, "training values for input " + input + " are declared twice");
        String type = DescriptorProcessor.valueType(schema);
        boolean text = "string".equals(schema.get("type"));
        Map<String, Object> notes = new LinkedHashMap<>();
        if (values.examples().length > 0) {
            if (values.examples().length > MAX_ITEMS) throw new Problem(at, "input " + input + " has more than " + MAX_ITEMS + " examples");
            List<Object> examples = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String example : values.examples()) {
                Object value = text ? example : json(example, "example " + example + " of input " + input, at);
                if (!fits(value, schema)) throw new Problem(at, "example " + example + " does not suit input " + input + " (" + type + ")");
                if (!seen.add(String.valueOf(value))) throw new Problem(at, "input " + input + " lists example " + example + " twice");
                examples.add(value);
            }
            notes.put("examples", examples);
        }
        if (values.phrasings().length > 0) {
            Map<String, Object> phrasings = new LinkedHashMap<>();
            for (ValuePhrasing phrasing : values.phrasings()) {
                String key = phrasing.value();
                if ("BOOLEAN".equals(type) && !key.equals("true") && !key.equals("false")) {
                    throw new Problem(at, "input " + input + " is a boolean, so a phrased value is true or false, not " + key);
                }
                if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(key)) {
                    throw new Problem(at, "input " + input + " has no value " + key + "; it allows " + allowed);
                }
                if (phrasing.phrasings().length == 0) throw new Problem(at, "value " + key + " of input " + input + " needs at least one phrasing");
                for (String words : phrasing.phrasings()) text(words, "a phrasing of input " + input, at);
                if (phrasings.put(key, List.of(phrasing.phrasings())) != null) {
                    throw new Problem(at, "value " + key + " of input " + input + " is phrased twice");
                }
            }
            notes.put("phrasings", phrasings);
        }
        if (notes.isEmpty()) throw new Problem(at, "training values for input " + input + " need examples or phrasings");
        parameters.put(input, notes);
    }

    /** Requests; with {@code properties}, each placeholder must name an input. */
    private static void texts(Map<String, Object> entry, String key, String[] values, String tool,
            Map<String, Object> properties, Element at) {
        if (values.length == 0) return;
        if (values.length > MAX_ITEMS) throw new Problem(at, "tool " + tool + " has more than " + MAX_ITEMS + " " + key);
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            text(value, "one of the " + key + " of tool " + tool, at);
            if (!seen.add(value)) throw new Problem(at, "tool " + tool + " lists \"" + value + "\" twice in " + key);
            Matcher m = PLACEHOLDER.matcher(value);
            while (m.find()) {
                if (properties == null) throw new Problem(at, "negative \"" + value + "\" of tool " + tool + " has a placeholder; negatives are said as written");
                if (!properties.containsKey(m.group(1))) {
                    throw new Problem(at, key + " \"" + value + "\" of tool " + tool + " names {" + m.group(1)
                            + "}, which is not an input; inputs are " + properties.keySet());
                }
            }
        }
        entry.put(key, Arrays.asList(values));
    }

    private static void text(String value, String what, Element at) {
        if (value.isBlank()) throw new Problem(at, what + " is blank");
        if (value.length() > MAX_TEXT) throw new Problem(at, what + " is longer than " + MAX_TEXT + " characters");
    }

    /** Whether a parsed example value suits an input schema's type. */
    static boolean fits(Object value, Map<?, ?> schema) {
        Object type = schema.get("type");
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) return false;
        if ("string".equals(type)) return value instanceof String;
        if ("integer".equals(type)) return value instanceof Long;
        if ("number".equals(type)) return value instanceof Long || value instanceof Double;
        if ("boolean".equals(type)) return value instanceof Boolean;
        if ("array".equals(type)) {
            if (!(value instanceof List<?> list)) return false;
            if (schema.get("items") instanceof Map<?, ?> items) for (Object item : list) if (!fits(item, items)) return false;
            return true;
        }
        if ("object".equals(type)) return value instanceof Map;
        return true;
    }

    static Object json(String text, String what, Element at) {
        try {
            return new Reader(text).document();
        } catch (IllegalArgumentException e) {
            throw new Problem(at, what + " is not JSON: " + e.getMessage());
        }
    }

    /** A strict JSON reader: objects keep their order, integers are {@code Long}, other numbers {@code Double}. */
    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Object document() {
            Object value = value();
            space();
            if (i != s.length()) throw fail("unexpected " + s.charAt(i));
            return value;
        }

        private Object value() {
            space();
            if (i >= s.length()) throw fail("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) return literal("true", Boolean.TRUE);
            if (s.startsWith("false", i)) return literal("false", Boolean.FALSE);
            if (s.startsWith("null", i)) throw fail("null is not an example value");
            return number();
        }

        private Object literal(String word, Object value) {
            i += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            space();
            if (peek('}')) return map;
            while (true) {
                space();
                if (i >= s.length() || s.charAt(i) != '"') throw fail("expected a key");
                String key = string();
                space();
                expect(':');
                if (map.put(key, value()) != null) throw fail("duplicate key " + key);
                space();
                if (peek('}')) return map;
                expect(',');
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            space();
            if (peek(']')) return list;
            while (true) {
                list.add(value());
                space();
                if (peek(']')) return list;
                expect(',');
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw fail("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw fail("control character in string");
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) throw fail("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw fail("short unicode escape");
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw fail("bad escape \\" + e);
                }
            }
        }

        private Object number() {
            int start = i;
            if (peekAt('-')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (!n.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) throw fail("not a value: " + (n.isEmpty() ? s.substring(start) : n));
            if (n.matches("-?\\d+")) {
                try {
                    return Long.parseLong(n);
                } catch (NumberFormatException e) {
                    throw fail("integer out of range: " + n);
                }
            }
            return Double.parseDouble(n);
        }

        private void space() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private boolean peek(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private boolean peekAt(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw fail("expected " + c);
        }

        private IllegalArgumentException fail(String message) {
            return new IllegalArgumentException(message + " at " + i);
        }
    }
}
