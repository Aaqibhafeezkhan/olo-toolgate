// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Bounded delegate model proposal; never execution authority.
 *
 * @param kind canonical kind value
 * @param agentId canonical agentId value
 * @param inputFromAgent canonical inputFromAgent value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowContextDelegateDecision(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputFromAgent", required = true) String inputFromAgent
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param agentId canonical agentId value
     * @param inputFromAgent canonical inputFromAgent value
     */
    public WorkflowContextDelegateDecision {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "delegate")) throw new IllegalArgumentException("kind must be delegate");
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(inputFromAgent, "inputFromAgent");
    }
}
