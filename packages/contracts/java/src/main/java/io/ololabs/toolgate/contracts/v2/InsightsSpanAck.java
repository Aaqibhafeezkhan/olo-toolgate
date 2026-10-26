// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** How many spans Control buffered and how many it refused (unknown invocation, wrong executor, over a bound).
 *
 * @param accepted canonical accepted value
 * @param refused canonical refused value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSpanAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "accepted", required = true) Long accepted,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "refused", required = true) Long refused
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param accepted canonical accepted value
     * @param refused canonical refused value
     */
    public InsightsSpanAck {
        java.util.Objects.requireNonNull(accepted, "accepted");
        java.util.Objects.requireNonNull(refused, "refused");
    }
}
