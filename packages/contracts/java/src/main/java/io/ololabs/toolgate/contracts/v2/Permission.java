// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical Permission wire values; unknown values must be rejected. */
public enum Permission {
    /** Canonical HTTP_ROUTE value. */
    HTTP_ROUTE,
    /** Canonical TCP value. */
    TCP,
    /** Canonical CREDENTIALS_IN_SANDBOX value. */
    CREDENTIALS_IN_SANDBOX,
    /** Canonical USER_SECRET value. */
    USER_SECRET,
    /** Canonical TOKEN_EXCHANGE value. */
    TOKEN_EXCHANGE,
    /** Canonical CONNECTION value. */
    CONNECTION,
    /** Canonical DEVICE_FILES_READ value. */
    DEVICE_FILES_READ,
    /** Canonical DEVICE_FILES_WRITE value. */
    DEVICE_FILES_WRITE
}
