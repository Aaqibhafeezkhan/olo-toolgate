// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * A caller the identity verifier (adapter A4) accepted.
 *
 * @param issuer the token issuer
 * @param subject the token subject
 * @param claims every claim of the token or introspection answer, read-only by convention
 * @param expiresAt when the credential stops being valid
 */
public record VerifiedIdentity(String issuer, String subject, ObjectNode claims, Instant expiresAt) {
    /** The owner of the tasks and request states this caller creates. */
    public String owner() {
        return "identity:" + issuer + "|" + subject;
    }
}
