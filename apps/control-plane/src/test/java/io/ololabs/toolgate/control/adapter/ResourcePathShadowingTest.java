// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JAX-RS first selects the resource class with the most specific matching class-level path and then searches only its
 * methods. A method whose full path also falls under another class's more specific class-level path is unreachable
 * and answers 404, as {@code endpoint/triggers/desired} once did under {@link EndpointResource}.
 */
class ResourcePathShadowingTest {
    private record Resource(Class<?> type, String path) {}

    @Test void everyResourceMethodIsReachableThroughItsOwnClass() throws Exception {
        var resources = resources();
        assertTrue(resources.stream().anyMatch(r -> r.type() == EndpointTriggerResource.class), "resource scan found no classes");
        var shadowed = new ArrayList<String>();
        for (var owner : resources) {
            for (Method method : owner.type().getDeclaredMethods()) {
                var annotation = method.getAnnotation(Path.class);
                if (annotation == null) continue;
                var full = join(owner.path(), annotation.value());
                for (var other : resources) {
                    if (other.type() != owner.type() && literal(other.path()) > literal(owner.path()) && prefix(other.path(), full))
                        shadowed.add(owner.type().getSimpleName() + "." + method.getName() + " " + full + " is shadowed by "
                            + other.type().getSimpleName() + " " + other.path());
                }
            }
        }
        assertEquals(List.of(), shadowed);
    }

    @Test void theDeviceTriggerRoutesHaveTheirOwnMostSpecificClass() {
        assertTrue(prefix("/api/control/v1/endpoint", "/api/control/v1/endpoint/triggers/desired"));
        assertFalse(prefix("/api/control/v1/endpoint", "/api/control/v1/endpoints/kills"));
        assertTrue(prefix("/api/control/v1/{tenant}", "/api/control/v1/endpoint"));
        assertTrue(literal("/api/control/v1/endpoint/triggers") > literal("/api/control/v1/endpoint"));
    }

    private static List<Resource> resources() throws Exception {
        var directory = java.nio.file.Path.of(EndpointResource.class.getResource("EndpointResource.class").toURI()).getParent();
        var found = new ArrayList<Resource>();
        try (Stream<java.nio.file.Path> files = Files.list(directory)) {
            for (var file : files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".class") && !n.contains("$")).sorted().toList()) {
                var type = Class.forName(EndpointResource.class.getPackageName() + "." + file.substring(0, file.length() - 6), false,
                    ResourcePathShadowingTest.class.getClassLoader());
                var path = type.getAnnotation(Path.class);
                if (path != null) found.add(new Resource(type, normalize(path.value())));
            }
        }
        return found;
    }

    private static String normalize(String path) { return "/" + String.join("/", segments(path)); }
    private static String join(String base, String path) { return normalize(base + "/" + path); }
    private static List<String> segments(String path) { return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList(); }
    private static int literal(String path) { return path.replaceAll("\\{[^}]*}", "").length(); }

    /** Whether every segment of {@code base} matches the leading segments of {@code path}; templates match any segment. */
    private static boolean prefix(String base, String path) {
        var left = segments(base); var right = segments(path);
        if (left.size() > right.size()) return false;
        for (int i = 0; i < left.size(); i++)
            if (!left.get(i).startsWith("{") && !left.get(i).equals(right.get(i))) return false;
        return true;
    }
}
