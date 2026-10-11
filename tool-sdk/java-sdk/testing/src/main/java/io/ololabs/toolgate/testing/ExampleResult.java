// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

/** The outcome of one declared {@code @Example} (execution.md §5): null {@code problem} when it passed. */
public record ExampleResult(String tool, int index, String problem, ToolResult result) {
    public boolean passed() {
        return problem == null;
    }

    @Override
    public String toString() {
        return tool + " example " + (index + 1) + (passed() ? " passed" : ": " + problem);
    }
}
