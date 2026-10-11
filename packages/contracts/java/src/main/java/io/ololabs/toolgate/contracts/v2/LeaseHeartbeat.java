// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Lease renewal for a running ledger invocation (every lease/3). Also carries the latest progress and, while the tool waits for input, its input request (invocation-state-v2.md T6, T7).
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param progress canonical progress value
 * @param inputRequest canonical inputRequest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LeaseHeartbeat(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "progress", required = false) TaskProgress progress,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputRequest", required = false) InputRequestFrame inputRequest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param progress canonical progress value
     * @param inputRequest canonical inputRequest value
     */
    public LeaseHeartbeat {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
    }
}
