// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Canonical ServicePermitClaimState wire values; unknown values must be rejected. */
public enum ServicePermitClaimState {
    /** Canonical CONSUMED value. */
    CONSUMED,
    /** Canonical CONSUMED_BY_OTHER_CLAIM value. */
    CONSUMED_BY_OTHER_CLAIM,
    /** Canonical COMPLETED value. */
    COMPLETED,
    /** Canonical FAILED value. */
    FAILED,
    /** Canonical AMBIGUOUS value. */
    AMBIGUOUS,
    /** Canonical CONFLICT value. */
    CONFLICT
}
