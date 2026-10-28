// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk;

/** Eligibility only: authentication, runtime capabilities and invocation permits remain mandatory. */
public enum ExecutionLocation {
    SERVER, CLIENT;

    public boolean allows(String kind) {
        return "SERVER".equals(kind) || this == CLIENT && "CLIENT".equals(kind);
    }
}
