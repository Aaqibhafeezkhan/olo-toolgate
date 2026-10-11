// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

/** The arguments are invalid; code INVALID_ARGUMENT. */
public final class ToolInputError extends ToolError {
    private static final long serialVersionUID = 1L;

    public ToolInputError(String message) { super("INVALID_ARGUMENT", message, null); }
}
