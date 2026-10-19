// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Canonical ErrorCode wire values; unknown values must be rejected. */
public enum ErrorCode {
    /** Canonical VALIDATION value. */
    VALIDATION,
    /** Canonical UNAUTHORIZED value. */
    UNAUTHORIZED,
    /** Canonical FORBIDDEN value. */
    FORBIDDEN,
    /** Canonical NOT_FOUND value. */
    NOT_FOUND,
    /** Canonical CONFLICT value. */
    CONFLICT,
    /** Canonical DEPENDENCY_UNAVAILABLE value. */
    DEPENDENCY_UNAVAILABLE,
    /** Canonical TIMEOUT value. */
    TIMEOUT,
    /** Canonical INTERNAL value. */
    INTERNAL,
    /** Canonical UNSUPPORTED value. */
    UNSUPPORTED,
    /** Canonical TENANT_MISMATCH value. */
    TENANT_MISMATCH,
    /** Canonical TENANT_UNAVAILABLE value. */
    TENANT_UNAVAILABLE,
    /** Canonical MISDIRECTED_REQUEST value. */
    MISDIRECTED_REQUEST
}
