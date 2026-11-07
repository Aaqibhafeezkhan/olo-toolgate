// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.trigger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Opaque trigger state the runtime commits once every event emitted before it is durable (plan 6.6). It is a JSON
 * object: values are strings, booleans, {@code long}s, finite {@code double}s, lists and string-keyed maps of those,
 * or null. It never holds secrets, and its encoding must fit the trigger's {@code maxCheckpointBytes}. Immutable.
 */
public final class Checkpoint {
    private final Map<String, Object> values;

    private Checkpoint(Map<String, Object> values) {
        this.values = values;
    }

    public static Checkpoint of(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        return new Checkpoint(object(values, "checkpoint"));
    }

    public static Checkpoint of(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return of(m);
    }

    public static Checkpoint of(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        if (m.containsKey(k2)) throw new IllegalArgumentException("checkpoint key " + k2 + " is given twice");
        m.put(k2, v2);
        return of(m);
    }

    public static Checkpoint of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        for (Object[] kv : new Object[][] {{k2, v2}, {k3, v3}}) {
            if (m.containsKey((String) kv[0])) throw new IllegalArgumentException("checkpoint key " + kv[0] + " is given twice");
            m.put((String) kv[0], kv[1]);
        }
        return of(m);
    }

    /** A copy with {@code key} set to {@code value}. */
    public Checkpoint with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        m.put(key, value);
        return of(m);
    }

    /** Every member, in insertion order; nested lists and maps are unmodifiable too. */
    public Map<String, Object> values() {
        return values;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public String getString(String key) {
        return typed(key, String.class);
    }

    public long getLong(String key) {
        return typed(key, Long.class);
    }

    public double getDouble(String key) {
        Object v = require(key);
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d;
        throw new IllegalStateException("checkpoint member " + key + " is not a number");
    }

    public boolean getBoolean(String key) {
        return typed(key, Boolean.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        return typed(key, Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        return typed(key, List.class);
    }

    private Object require(String key) {
        Object v = values.get(key);
        if (v == null) throw new NoSuchElementException("checkpoint has no member " + key);
        return v;
    }

    private <T> T typed(String key, Class<T> type) {
        Object v = require(key);
        if (!type.isInstance(v)) throw new IllegalStateException("checkpoint member " + key + " is not a " + type.getSimpleName());
        return type.cast(v);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Checkpoint c && c.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Checkpoint" + values;
    }

    private static Map<String, Object> object(Map<?, ?> source, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            if (!(e.getKey() instanceof String key)) throw new IllegalArgumentException(path + " has a key that is not a string");
            m.put(key, value(e.getValue(), path + "." + key));
        }
        return Collections.unmodifiableMap(m);
    }

    private static Object value(Object v, String path) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long) return v;
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof BigInteger b) {
            try {
                return b.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(path + " does not fit a long");
            }
        }
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException(path + " is not a finite number");
            if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) return (long) d;
            return d;
        }
        if (v instanceof Map<?, ?> map) return object(map, path);
        if (v instanceof Iterable<?> items) {
            List<Object> list = new ArrayList<>();
            int i = 0;
            for (Object item : items) list.add(value(item, path + "[" + i++ + "]"));
            return Collections.unmodifiableList(list);
        }
        throw new IllegalArgumentException(path + " is a " + v.getClass().getName() + "; a checkpoint holds JSON values only");
    }
}
