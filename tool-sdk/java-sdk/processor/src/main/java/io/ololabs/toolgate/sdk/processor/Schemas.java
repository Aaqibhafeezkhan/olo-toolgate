// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/** Schema generation rules of descriptor §5, generation 1. */
final class Schemas {
    static final long INT_MIN = Integer.MIN_VALUE;
    static final long INT_MAX = Integer.MAX_VALUE;
    static final long SAFE = 9007199254740991L;

    private final Types types;
    private final ProcessingEnvironment env;
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> recursive = new HashSet<>();
    private final Map<String, Object> defs = new TreeMap<>();

    Schemas(ProcessingEnvironment env) {
        this.env = env;
        this.types = env.getTypeUtils();
    }

    /** The closed input object over the given properties. */
    Map<String, Object> inputs(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = closed(properties, required);
        if (!defs.isEmpty()) schema.put("$defs", new TreeMap<>(defs));
        return schema;
    }

    /** The output schema of a return type, or null for ToolResult. */
    Map<String, Object> output(TypeMirror type, Element at) {
        Map<String, Object> schema = schema(type, null, at);
        if (!"object".equals(schema.get("type")) && !schema.containsKey("$ref")) {
            throw new Problem(at, "the return type must be a record, a POJO, a Map<String, ?> or ToolResult");
        }
        if (!defs.isEmpty()) schema.put("$defs", new TreeMap<>(defs));
        return schema;
    }

    /** Whether a declared type is Optional or annotated with any annotation named Nullable. */
    boolean optional(Element element, TypeMirror type) {
        return isOptional(type) || nullable(element.getAnnotationMirrors()) || nullable(type.getAnnotationMirrors());
    }

    private static boolean nullable(List<? extends AnnotationMirror> annotations) {
        for (AnnotationMirror a : annotations) {
            if (a.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable")) return true;
        }
        return false;
    }

    private boolean isOptional(TypeMirror type) {
        return qualifiedName(type).equals("java.util.Optional");
    }

    /** The schema of one value; spec is null for unannotated properties and array items. */
    Map<String, Object> schema(TypeMirror type, Spec spec, Element at) {
        if (isOptional(type)) type = typeArgument(type, 0, at);
        ValueType inferred = infer(type, at);
        ValueType vt = resolve(inferred, spec, at);
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (vt) {
            case STRING -> schema.put("type", "string");
            case INT, LONG -> schema.put("type", "integer");
            case NUMBER -> schema.put("type", "number");
            case BOOLEAN -> schema.put("type", "boolean");
            case DATE -> format(schema, "date");
            case DATE_TIME -> format(schema, "date-time");
            case DURATION -> format(schema, "duration");
            case URI -> format(schema, "uri");
            case BINARY -> {
                schema.put("type", "string");
                schema.put("contentEncoding", "base64");
            }
            case ENUM -> {
                schema.put("type", "string");
                schema.put("enum", inferred == ValueType.ENUM ? constants(type) : new ArrayList<>(spec.enumValues()));
            }
            case ARRAY -> {
                schema.put("type", "array");
                schema.put("items", schema(elementType(type, at), null, at));
            }
            case OBJECT -> {
                if (isMap(type)) {
                    if (!qualifiedName(typeArgument(type, 0, at)).equals("java.lang.String")) {
                        throw new Problem(at, "map keys must be String");
                    }
                    TypeMirror value = typeArgument(type, 1, at);
                    schema.put("type", "object");
                    // Map<String, Object> holds any JSON value: the empty schema.
                    schema.put("additionalProperties", qualifiedName(value).equals("java.lang.Object")
                            ? new LinkedHashMap<String, Object>() : schema(value, null, at));
                } else {
                    return withSpec(object((TypeElement) types.asElement(type), at), spec, vt, at);
                }
            }
            default -> throw new Problem(at, "unsupported type " + type);
        }
        return withSpec(schema, spec, vt, at);
    }

    private static void format(Map<String, Object> schema, String format) {
        schema.put("type", "string");
        schema.put("format", format);
    }

    private Map<String, Object> withSpec(Map<String, Object> schema, Spec spec, ValueType vt, Element at) {
        if (spec == null) {
            if (vt == ValueType.INT) bounds(schema, INT_MIN, INT_MAX);
            if (vt == ValueType.LONG) bounds(schema, -SAFE, SAFE);
            return schema;
        }
        if (!spec.description().isEmpty()) schema.put("description", spec.description());
        constraints(schema, vt, spec, at, true);
        return schema;
    }

    private static void bounds(Map<String, Object> schema, long min, long max) {
        schema.put("minimum", min);
        schema.put("maximum", max);
    }

    /**
     * Adds the declared constraint keywords; with materialise, INT and LONG get their range bounds unless
     * narrower ones are declared.
     */
    static void constraints(Map<String, Object> schema, ValueType vt, Spec spec, Element at, boolean materialise) {
        String name = spec.name().isEmpty() ? at.getSimpleName().toString() : spec.name();
        boolean numeric = vt == ValueType.INT || vt == ValueType.LONG || vt == ValueType.NUMBER;
        boolean text = vt == ValueType.STRING || vt == ValueType.URI;
        if (!numeric && (!spec.min().isEmpty() || !spec.max().isEmpty())) {
            throw new Problem(at, name + ": min and max apply to INT, LONG and NUMBER only");
        }
        if (!text && (spec.minLength() >= 0 || spec.maxLength() >= 0 || !spec.pattern().isEmpty())) {
            throw new Problem(at, name + ": minLength, maxLength and pattern apply to STRING and URI only");
        }
        if (!spec.format().isEmpty() && vt != ValueType.STRING) {
            throw new Problem(at, name + ": format applies to STRING only; " + vt + " has its own format");
        }
        if (!spec.enumValues().isEmpty() && vt != ValueType.ENUM) {
            throw new Problem(at, name + ": enumValues applies to a String typed as ENUM");
        }
        if (vt == ValueType.INT || vt == ValueType.LONG) {
            long lo = vt == ValueType.INT ? INT_MIN : -SAFE;
            long hi = vt == ValueType.INT ? INT_MAX : SAFE;
            Long min = spec.min().isEmpty() ? null : integer(spec.min(), lo, hi, name, at);
            Long max = spec.max().isEmpty() ? null : integer(spec.max(), lo, hi, name, at);
            if (min != null && max != null && min > max) throw new Problem(at, name + ": min is greater than max");
            if (min != null || materialise) schema.put("minimum", min != null ? min : lo);
            if (max != null || materialise) schema.put("maximum", max != null ? max : hi);
        } else if (vt == ValueType.NUMBER) {
            Double min = spec.min().isEmpty() ? null : number(spec.min(), name, at);
            Double max = spec.max().isEmpty() ? null : number(spec.max(), name, at);
            if (min != null && max != null && min > max) throw new Problem(at, name + ": min is greater than max");
            if (min != null) schema.put("minimum", min);
            if (max != null) schema.put("maximum", max);
        }
        if (spec.minLength() < -1 || spec.maxLength() < -1) throw new Problem(at, name + ": lengths must be at least 0");
        if (spec.minLength() >= 0) schema.put("minLength", (long) spec.minLength());
        if (spec.maxLength() >= 0) schema.put("maxLength", (long) spec.maxLength());
        if (spec.minLength() >= 0 && spec.maxLength() >= 0 && spec.minLength() > spec.maxLength()) {
            throw new Problem(at, name + ": minLength is greater than maxLength");
        }
        if (!spec.pattern().isEmpty()) {
            try {
                Pattern.compile(spec.pattern());
            } catch (PatternSyntaxException e) {
                throw new Problem(at, name + ": pattern does not compile: " + e.getDescription());
            }
            schema.put("pattern", spec.pattern());
        }
        if (!spec.format().isEmpty()) schema.put("format", spec.format());
        if (!spec.defaultValue().isEmpty()) {
            schema.put("default", typedDefault(vt, spec, schema, name, at));
        }
        if (spec.sensitive()) schema.put("x-toolgate-sensitive", true);
    }

    @SuppressWarnings("unchecked")
    private static Object typedDefault(ValueType vt, Spec spec, Map<String, Object> schema, String name, Element at) {
        String raw = spec.defaultValue();
        switch (vt) {
            case INT, LONG -> {
                long lo = schema.containsKey("minimum") ? (Long) schema.get("minimum") : vt == ValueType.INT ? INT_MIN : -SAFE;
                long hi = schema.containsKey("maximum") ? (Long) schema.get("maximum") : vt == ValueType.INT ? INT_MAX : SAFE;
                return integer(raw, lo, hi, name + " default", at);
            }
            case NUMBER -> {
                return number(raw, name + " default", at);
            }
            case BOOLEAN -> {
                if (!raw.equals("true") && !raw.equals("false")) throw new Problem(at, name + ": default must be true or false");
                return Boolean.valueOf(raw);
            }
            case ENUM -> {
                List<String> values = (List<String>) schema.get("enum");
                if (values == null || !values.contains(raw)) throw new Problem(at, name + ": default " + raw + " is not an enum value");
                return raw;
            }
            case STRING, DATE, DATE_TIME, DURATION, URI -> {
                return raw;
            }
            default -> throw new Problem(at, name + ": " + vt + " values cannot have a default");
        }
    }

    private static long integer(String raw, long lo, long hi, String name, Element at) {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new Problem(at, name + ": " + raw + " is not an integer");
        }
        if (value < lo || value > hi) throw new Problem(at, name + ": " + raw + " is outside " + lo + ".." + hi);
        return value;
    }

    private static double number(String raw, String name, Element at) {
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new Problem(at, name + ": " + raw + " is not a number");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new Problem(at, name + ": " + raw + " is not finite");
        return value;
    }

    private static ValueType resolve(ValueType inferred, Spec spec, Element at) {
        if (spec == null) return inferred;
        ValueType declared = spec.type();
        if (declared == ValueType.INFER) {
            return inferred == ValueType.STRING && !spec.enumValues().isEmpty() ? ValueType.ENUM : inferred;
        }
        if (declared == inferred) return declared;
        if (inferred == ValueType.STRING && (declared == ValueType.DATE || declared == ValueType.DATE_TIME
                || declared == ValueType.DURATION || declared == ValueType.URI)) {
            return declared;
        }
        if (inferred == ValueType.STRING && declared == ValueType.ENUM) {
            if (spec.enumValues().isEmpty()) throw new Problem(at, "a String typed as ENUM needs enumValues");
            return declared;
        }
        throw new Problem(at, "type " + declared + " does not match the Java type, which maps to " + inferred);
    }

    /** The value type a Java type maps to (authoring README §7). */
    ValueType infer(TypeMirror type, Element at) {
        switch (type.getKind()) {
            case INT, SHORT, BYTE -> {
                return ValueType.INT;
            }
            case LONG -> {
                return ValueType.LONG;
            }
            case DOUBLE, FLOAT -> {
                return ValueType.NUMBER;
            }
            case BOOLEAN -> {
                return ValueType.BOOLEAN;
            }
            case ARRAY -> {
                return ((ArrayType) type).getComponentType().getKind() == TypeKind.BYTE ? ValueType.BINARY : ValueType.ARRAY;
            }
            case DECLARED -> {
                switch (qualifiedName(type)) {
                    case "java.lang.String" -> {
                        return ValueType.STRING;
                    }
                    case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> {
                        return ValueType.INT;
                    }
                    case "java.lang.Long" -> {
                        return ValueType.LONG;
                    }
                    case "java.lang.Double", "java.lang.Float", "java.math.BigDecimal" -> {
                        return ValueType.NUMBER;
                    }
                    case "java.lang.Boolean" -> {
                        return ValueType.BOOLEAN;
                    }
                    case "java.time.LocalDate" -> {
                        return ValueType.DATE;
                    }
                    case "java.time.OffsetDateTime" -> {
                        return ValueType.DATE_TIME;
                    }
                    case "java.time.Duration" -> {
                        return ValueType.DURATION;
                    }
                    case "java.net.URI" -> {
                        return ValueType.URI;
                    }
                    default -> {
                        Element e = types.asElement(type);
                        if (e.getKind() == ElementKind.ENUM) return ValueType.ENUM;
                        if (isMap(type)) return ValueType.OBJECT;
                        if (isA(type, "java.util.Collection")) return ValueType.ARRAY;
                        if (qualifiedName(type).startsWith("java.") || qualifiedName(type).startsWith("javax.")) {
                            throw new Problem(at, "unsupported type " + type);
                        }
                        return ValueType.OBJECT;
                    }
                }
            }
            default -> throw new Problem(at, "unsupported type " + type);
        }
    }

    private Map<String, Object> object(TypeElement type, Element at) {
        String name = type.getSimpleName().toString();
        if (stack.contains(name)) {
            recursive.add(name);
            return ref(name);
        }
        stack.push(name);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        try {
            if (type.getKind() == ElementKind.RECORD) {
                for (RecordComponentElement c : type.getRecordComponents()) {
                    Param p = c.getAnnotation(Param.class);
                    Spec spec = p == null ? null : Spec.of(p);
                    String prop = p != null && !p.name().isEmpty() ? p.name() : c.getSimpleName().toString();
                    property(properties, prop, schema(c.asType(), spec, c), c);
                    boolean optional = optional(c, c.asType()) || (p != null && (!p.required() || !p.defaultValue().isEmpty()));
                    if (!optional) required.add(prop);
                }
            } else {
                for (ExecutableElement m : ElementFilter.methodsIn(env.getElementUtils().getAllMembers(type))) {
                    String prop = beanProperty(m);
                    if (prop == null) continue;
                    property(properties, prop, schema(m.getReturnType(), null, m), m);
                    if (!optional(m, m.getReturnType())) required.add(prop);
                }
                if (properties.isEmpty()) {
                    throw new Problem(at, name + " has no properties; use a record, a POJO with getters, or a Map");
                }
            }
        } finally {
            stack.pop();
        }
        Map<String, Object> schema = closed(properties, required);
        if (recursive.contains(name)) {
            defs.put(name, schema);
            return ref(name);
        }
        return schema;
    }

    private static void property(Map<String, Object> properties, String name, Map<String, Object> schema, Element at) {
        if (properties.put(name, schema) != null) throw new Problem(at, "duplicate property " + name);
    }

    private static String beanProperty(ExecutableElement m) {
        if (!m.getModifiers().contains(Modifier.PUBLIC) || m.getModifiers().contains(Modifier.STATIC)) return null;
        if (!m.getParameters().isEmpty() || m.getReturnType().getKind() == TypeKind.VOID) return null;
        String n = m.getSimpleName().toString();
        String rest;
        if (n.startsWith("get") && n.length() > 3 && !n.equals("getClass")) rest = n.substring(3);
        else if (n.startsWith("is") && n.length() > 2 && m.getReturnType().getKind() == TypeKind.BOOLEAN) rest = n.substring(2);
        else return null;
        if (rest.length() > 1 && Character.isUpperCase(rest.charAt(0)) && Character.isUpperCase(rest.charAt(1))) return rest;
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static Map<String, Object> ref(String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("$ref", "#/$defs/" + name);
        return ref;
    }

    static Map<String, Object> closed(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Jcs.sorted(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static List<String> constants(TypeMirror type) {
        List<String> values = new ArrayList<>();
        for (Element e : ((DeclaredType) type).asElement().getEnclosedElements()) {
            if (e.getKind() == ElementKind.ENUM_CONSTANT) values.add(e.getSimpleName().toString());
        }
        return values;
    }

    private TypeMirror elementType(TypeMirror type, Element at) {
        if (type.getKind() == TypeKind.ARRAY) return ((ArrayType) type).getComponentType();
        TypeElement collection = env.getElementUtils().getTypeElement("java.util.Collection");
        for (TypeMirror t = type; t != null; ) {
            if (types.isSameType(types.erasure(t), types.erasure(collection.asType()))) return typeArgument(t, 0, at);
            TypeMirror next = null;
            for (TypeMirror s : types.directSupertypes(t)) {
                if (isA(s, "java.util.Collection")) next = s;
            }
            t = next;
        }
        throw new Problem(at, "cannot find the element type of " + type);
    }

    private boolean isMap(TypeMirror type) {
        return isA(type, "java.util.Map");
    }

    private boolean isA(TypeMirror type, String name) {
        TypeElement target = env.getElementUtils().getTypeElement(name);
        return type.getKind() == TypeKind.DECLARED && types.isAssignable(types.erasure(type), types.erasure(target.asType()));
    }

    private TypeMirror typeArgument(TypeMirror type, int index, Element at) {
        List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
        if (args.size() <= index) throw new Problem(at, "raw type " + type + "; add type arguments");
        TypeMirror arg = args.get(index);
        if (arg.getKind() == TypeKind.WILDCARD) throw new Problem(at, "wildcard type arguments are not supported: " + type);
        return arg;
    }

    String qualifiedName(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return type.toString();
        return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
    }
}
