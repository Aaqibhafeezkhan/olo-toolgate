// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.sdk.annotations.Param;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Converts between JSON values and the Java types of authoring README §7, mirroring the processor's mapping. */
final class Converter {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Converter() {}

    /** A value that does not fit the Java type. */
    static final class ConversionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConversionException(String message) {
            super(message, null, false, false);
        }
    }

    // ---- Java to JSON ----

    static JsonNode toJson(Object value) {
        if (value == null) return JSON.nullNode();
        if (value instanceof JsonNode node) return node;
        if (value instanceof Optional<?> o) return o.isPresent() ? toJson(o.get()) : JSON.nullNode();
        if (value instanceof String s) return JSON.textNode(s);
        if (value instanceof Character c) return JSON.textNode(String.valueOf(c));
        if (value instanceof Boolean b) return JSON.booleanNode(b);
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return JSON.numberNode(((Number) value).intValue());
        if (value instanceof Long l) return JSON.numberNode(l);
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new ConversionException("a number is not finite");
            return JSON.numberNode(d);
        }
        if (value instanceof BigDecimal d) return JSON.numberNode(d);
        if (value instanceof BigInteger i) return JSON.numberNode(i);
        if (value instanceof Enum<?> e) return JSON.textNode(e.name());
        if (value instanceof LocalDate || value instanceof OffsetDateTime || value instanceof Duration || value instanceof URI) {
            return JSON.textNode(value.toString());
        }
        if (value instanceof byte[] bytes) return JSON.textNode(Base64.getEncoder().encodeToString(bytes));
        if (value instanceof Map<?, ?> map) {
            ObjectNode out = JSON.objectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) out.set(String.valueOf(e.getKey()), toJson(e.getValue()));
            return out;
        }
        if (value instanceof Collection<?> c) {
            ArrayNode out = JSON.arrayNode();
            for (Object item : c) out.add(toJson(item));
            return out;
        }
        if (value.getClass().isArray()) {
            ArrayNode out = JSON.arrayNode();
            for (int i = 0; i < Array.getLength(value); i++) out.add(toJson(Array.get(value, i)));
            return out;
        }
        ObjectNode out = JSON.objectNode();
        if (value.getClass().isRecord()) {
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                put(out, propertyName(c), invoke(c.getAccessor(), value));
            }
        } else {
            for (Method m : value.getClass().getMethods()) {
                String name = beanProperty(m);
                if (name != null) put(out, name, invoke(m, value));
            }
        }
        return out;
    }

    private static void put(ObjectNode out, String name, Object value) {
        if (value == null || (value instanceof Optional<?> o && o.isEmpty())) return;
        out.set(name, toJson(value));
    }

    private static Object invoke(Method m, Object target) {
        try {
            m.setAccessible(true);
            return m.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            throw new ConversionException("cannot read " + m.getName() + " of " + target.getClass().getSimpleName());
        }
    }

    static String propertyName(RecordComponent c) {
        Param p = c.getAnnotation(Param.class);
        return p != null && !p.name().isEmpty() ? p.name() : c.getName();
    }

    static String beanProperty(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.getReturnType() == void.class) return null;
        String n = m.getName();
        String rest;
        if (n.startsWith("get") && n.length() > 3 && !n.equals("getClass")) rest = n.substring(3);
        else if (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class) rest = n.substring(2);
        else return null;
        if (rest.length() > 1 && Character.isUpperCase(rest.charAt(0)) && Character.isUpperCase(rest.charAt(1))) return rest;
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    // ---- JSON to Java ----

    /** Converts a JSON value, or null for absent, to the Java type. */
    static Object fromJson(JsonNode value, Type type) {
        Class<?> raw = raw(type);
        if (raw == Optional.class) {
            return value == null || value.isNull() ? Optional.empty() : Optional.of(fromJson(value, argument(type, 0)));
        }
        if (value == null || value.isNull()) return raw.isPrimitive() ? primitiveDefault(raw) : null;
        try {
            if (raw == String.class) return text(value);
            if (raw == int.class || raw == Integer.class) return value.canConvertToExactIntegral() && value.canConvertToInt() ? value.intValue() : fail(value, type);
            if (raw == long.class || raw == Long.class) return value.canConvertToExactIntegral() && value.canConvertToLong() ? value.longValue() : fail(value, type);
            if (raw == short.class || raw == Short.class) return (short) (int) (Integer) fromJson(value, Integer.class);
            if (raw == byte.class || raw == Byte.class) return (byte) (int) (Integer) fromJson(value, Integer.class);
            if (raw == double.class || raw == Double.class) return value.isNumber() ? value.doubleValue() : fail(value, type);
            if (raw == float.class || raw == Float.class) return value.isNumber() ? value.floatValue() : fail(value, type);
            if (raw == BigDecimal.class) return value.isNumber() ? value.decimalValue() : fail(value, type);
            if (raw == boolean.class || raw == Boolean.class) return value.isBoolean() ? value.booleanValue() : fail(value, type);
            if (raw == LocalDate.class) return LocalDate.parse(text(value));
            if (raw == OffsetDateTime.class) return OffsetDateTime.parse(text(value));
            if (raw == Duration.class) return Duration.parse(text(value));
            if (raw == URI.class) return new URI(text(value));
            if (raw == byte[].class) return Base64.getDecoder().decode(text(value));
            if (raw.isEnum()) return enumValue(raw, text(value));
            if (raw == JsonNode.class) return value;
            if (Map.class.isAssignableFrom(raw)) {
                if (!value.isObject()) return fail(value, type);
                Type valueType = type instanceof ParameterizedType ? argument(type, 1) : Object.class;
                Map<String, Object> out = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    out.put(f.getKey(), fromJson(f.getValue(), valueType));
                }
                return out;
            }
            if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
                if (!value.isArray()) return fail(value, type);
                Type item = raw.isArray() ? componentType(type) : type instanceof ParameterizedType ? argument(type, 0) : Object.class;
                List<Object> items = new ArrayList<>();
                for (JsonNode v : value) items.add(fromJson(v, item));
                if (raw.isArray()) {
                    Object array = Array.newInstance(raw.getComponentType(), items.size());
                    for (int i = 0; i < items.size(); i++) Array.set(array, i, items.get(i));
                    return array;
                }
                if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(items);
                return items;
            }
            if (raw == Object.class) return plain(value);
            if (!value.isObject()) return fail(value, type);
            return raw.isRecord() ? record(value, raw) : bean(value, raw);
        } catch (ConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionException("cannot read " + value.getNodeType().name().toLowerCase(java.util.Locale.ROOT) + " as " + raw.getSimpleName());
        }
    }

    private static Object record(JsonNode value, Class<?> raw) throws ReflectiveOperationException {
        RecordComponent[] components = raw.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            String name = propertyName(components[i]);
            args[i] = fromJson(value.get(name), components[i].getGenericType());
        }
        Constructor<?> c = raw.getDeclaredConstructor(types);
        c.setAccessible(true);
        try {
            return c.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new ConversionException("the " + raw.getSimpleName() + " constructor refused the value");
        }
    }

    private static Object bean(JsonNode value, Class<?> raw) throws ReflectiveOperationException {
        Constructor<?> c = raw.getDeclaredConstructor();
        c.setAccessible(true);
        Object bean = c.newInstance();
        for (Method m : raw.getMethods()) {
            if (!m.getName().startsWith("set") || m.getParameterCount() != 1 || Modifier.isStatic(m.getModifiers())) continue;
            String rest = m.getName().substring(3);
            if (rest.isEmpty()) continue;
            String name = rest.length() > 1 && Character.isUpperCase(rest.charAt(1)) ? rest : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            if (value.has(name)) m.invoke(bean, fromJson(value.get(name), m.getGenericParameterTypes()[0]));
        }
        return bean;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> raw, String name) {
        try {
            return Enum.valueOf((Class) raw, name);
        } catch (IllegalArgumentException e) {
            throw new ConversionException(name + " is not a " + raw.getSimpleName());
        }
    }

    private static Object plain(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.canConvertToLong() ? (Object) value.longValue() : value.bigIntegerValue();
        if (value.isNumber()) return value.decimalValue();
        if (value.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode v : value) out.add(plain(v));
            return out;
        }
        if (value.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            value.properties().forEach(f -> out.put(f.getKey(), plain(f.getValue())));
            return out;
        }
        return null;
    }

    private static String text(JsonNode value) {
        if (!value.isTextual()) throw new ConversionException("expected a string");
        return value.asText();
    }

    private static Object fail(JsonNode value, Type type) {
        throw new ConversionException("cannot read " + value.getNodeType().name().toLowerCase(java.util.Locale.ROOT) + " as " + raw(type).getSimpleName());
    }

    private static Object primitiveDefault(Class<?> raw) {
        if (raw == boolean.class) return false;
        if (raw == double.class) return 0d;
        if (raw == float.class) return 0f;
        if (raw == long.class) return 0L;
        if (raw == short.class) return (short) 0;
        if (raw == byte.class) return (byte) 0;
        if (raw == char.class) return (char) 0;
        return 0;
    }

    static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (type instanceof GenericArrayType g) return Array.newInstance(raw(g.getGenericComponentType()), 0).getClass();
        return Object.class;
    }

    private static Type argument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) return p.getActualTypeArguments()[index];
        return Object.class;
    }

    private static Type componentType(Type type) {
        if (type instanceof GenericArrayType g) return g.getGenericComponentType();
        return ((Class<?>) type).getComponentType();
    }
}
