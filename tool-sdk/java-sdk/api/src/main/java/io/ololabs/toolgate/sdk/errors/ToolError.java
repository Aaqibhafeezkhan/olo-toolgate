// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

/** Base of every error a handler raises (errors.md). */
public abstract class ToolError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    protected ToolError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
