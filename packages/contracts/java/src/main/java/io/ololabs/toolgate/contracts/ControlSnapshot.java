// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Enterprise group graph. Individual ACLs and retired snapshot formats are rejected.
 *
 * @param formatVersion canonical formatVersion value
 * @param tenantId canonical tenantId value
 * @param revision canonical revision value
 * @param users canonical users value
 * @param teams canonical teams value
 * @param agents canonical agents value
 * @param tools canonical tools value
 * @param policies canonical policies value
 * @param devices canonical devices value
 * @param roles canonical roles value
 * @param deviceGroups canonical deviceGroups value
 * @param agentGroups canonical agentGroups value
 * @param toolGroups canonical toolGroups value
 * @param grants canonical grants value
 * @param delegations canonical delegations value
 * @param agentDelegations canonical agentDelegations value
 * @param bindings canonical bindings value
 * @param extractors canonical extractors value
 * @param workloadBindings canonical workloadBindings value
 * @param identityBindings canonical identityBindings value
 * @param deviceEvidence canonical deviceEvidence value
 * @param settings canonical settings value
 * @param requiredIdentities canonical requiredIdentities value
 * @param visitorPool canonical visitorPool value
 * @param reservedRecords canonical reservedRecords value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlSnapshot(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "formatVersion", required = true) Long formatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "users", required = true) java.util.List<ControlUser> users,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "teams", required = true) java.util.List<ControlTeam> teams,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agents", required = true) java.util.List<ControlAgent> agents,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<ControlTool> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "policies", required = true) java.util.List<ControlPolicy> policies,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "devices", required = true) java.util.List<ControlDevice> devices,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "roles", required = true) java.util.List<ControlRole> roles,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroups", required = true) java.util.List<ControlDeviceGroup> deviceGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroups", required = true) java.util.List<ControlAgentGroup> agentGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroups", required = true) java.util.List<ControlToolGroup> toolGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grants", required = true) java.util.List<ControlAccessGrant> grants,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegations", required = true) java.util.List<ControlDelegation> delegations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentDelegations", required = true) java.util.List<ControlAgentDelegation> agentDelegations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindings", required = true) java.util.List<ControlExecutionBinding> bindings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "extractors", required = true) java.util.List<ControlResourceExtractor> extractors,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workloadBindings", required = true) java.util.List<ControlWorkloadBinding> workloadBindings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "identityBindings", required = true) java.util.List<ControlIdentityBinding> identityBindings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceEvidence", required = true) java.util.List<ControlDeviceEvidence> deviceEvidence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "settings", required = false) io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch settings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requiredIdentities", required = false) java.util.List<ControlRequiredIdentity> requiredIdentities,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "visitorPool", required = false) ControlVisitorPool visitorPool,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reservedRecords", required = false) Long reservedRecords
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param formatVersion canonical formatVersion value
     * @param tenantId canonical tenantId value
     * @param revision canonical revision value
     * @param users canonical users value
     * @param teams canonical teams value
     * @param agents canonical agents value
     * @param tools canonical tools value
     * @param policies canonical policies value
     * @param devices canonical devices value
     * @param roles canonical roles value
     * @param deviceGroups canonical deviceGroups value
     * @param agentGroups canonical agentGroups value
     * @param toolGroups canonical toolGroups value
     * @param grants canonical grants value
     * @param delegations canonical delegations value
     * @param agentDelegations canonical agentDelegations value
     * @param bindings canonical bindings value
     * @param extractors canonical extractors value
     * @param workloadBindings canonical workloadBindings value
     * @param identityBindings canonical identityBindings value
     * @param deviceEvidence canonical deviceEvidence value
     * @param settings canonical settings value
     * @param requiredIdentities canonical requiredIdentities value
     * @param visitorPool canonical visitorPool value
     * @param reservedRecords canonical reservedRecords value
     */
    public ControlSnapshot {
        java.util.Objects.requireNonNull(formatVersion, "formatVersion");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(users, "users");
        users = users == null ? null : java.util.List.copyOf(users);
        java.util.Objects.requireNonNull(teams, "teams");
        teams = teams == null ? null : java.util.List.copyOf(teams);
        java.util.Objects.requireNonNull(agents, "agents");
        agents = agents == null ? null : java.util.List.copyOf(agents);
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        java.util.Objects.requireNonNull(policies, "policies");
        policies = policies == null ? null : java.util.List.copyOf(policies);
        java.util.Objects.requireNonNull(devices, "devices");
        devices = devices == null ? null : java.util.List.copyOf(devices);
        java.util.Objects.requireNonNull(roles, "roles");
        roles = roles == null ? null : java.util.List.copyOf(roles);
        java.util.Objects.requireNonNull(deviceGroups, "deviceGroups");
        deviceGroups = deviceGroups == null ? null : java.util.List.copyOf(deviceGroups);
        java.util.Objects.requireNonNull(agentGroups, "agentGroups");
        agentGroups = agentGroups == null ? null : java.util.List.copyOf(agentGroups);
        java.util.Objects.requireNonNull(toolGroups, "toolGroups");
        toolGroups = toolGroups == null ? null : java.util.List.copyOf(toolGroups);
        java.util.Objects.requireNonNull(grants, "grants");
        grants = grants == null ? null : java.util.List.copyOf(grants);
        java.util.Objects.requireNonNull(delegations, "delegations");
        delegations = delegations == null ? null : java.util.List.copyOf(delegations);
        java.util.Objects.requireNonNull(agentDelegations, "agentDelegations");
        agentDelegations = agentDelegations == null ? null : java.util.List.copyOf(agentDelegations);
        java.util.Objects.requireNonNull(bindings, "bindings");
        bindings = bindings == null ? null : java.util.List.copyOf(bindings);
        java.util.Objects.requireNonNull(extractors, "extractors");
        extractors = extractors == null ? null : java.util.List.copyOf(extractors);
        java.util.Objects.requireNonNull(workloadBindings, "workloadBindings");
        workloadBindings = workloadBindings == null ? null : java.util.List.copyOf(workloadBindings);
        java.util.Objects.requireNonNull(identityBindings, "identityBindings");
        identityBindings = identityBindings == null ? null : java.util.List.copyOf(identityBindings);
        java.util.Objects.requireNonNull(deviceEvidence, "deviceEvidence");
        deviceEvidence = deviceEvidence == null ? null : java.util.List.copyOf(deviceEvidence);
        requiredIdentities = requiredIdentities == null ? null : java.util.List.copyOf(requiredIdentities);
    }
}
