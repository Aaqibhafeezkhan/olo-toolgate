// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The API difference between two versions of one package (design §6.2 B).
 *
 * <ul>
 *   <li>Breaking: a tool removed; an input property removed, newly required or narrowed (type or enum); an output
 *       property removed or its type changed to one consumers don't expect.</li>
 *   <li>Compatible: a tool added; an optional input or an output property added; an input widened or made optional.</li>
 *   <li>Anything else, such as descriptions, is no API change.</li>
 * </ul>
 */
public final class ApiDiff {
    private static final int MAX_DEPTH = 16;

    public record Result(String apiChange, List<String> changes) {
    }

    private final List<String> breaking = new ArrayList<>();
    private final List<String> compatible = new ArrayList<>();

    private ApiDiff() {
    }

    public static Result between(Map<String, Object> previousManifest, Map<String, Object> manifest) {
        ApiDiff diff = new ApiDiff();
        Map<String, Map<String, Object>> before = tools(previousManifest);
        Map<String, Map<String, Object>> after = tools(manifest);
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                diff.breaking.add("tool " + name + " removed");
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : after.entrySet()) {
            Map<String, Object> old = before.get(e.getKey());
            if (old == null) {
                diff.compatible.add("tool " + e.getKey() + " added");
                continue;
            }
            diff.input(e.getKey() + " input", Json.obj(old.get("inputSchema")), Json.obj(e.getValue().get("inputSchema")), 0);
            diff.output(e.getKey() + " output", Json.obj(old.get("outputSchema")), Json.obj(e.getValue().get("outputSchema")), 0);
        }
        List<String> changes = new ArrayList<>();
        changes.addAll(diff.breaking.stream().map(c -> "breaking: " + c).toList());
        changes.addAll(diff.compatible.stream().map(c -> "compatible: " + c).toList());
        String kind = !diff.breaking.isEmpty() ? "breaking" : !diff.compatible.isEmpty() ? "compatible" : "none";
        return new Result(kind, changes);
    }

    /** Tool descriptors by local name (the part of the toolId after the package id). */
    static Map<String, Map<String, Object>> tools(Map<String, Object> manifest) {
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        for (Object tool : Json.arr(manifest.get("tools"))) {
            Map<String, Object> descriptor = Json.obj(Json.obj(tool).get("descriptor"));
            String id = Json.str(descriptor.get("toolId"));
            if (id != null) {
                tools.put(id.substring(id.indexOf('/') + 1), descriptor);
            }
        }
        return tools;
    }

    private void input(String where, Map<String, Object> old, Map<String, Object> now, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        // An empty set means any value, so it is the widest possible.
        Set<String> oldTypes = types(old);
        Set<String> newTypes = types(now);
        if (!newTypes.isEmpty() && (oldTypes.isEmpty() || !newTypes.containsAll(oldTypes))) {
            breaking.add(where + " type narrowed from " + describe(oldTypes) + " to " + describe(newTypes));
        } else if (!oldTypes.equals(newTypes)) {
            compatible.add(where + " type widened to " + describe(newTypes));
        }
        Set<Object> oldEnum = new HashSet<>(Json.arr(old.get("enum")));
        Set<Object> newEnum = new HashSet<>(Json.arr(now.get("enum")));
        if (!newEnum.isEmpty() && (oldEnum.isEmpty() || !newEnum.containsAll(oldEnum))) {
            breaking.add(where + " allowed values narrowed");
        } else if (!oldEnum.equals(newEnum)) {
            compatible.add(where + " allowed values widened");
        }
        Map<String, Object> oldProps = Json.obj(old.get("properties"));
        Map<String, Object> newProps = Json.obj(now.get("properties"));
        Set<Object> oldRequired = new HashSet<>(Json.arr(old.get("required")));
        Set<Object> newRequired = new HashSet<>(Json.arr(now.get("required")));
        for (String name : oldProps.keySet()) {
            if (!newProps.containsKey(name)) {
                breaking.add(where + " property " + name + " removed");
            }
        }
        for (Map.Entry<String, Object> e : newProps.entrySet()) {
            String name = e.getKey();
            if (!oldProps.containsKey(name)) {
                if (newRequired.contains(name)) {
                    breaking.add(where + " required property " + name + " added");
                } else {
                    compatible.add(where + " optional property " + name + " added");
                }
                continue;
            }
            if (newRequired.contains(name) && !oldRequired.contains(name)) {
                breaking.add(where + " property " + name + " is now required");
            } else if (oldRequired.contains(name) && !newRequired.contains(name)) {
                compatible.add(where + " property " + name + " is now optional");
            }
            input(where + "." + name, Json.obj(oldProps.get(name)), Json.obj(e.getValue()), depth + 1);
        }
        if (old.containsKey("items") || now.containsKey("items")) {
            input(where + "[]", Json.obj(old.get("items")), Json.obj(now.get("items")), depth + 1);
        }
    }

    private void output(String where, Map<String, Object> old, Map<String, Object> now, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        Set<String> oldTypes = types(old);
        Set<String> newTypes = types(now);
        if (!oldTypes.isEmpty() && (newTypes.isEmpty() || !oldTypes.containsAll(newTypes))) {
            breaking.add(where + " type changed from " + describe(oldTypes) + " to " + describe(newTypes));
        }
        Map<String, Object> oldProps = Json.obj(old.get("properties"));
        Map<String, Object> newProps = Json.obj(now.get("properties"));
        for (Map.Entry<String, Object> e : oldProps.entrySet()) {
            if (!newProps.containsKey(e.getKey())) {
                breaking.add(where + " field " + e.getKey() + " removed");
            } else {
                output(where + "." + e.getKey(), Json.obj(e.getValue()), Json.obj(newProps.get(e.getKey())), depth + 1);
            }
        }
        for (String name : newProps.keySet()) {
            if (!oldProps.containsKey(name)) {
                compatible.add(where + " field " + name + " added");
            }
        }
        if (old.containsKey("items") && now.containsKey("items")) {
            output(where + "[]", Json.obj(old.get("items")), Json.obj(now.get("items")), depth + 1);
        }
    }

    private static String describe(Set<String> types) {
        return types.isEmpty() ? "any" : String.join("|", new java.util.TreeSet<>(types));
    }

    /** The JSON Schema types a schema allows; integer counts as a number too. Empty means unconstrained. */
    private static Set<String> types(Map<String, Object> schema) {
        Set<String> types = new HashSet<>();
        Object type = schema.get("type");
        if (type instanceof String s) {
            types.add(s);
        } else {
            for (Object t : Json.arr(type)) {
                if (t instanceof String s) {
                    types.add(s);
                }
            }
        }
        if (types.contains("number")) {
            types.add("integer");
        }
        return types;
    }
}
