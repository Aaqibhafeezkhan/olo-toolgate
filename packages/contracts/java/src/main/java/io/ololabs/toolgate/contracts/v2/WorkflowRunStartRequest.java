// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit text input for one published bot. Backend derives service group and configured mode; current authority and idempotency remain required.
 *
 * @param profileDigest canonical profileDigest value
 * @param expectedGeneration canonical expectedGeneration value
 * @param entryAgentId canonical entryAgentId value
 * @param message canonical message value
 * @param dispatchBindingId canonical dispatchBindingId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunStartRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedGeneration", required = true) Long expectedGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryAgentId", required = true) String entryAgentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = true) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dispatchBindingId", required = false) String dispatchBindingId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileDigest canonical profileDigest value
     * @param expectedGeneration canonical expectedGeneration value
     * @param entryAgentId canonical entryAgentId value
     * @param message canonical message value
     * @param dispatchBindingId canonical dispatchBindingId value
     */
    public WorkflowRunStartRequest {
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        java.util.Objects.requireNonNull(entryAgentId, "entryAgentId");
        java.util.Objects.requireNonNull(message, "message");
    }
}
