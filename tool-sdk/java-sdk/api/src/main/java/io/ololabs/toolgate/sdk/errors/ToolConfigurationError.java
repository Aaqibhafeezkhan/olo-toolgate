// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

/** A declaration or configuration problem; code CONFIGURATION. */
public final class ToolConfigurationError extends ToolError {
    private static final long serialVersionUID = 1L;

    public ToolConfigurationError(String message) { super("CONFIGURATION", message, null); }
}
