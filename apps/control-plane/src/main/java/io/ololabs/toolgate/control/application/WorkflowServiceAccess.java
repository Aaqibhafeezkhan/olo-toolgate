// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;

/** Checks explicit service grants for workflow and model-group admission. */
final class WorkflowServiceAccess {
    private final Codec codec;

    WorkflowServiceAccess(Codec codec) { this.codec = codec; }

    void require(Directory directory, WorkflowProfiles.DispatchTarget target, long now) {
        require(directory, target.binding().serviceAgentId(), target.binding().serviceAgentGroupId(), target.workflow(), now);
    }

    void require(Directory directory, String agentId, String groupId,
                 io.ololabs.toolgate.contracts.v2.WorkflowDefinition workflow, long now) {
        var group = directory.entries().get(Kind.AGENT_GROUP.id(groupId));
        var agent = directory.entries().get(Kind.AGENT.id(agentId));
        if (agent == null || !agent.enabled() || group == null || !group.enabled()
                || !GroupGraph.memberships(directory, codec, Kind.AGENT, agentId, true).contains(groupId))
            throw ManagementAccess.denied();
        var scopes = scopes(directory, groupId, now);
        require(scopes, "workflow.execute", "workflow-group:" + workflow.workflowGroupId());
        require(scopes, "model.infer", "model-group:" + workflow.modelGroupId());
    }

    void requireGrant(Directory directory, String groupId, String action, String locator, long now) {
        require(scopes(directory, groupId, now), action, locator);
    }

    io.ololabs.toolgate.contracts.v2.WorkflowAgentBinding agent(Directory directory,
            io.ololabs.toolgate.contracts.v2.WorkflowDefinition workflow, String agentId, long now) {
        var binding = workflow.agents().stream().filter(item -> item.agentId().equals(agentId)
            && (item.entryEligible() || item.delegationAllowed())).findFirst().orElseThrow(ManagementAccess::denied);
        require(directory, agentId, binding.agentGroupId(), workflow, now);
        return binding;
    }

    private java.util.List<EnterpriseScope> scopes(Directory directory, String groupId, long now) {
        var group = directory.entries().get(Kind.AGENT_GROUP.id(groupId));
        if (group == null || !group.enabled()) throw ManagementAccess.denied();
        var roles = GroupGraph.roles(group, codec);
        return directory.entries().values().stream().filter(entry -> entry.enabled() && entry.id().kind() == Kind.GRANT)
                .map(entry -> codec.model(entry.document(), ControlAccessGrant.class))
                .filter(grant -> grant.purpose() == EnterpriseGrantPurpose.SERVICE && grant.sourceType() == EnterpriseSourceType.ROLE)
                .filter(grant -> roles.contains(grant.sourceId()) && activeRole(directory, grant.sourceId()))
                .map(ControlAccessGrant::scope).filter(scope -> unconditionalTarget(scope, now)).toList();
    }

    private static void require(java.util.List<EnterpriseScope> scopes, String action, String locator) {
        if (scopes.stream().noneMatch(scope -> matches(scope, action, locator))) throw ManagementAccess.denied();
    }

    private boolean activeRole(Directory directory, String id) {
        var entry = directory.entries().get(Kind.ROLE.id(id));
        return entry != null && entry.enabled() && codec.model(entry.document(), ControlRole.class).roleType() == EnterpriseRoleType.ACTOR_SERVICE;
    }

    private static boolean unconditionalTarget(EnterpriseScope scope, long now) {
        // Admission has no tool/device target or evidence for online and high-risk conditions.
        return scope.toolGroups().all() && scope.deviceGroups().all() && !scope.conditions().requireOnline()
            && !scope.conditions().highRisk() && ManagementAccess.current(scope.conditions(), now);
    }

    private static boolean matches(EnterpriseScope scope, String action, String locator) {
        return scope.actions().contains(action) && scope.resources().stream().anyMatch(rule -> rule.kind() == ResourceKind.CUSTOM
            && rule.match() == EnterpriseResourceMatch.EXACT && rule.locator().equals(locator));
    }
}
