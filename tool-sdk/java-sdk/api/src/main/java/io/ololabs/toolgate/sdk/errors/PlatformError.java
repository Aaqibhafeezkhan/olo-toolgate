// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

/** Raised by the SDK only. */
public final class PlatformError extends ToolError {
    private static final long serialVersionUID = 1L;

    /** The platform codes of errors.md §2. */
    public enum Code {
        CANCELLED, DEADLINE_EXCEEDED, ROUTE_REFUSED, AUDIT_UNAVAILABLE, CLAIMS_UNAVAILABLE,
        CLIENT_TOOLS_UNAVAILABLE, UNSUPPORTED_TIER, CONCURRENCY_LIMIT, FILES_REFUSED
    }

    private final Code platformCode;

    public PlatformError(Code code, String message) {
        super(code.name(), message, null);
        this.platformCode = code;
    }

    public Code platformCode() { return platformCode; }
}
