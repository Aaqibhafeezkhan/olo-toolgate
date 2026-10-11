// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Tenant-scoped Agent membership and compatible actor/management role assignments.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param slug canonical slug value
 * @param enabled canonical enabled value
 * @param revision canonical revision value
 * @param agentIds canonical agentIds value
 * @param roleIds canonical roleIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlAgentGroup(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = false) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentIds", required = true) java.util.List<String> agentIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "roleIds", required = true) java.util.List<String> roleIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param slug canonical slug value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     * @param agentIds canonical agentIds value
     * @param roleIds canonical roleIds value
     */
    public ControlAgentGroup {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(agentIds, "agentIds");
        agentIds = agentIds == null ? null : java.util.List.copyOf(agentIds);
        java.util.Objects.requireNonNull(roleIds, "roleIds");
        roleIds = roleIds == null ? null : java.util.List.copyOf(roleIds);
    }
}
