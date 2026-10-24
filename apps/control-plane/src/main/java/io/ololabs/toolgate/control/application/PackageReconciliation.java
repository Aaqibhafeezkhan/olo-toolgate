// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The reconciliation method of a package tool (OLO M5.4), checked at deployment as the SDKs check it at build time:
 * a tool with side effects ({@code IDEMPOTENT}, {@code NON_IDEMPOTENT}) declares one, consistently with its other
 * declarations; a read-only tool declares none; a tool with an {@code UNKNOWN} effect that declares none is settled
 * {@code MANUAL}ly. A package that breaks these rules is incompatible.
 */
public final class PackageReconciliation {
    private PackageReconciliation() {}

    /** The method to record on the tool, or {@code null} for a read-only tool. */
    public static String of(JsonNode descriptor) {
        String safety = descriptor.path("effect").path("safety").asText("UNKNOWN");
        JsonNode declared = descriptor.path("reconciliation");
        boolean present = declared.isTextual();
        if ("READ_ONLY".equals(safety)) {
            if (present) throw PackageCompatibility.incompatible();
            return null;
        }
        if (!present) {
            if ("IDEMPOTENT".equals(safety) || "NON_IDEMPOTENT".equals(safety)) throw PackageCompatibility.incompatible();
            return "MANUAL";
        }
        String method = declared.asText();
        switch (method) {
            case "MANUAL" -> { }
            case "STATUS_LOOKUP" -> { if (!descriptor.path("reconcileTool").isTextual()) throw PackageCompatibility.incompatible(); }
            case "DOWNSTREAM_IDEMPOTENCY" -> {
                if (!"IDEMPOTENT".equals(safety) && !descriptor.path("businessKey").path("idempotencyHeader").isTextual())
                    throw PackageCompatibility.incompatible();
            }
            default -> throw PackageCompatibility.incompatible();
        }
        return method;
    }
}
