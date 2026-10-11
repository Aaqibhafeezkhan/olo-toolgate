// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical McpTaskStatus wire values; unknown values must be rejected. */
public enum McpTaskStatus {
    /** Canonical working value. */
    working,
    /** Canonical input_required value. */
    input_required,
    /** Canonical completed value. */
    completed,
    /** Canonical failed value. */
    failed,
    /** Canonical cancelled value. */
    cancelled
}
