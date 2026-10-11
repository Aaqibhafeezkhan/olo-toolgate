// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Heartbeat response: the renewed lease, whether cancellation was requested, and any task input waiting for the tool (invocation-state-v2.md T6–T8).
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
 * @param cancelRequested canonical cancelRequested value
 * @param inputResponses canonical inputResponses value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LeaseRenewal(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseExpiresAtUnixMs", required = true) Long leaseExpiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cancelRequested", required = true) Boolean cancelRequested,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputResponses", required = false) com.fasterxml.jackson.databind.JsonNode inputResponses
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
     * @param cancelRequested canonical cancelRequested value
     * @param inputResponses canonical inputResponses value
     */
    public LeaseRenewal {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(leaseExpiresAtUnixMs, "leaseExpiresAtUnixMs");
        java.util.Objects.requireNonNull(cancelRequested, "cancelRequested");
    }
}
