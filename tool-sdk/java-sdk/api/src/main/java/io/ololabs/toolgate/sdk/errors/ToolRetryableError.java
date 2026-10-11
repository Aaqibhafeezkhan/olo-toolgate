// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

import java.time.Duration;
import java.util.Optional;

/** A transient failure with a declared code; retryable only for READ_ONLY and IDEMPOTENT tools. */
public class ToolRetryableError extends ToolError {
    private static final long serialVersionUID = 1L;
    private final Duration retryAfter;

    public ToolRetryableError(String code, String message) { this(code, message, null); }

    public ToolRetryableError(String code, String message, Duration retryAfter) {
        super(code, message, null);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
}
