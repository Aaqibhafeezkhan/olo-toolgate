// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control's acknowledgement of one admission batch.
 *
 * @param sequence canonical sequence value
 * @param duplicate canonical duplicate value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsAdmissionAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "duplicate", required = true) Boolean duplicate
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param duplicate canonical duplicate value
     */
    public InsightsAdmissionAck {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(duplicate, "duplicate");
    }
}
