// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

/** The runtime refuses to start, with a fixed code (runtime-modes.md §3, item 3). */
public final class StartupRefused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    StartupRefused(String code, String message) {
        super(code + ": " + message, null, false, false);
        this.code = code;
    }

    public String code() { return code; }
}
