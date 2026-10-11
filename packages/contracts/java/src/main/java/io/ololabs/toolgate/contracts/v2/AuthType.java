// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical AuthType wire values; unknown values must be rejected. */
public enum AuthType {
    /** Canonical API_KEY value. */
    API_KEY,
    /** Canonical BASIC value. */
    BASIC,
    /** Canonical OAUTH2_CLIENT_CREDENTIALS value. */
    OAUTH2_CLIENT_CREDENTIALS,
    /** Canonical TOKEN_EXCHANGE value. */
    TOKEN_EXCHANGE,
    /** Canonical CONNECTION value. */
    CONNECTION,
    /** Canonical CUSTOM value. */
    CUSTOM
}
