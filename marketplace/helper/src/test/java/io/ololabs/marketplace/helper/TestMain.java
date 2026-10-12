// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal test runner, so the helper's build needs no test framework from a repository. Runs every
 * {@code public static void test*()} method of the listed classes and exits non-zero on any failure.
 */
public final class TestMain {
    private static final List<Class<?>> SUITES = List.of(
            JsonTest.class,
            SemVerTest.class,
            ApiDiffTest.class,
            SignerTest.class,
            FileStoreTest.class,
            ValidationTest.class,
            ServerTest.class,
            SeedTest.class);

    private TestMain() {
    }

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        int passed = 0;
        int skipped = 0;
        for (Class<?> suite : SUITES) {
            for (Method method : suite.getDeclaredMethods()) {
                if (!method.getName().startsWith("test") || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) {
                    continue;
                }
                String name = suite.getSimpleName() + "." + method.getName();
                try {
                    method.invoke(null);
                    passed++;
                    System.out.println("PASS " + name);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Check.Skipped skip) {
                        skipped++;
                        System.out.println("SKIP " + name + ": " + skip.getMessage());
                        continue;
                    }
                    failures.add(name);
                    System.out.println("FAIL " + name + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println("helper tests: " + passed + " passed, " + failures.size() + " failed, " + skipped + " skipped");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
