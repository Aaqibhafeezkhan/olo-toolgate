// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

/** A refusal with an HTTP status and a machine code from api.md. */
public final class HelperException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public HelperException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
