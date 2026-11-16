// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Ordered configured agent stage; identifiers and modes are data, never executable expressions.
 *
 * @param agentId canonical agentId value
 * @param resultMode canonical resultMode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowAgentStage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultMode", required = true) WorkflowAgentResultMode resultMode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentId canonical agentId value
     * @param resultMode canonical resultMode value
     */
    public WorkflowAgentStage {
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(resultMode, "resultMode");
    }
}
