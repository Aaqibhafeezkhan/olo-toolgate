// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every JAX-RS resource method must be reachable. JAX-RS (spec 3.7.2) selects the root resource class first, by the
 * most literal characters of its class {@code @Path}, and matches methods only inside that class. A method whose full
 * path also matches a root class with a longer literal path is unreachable and answers 404 (the device trigger routes
 * under {@code /api/control/v1} were shadowed by {@code /api/control/v1/endpoint} this way).
 */
class ResourceRoutingTest {
    private record Root(Class<?> type, String path, Pattern pattern, int literal, int groups) {}

    private static String normalize(String path) {
        String value = "/" + path.replaceAll("^/+|/+$", "");
        return value.equals("/") ? "" : value;
    }
    private static final Pattern TEMPLATE = Pattern.compile("\\{[^}]*}");

    private static Root root(Class<?> type) {
        String path = normalize(type.getAnnotation(Path.class).value());
        var regex = new StringBuilder();
        var matcher = TEMPLATE.matcher(path);
        int last = 0, groups = 0;
        while (matcher.find()) { regex.append(Pattern.quote(path.substring(last, matcher.start()))).append("[^/]+"); last = matcher.end(); groups++; }
        regex.append(Pattern.quote(path.substring(last))).append("(/.*)?");
        return new Root(type, path, Pattern.compile(regex.toString()), TEMPLATE.matcher(path).replaceAll("").length(), groups);
    }

    private static List<Class<?>> resources() throws Exception {
        var location = java.nio.file.Path.of(TriggerResource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var base = location.resolve("io/ololabs/toolgate/control");
        var found = new ArrayList<Class<?>>();
        try (var files = Files.walk(base)) {
            for (var file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = location.relativize(file).toString().replace('\\', '/').replaceAll("\\.class$", "").replace('/', '.');
                Class<?> type;
                try { type = Class.forName(name, false, ResourceRoutingTest.class.getClassLoader()); }
                catch (LinkageError unloadable) { continue; }
                if (type.isAnnotationPresent(Path.class)) found.add(type);
            }
        }
        return found;
    }

    @Test
    void noResourceMethodIsShadowedByAnotherRootClass() throws Exception {
        var roots = resources().stream().map(ResourceRoutingTest::root).toList();
        assertTrue(roots.stream().anyMatch(r -> r.type() == EndpointTriggerResource.class), "resource scan found the adapters");
        var shadowed = new ArrayList<String>();
        int checked = 0;
        for (var owner : roots) {
            for (var method : owner.type().getDeclaredMethods()) {
                var annotation = method.getAnnotation(Path.class);
                if (annotation == null) continue;
                String sample = TEMPLATE.matcher(owner.path() + normalize(annotation.value())).replaceAll("sample");
                var winner = roots.stream().filter(r -> r.pattern().matcher(sample).matches())
                    .max(Comparator.comparingInt(Root::literal).thenComparingInt(Root::groups)).orElseThrow();
                checked++;
                if (!winner.path().equals(owner.path()))
                    shadowed.add(owner.type().getSimpleName() + "." + method.getName() + " " + sample + " is routed to " + winner.type().getSimpleName());
            }
        }
        assertTrue(checked > 100, "resource methods checked: " + checked);
        assertEquals(List.of(), shadowed);
    }

    @Test
    void deviceTriggerRoutesResolveToTheirOwnResource() throws Exception {
        var roots = resources().stream().map(ResourceRoutingTest::root).toList();
        for (var path : List.of("/api/control/v1/endpoint/triggers/desired", "/api/control/v1/endpoint/triggers/descriptors/sha256:" + "0".repeat(64))) {
            var winner = roots.stream().filter(r -> r.pattern().matcher(path).matches())
                .max(Comparator.comparingInt(Root::literal).thenComparingInt(Root::groups)).orElseThrow();
            assertEquals(EndpointTriggerResource.class, winner.type(), path);
        }
    }
}
