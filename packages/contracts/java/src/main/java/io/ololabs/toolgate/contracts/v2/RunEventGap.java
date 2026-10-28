// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Sequences of an attempt that never arrived; they are reported, never invented.
 *
 * @param attempt canonical attempt value
 * @param fromSequence canonical fromSequence value
 * @param toSequence canonical toSequence value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEventGap(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = true) Long attempt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromSequence", required = true) Long fromSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toSequence", required = true) Long toSequence
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param attempt canonical attempt value
     * @param fromSequence canonical fromSequence value
     * @param toSequence canonical toSequence value
     */
    public RunEventGap {
        java.util.Objects.requireNonNull(attempt, "attempt");
        java.util.Objects.requireNonNull(fromSequence, "fromSequence");
        java.util.Objects.requireNonNull(toSequence, "toSequence");
    }
}
