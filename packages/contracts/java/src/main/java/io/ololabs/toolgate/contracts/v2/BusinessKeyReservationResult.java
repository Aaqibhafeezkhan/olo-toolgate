// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Reservation result. IDEMPOTENCY_KEY_IN_USE carries nothing else.
 *
 * @param outcome canonical outcome value
 * @param invocationId canonical invocationId value
 * @param state canonical state value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BusinessKeyReservationResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) ReservationOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = false) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = false) TaskState state
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param outcome canonical outcome value
     * @param invocationId canonical invocationId value
     * @param state canonical state value
     */
    public BusinessKeyReservationResult {
        java.util.Objects.requireNonNull(outcome, "outcome");
    }
}
