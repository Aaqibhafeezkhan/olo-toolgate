// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** v2 body of POST /access/invocations/reserve: one permit for one attempt, addressed to one Tool Host or device (invocation-state-v2.md T4).
 *
 * @param invocationId canonical invocationId value
 * @param audience canonical audience value
 * @param attempt canonical attempt value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PermitReservationRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "audience", required = true) String audience,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = true) Long attempt
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param audience canonical audience value
     * @param attempt canonical attempt value
     */
    public PermitReservationRequest {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(audience, "audience");
        java.util.Objects.requireNonNull(attempt, "attempt");
    }
}
