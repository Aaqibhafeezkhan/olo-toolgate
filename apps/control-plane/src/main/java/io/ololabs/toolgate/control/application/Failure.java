// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.ErrorCode;

/** Stable application failure. Details deliberately exclude database and request payloads. */
public final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    private final int status;
    private final io.ololabs.toolgate.contracts.ControlErrorDetailCode detail;
    private final transient java.util.List<String> ids;
    public Failure(ErrorCode code, int status, String message) {
        this(code, status, message, null, java.util.List.of());
    }
    private Failure(ErrorCode code, int status, String message, io.ololabs.toolgate.contracts.ControlErrorDetailCode detail, java.util.List<String> ids) {
        super(message); this.code = code; this.status = status; this.detail = detail; this.ids = java.util.List.copyOf(ids);
    }
    /** Refusal whose body names what the caller must resolve, as IDs only. */
    public static Failure detail(int status, io.ololabs.toolgate.contracts.ControlErrorDetailCode detail, java.util.Collection<String> ids) {
        return new Failure(status == 429 ? ErrorCode.CONFLICT : status == 403 ? ErrorCode.FORBIDDEN : ErrorCode.CONFLICT, status, detail.name(), detail, java.util.List.copyOf(new java.util.TreeSet<>(ids)));
    }
    public io.ololabs.toolgate.contracts.ControlErrorDetailCode detail() { return detail; }
    public java.util.List<String> ids() { return ids; }
    public ErrorCode code() { return code; }
    public int status() { return status; }
    public static Failure validation() { return new Failure(ErrorCode.VALIDATION, 400, "Invalid request"); }
    public static Failure conflict() { return new Failure(ErrorCode.CONFLICT, 409, "Revision or configuration conflict"); }
    public static Failure unavailable() { return new Failure(ErrorCode.DEPENDENCY_UNAVAILABLE, 503, "Persistence unavailable"); }
}
