// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Response to a v2 POST /access/invocations for NEW and EXISTING reservations. IDEMPOTENCY_CONFLICT, IDEMPOTENCY_KEY_IN_USE and EXPIRED_KEY are 409 errors with that code and carry nothing else. requestState is present with an ASK outcome for an MRTR client.
 *
 * @param invocationId canonical invocationId value
 * @param state canonical state value
 * @param reservation canonical reservation value
 * @param task canonical task value
 * @param approvalId canonical approvalId value
 * @param approvalUrl canonical approvalUrl value
 * @param requestState canonical requestState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InvocationAdmission(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) TaskState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reservation", required = false) ReservationOutcome reservation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "task", required = false) InvocationTask task,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalId", required = false) String approvalId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalUrl", required = false) String approvalUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestState", required = false) String requestState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param state canonical state value
     * @param reservation canonical reservation value
     * @param task canonical task value
     * @param approvalId canonical approvalId value
     * @param approvalUrl canonical approvalUrl value
     * @param requestState canonical requestState value
     */
    public InvocationAdmission {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(state, "state");
    }
}
