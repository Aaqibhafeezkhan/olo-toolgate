// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control acknowledgement: the highest contiguous sequence stored for the attempt, and how many frames were already present or refused by retention bounds.
 *
 * @param invocationId canonical invocationId value
 * @param attempt canonical attempt value
 * @param acceptedThrough canonical acceptedThrough value
 * @param duplicates canonical duplicates value
 * @param refused canonical refused value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEventAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = true) Long attempt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acceptedThrough", required = true) Long acceptedThrough,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "duplicates", required = true) Long duplicates,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "refused", required = true) Long refused
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param attempt canonical attempt value
     * @param acceptedThrough canonical acceptedThrough value
     * @param duplicates canonical duplicates value
     * @param refused canonical refused value
     */
    public RunEventAck {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(attempt, "attempt");
        java.util.Objects.requireNonNull(acceptedThrough, "acceptedThrough");
        java.util.Objects.requireNonNull(duplicates, "duplicates");
        java.util.Objects.requireNonNull(refused, "refused");
    }
}
