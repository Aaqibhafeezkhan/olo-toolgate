// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical McpCheckFailure wire values; unknown values must be rejected. */
public enum McpCheckFailure {
    /** Canonical PIN_MISMATCH value. */
    PIN_MISMATCH,
    /** Canonical TLS_ERROR value. */
    TLS_ERROR,
    /** Canonical UPSTREAM_UNREACHABLE value. */
    UPSTREAM_UNREACHABLE,
    /** Canonical UPSTREAM_ERROR value. */
    UPSTREAM_ERROR,
    /** Canonical PROTOCOL_ERROR value. */
    PROTOCOL_ERROR,
    /** Canonical SCAN_LIMIT_EXCEEDED value. */
    SCAN_LIMIT_EXCEEDED,
    /** Canonical DEADLINE_EXCEEDED value. */
    DEADLINE_EXCEEDED,
    /** Canonical DEFINITION_CHANGED value. */
    DEFINITION_CHANGED,
    /** Canonical TOOL_MISSING value. */
    TOOL_MISSING,
    /** Canonical SERVER_CHANGED value. */
    SERVER_CHANGED
}
