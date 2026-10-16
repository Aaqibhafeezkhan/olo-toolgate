// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical FrameKind wire values; unknown values must be rejected. */
public enum FrameKind {
    /** Canonical RESULT value. */
    RESULT,
    /** Canonical PROGRESS value. */
    PROGRESS,
    /** Canonical INPUT_REQUEST value. */
    INPUT_REQUEST,
    /** Canonical LOG value. */
    LOG,
    /** Canonical BROKER_REQUEST value. */
    BROKER_REQUEST,
    /** Canonical BROKER_RESPONSE value. */
    BROKER_RESPONSE
}
