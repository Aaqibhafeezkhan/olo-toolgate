// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

/** MCP task statuses (plan.md §12.4). */
public enum TaskStatus {
    WORKING("working"), INPUT_REQUIRED("input_required"), COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    private final String wire;

    TaskStatus(String wire) {
        this.wire = wire;
    }

    public String wire() { return wire; }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
