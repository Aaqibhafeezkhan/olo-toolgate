// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A dispatch the Gateway handed to Control for a Tool Host that fetches its calls (pull mode), with the host's admission and result once it has sent them.
 *
 * @param invocationId canonical invocationId value
 * @param hostId canonical hostId value
 * @param state canonical state value
 * @param admission canonical admission value
 * @param result canonical result value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolHostDispatchStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostId", required = true) String hostId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) ToolHostDispatchState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "admission", required = false) AdmissionResponse admission,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) ResultMessage result
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param hostId canonical hostId value
     * @param state canonical state value
     * @param admission canonical admission value
     * @param result canonical result value
     */
    public ToolHostDispatchStatus {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(hostId, "hostId");
        java.util.Objects.requireNonNull(state, "state");
    }
}
