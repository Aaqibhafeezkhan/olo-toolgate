// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

/** Declared claims of the verified caller, plus the standard identity members. */
public interface Jwt extends Values {
    String getUserName();
    String getSubject();
    String getIssuer();
}
