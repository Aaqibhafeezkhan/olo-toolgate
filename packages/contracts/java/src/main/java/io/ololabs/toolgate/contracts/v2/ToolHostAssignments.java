// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** What a registered Tool Host runs: its tools, and the deployments whose package, service profile and globals it fetches by digest.
 *
 * @param hostId canonical hostId value
 * @param tools canonical tools value
 * @param deployments canonical deployments value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolHostAssignments(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostId", required = true) String hostId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<ToolHostAssignment> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deployments", required = true) java.util.List<PackageDeployment> deployments
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param hostId canonical hostId value
     * @param tools canonical tools value
     * @param deployments canonical deployments value
     */
    public ToolHostAssignments {
        java.util.Objects.requireNonNull(hostId, "hostId");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        java.util.Objects.requireNonNull(deployments, "deployments");
        deployments = deployments == null ? null : java.util.List.copyOf(deployments);
    }
}
