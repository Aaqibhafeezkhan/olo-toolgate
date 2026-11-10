// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowAgentBinding; eligibility and configuration confer no runtime authority.
 *
 * @param agentId canonical agentId value
 * @param agentGroupId canonical agentGroupId value
 * @param entryEligible canonical entryEligible value
 * @param delegationAllowed canonical delegationAllowed value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowAgentBinding(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroupId", required = true) String agentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryEligible", required = true) Boolean entryEligible,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegationAllowed", required = true) Boolean delegationAllowed
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentId canonical agentId value
     * @param agentGroupId canonical agentGroupId value
     * @param entryEligible canonical entryEligible value
     * @param delegationAllowed canonical delegationAllowed value
     */
    public WorkflowAgentBinding {
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(agentGroupId, "agentGroupId");
        java.util.Objects.requireNonNull(entryEligible, "entryEligible");
        java.util.Objects.requireNonNull(delegationAllowed, "delegationAllowed");
    }
}
