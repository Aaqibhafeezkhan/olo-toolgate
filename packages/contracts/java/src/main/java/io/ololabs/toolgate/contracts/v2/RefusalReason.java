// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical RefusalReason wire values; unknown values must be rejected. */
public enum RefusalReason {
    /** Canonical BUSY value. */
    BUSY,
    /** Canonical DIGEST_NOT_PRESENT value. */
    DIGEST_NOT_PRESENT,
    /** Canonical STALE_SEQUENCE value. */
    STALE_SEQUENCE,
    /** Canonical PERMIT_INVALID value. */
    PERMIT_INVALID,
    /** Canonical PERMIT_EXPIRED value. */
    PERMIT_EXPIRED,
    /** Canonical STORE_UNAVAILABLE value. */
    STORE_UNAVAILABLE,
    /** Canonical AUDIT_UNAVAILABLE value. */
    AUDIT_UNAVAILABLE,
    /** Canonical UNSUPPORTED_TIER value. */
    UNSUPPORTED_TIER,
    /** Canonical KILLED value. */
    KILLED
}
