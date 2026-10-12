// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.util.Objects;

/** Assertions for {@link TestMain}. */
final class Check {
    private Check() {
    }

    static final class Skipped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Skipped(String message) {
            super(message);
        }
    }

    static void skip(String reason) {
        throw new Skipped(reason);
    }

    static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    interface Action {
        void run() throws Exception;
    }

    static <T extends Throwable> T fails(Class<T> type, Action action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static HelperException refused(String code, Action action) {
        HelperException e = fails(HelperException.class, action);
        equal(code, e.code());
        return e;
    }
}
