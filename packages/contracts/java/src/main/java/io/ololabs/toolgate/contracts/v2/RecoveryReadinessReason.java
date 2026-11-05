// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical RecoveryReadinessReason wire values; unknown values must be rejected. */
public enum RecoveryReadinessReason {
    /** Canonical ELIGIBLE value. */
    ELIGIBLE,
    /** Canonical DISABLED value. */
    DISABLED,
    /** Canonical MISSING_PROFILE value. */
    MISSING_PROFILE,
    /** Canonical PROFILE_CHANGED value. */
    PROFILE_CHANGED,
    /** Canonical NO_WRITE_ROUTES value. */
    NO_WRITE_ROUTES,
    /** Canonical UNREVIEWED_ROUTE value. */
    UNREVIEWED_ROUTE,
    /** Canonical MISSING_GUARANTEE value. */
    MISSING_GUARANTEE,
    /** Canonical REVIEW_EXPIRED value. */
    REVIEW_EXPIRED,
    /** Canonical INDEPENDENT_REVIEW_REQUIRED value. */
    INDEPENDENT_REVIEW_REQUIRED,
    /** Canonical UNSUPPORTED_CREDENTIAL value. */
    UNSUPPORTED_CREDENTIAL,
    /** Canonical UNQUALIFIED_EXECUTOR value. */
    UNQUALIFIED_EXECUTOR
}
