// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Durable acceptance receipt; it is not evidence of execution success.
 *
 * @param runId canonical runId value
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param workflowId canonical workflowId value
 * @param state canonical state value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunStartReceipt(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runId", required = true) String runId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) String state
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param runId canonical runId value
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param workflowId canonical workflowId value
     * @param state canonical state value
     */
    public WorkflowRunStartReceipt {
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(state, "state");
        if (state != null && !java.util.Objects.equals(state, "QUEUED")) throw new IllegalArgumentException("state must be QUEUED");
    }
}
