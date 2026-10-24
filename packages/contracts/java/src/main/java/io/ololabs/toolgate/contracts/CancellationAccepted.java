// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Durable caller-scoped cancellation acknowledgement.
 *
 * @param cancelled canonical cancelled value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CancellationAccepted(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cancelled", required = true) Boolean cancelled
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param cancelled canonical cancelled value
     */
    public CancellationAccepted {
        java.util.Objects.requireNonNull(cancelled, "cancelled");
    }
}
