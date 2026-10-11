// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Agent Group grant and catalog scope.
 *
 * @param agentGroupId canonical agentGroupId value
 * @param toolIds canonical toolIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeploymentGrant(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroupId", required = true) String agentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolIds", required = true) java.util.List<String> toolIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentGroupId canonical agentGroupId value
     * @param toolIds canonical toolIds value
     */
    public DeploymentGrant {
        java.util.Objects.requireNonNull(agentGroupId, "agentGroupId");
        java.util.Objects.requireNonNull(toolIds, "toolIds");
        toolIds = toolIds == null ? null : java.util.List.copyOf(toolIds);
    }
}
