// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.util.*;

/** Human admission is an additional ceiling; it never replaces service or per-effect authority. */
public final class WorkflowUserAccess {
    private final Codec codec;
    public WorkflowUserAccess(Codec codec) { this.codec = codec; }

    public WorkflowUserAuthority capture(Directory directory, DirectoryService.Actor actor,
                                        WorkflowConfiguration config, WorkflowDefinition workflow, long now) {
        if (actor.userId() == null) throw ManagementAccess.denied();
        var human = scopes(directory, actor.userId(), EnterpriseGrantPurpose.HUMAN, now);
        var secrets = scopes(directory, actor.userId(), EnterpriseGrantPurpose.SECRET, now);
        var authority = new WorkflowUserAuthority(actor.userId(), actor.id(), identities(directory, actor.userId()),
            human, secrets, agentScopes(directory, workflow, false), agentScopes(directory, workflow, true), requirements(config, workflow));
        require(directory, authority, config, workflow, "workflow.execute", now);
        return authority;
    }

    public void require(Directory directory, WorkflowUserAuthority authority, WorkflowConfiguration config,
                        WorkflowDefinition workflow, String action, long now) {
        new ManagementAccess(codec).access(directory, authority.userId());
        if (!identities(directory, authority.userId()).equals(authority.identityDigest())) throw ManagementAccess.denied();
        var current = scopes(directory, authority.userId(), EnterpriseGrantPurpose.HUMAN, now);
        var secrets = scopes(directory, authority.userId(), EnterpriseGrantPurpose.SECRET, now);
        exact(current, action, "workflow:" + workflow.id());
        exact(live(authority.humanScopes(), now), action, "workflow:" + workflow.id());
        exact(current, "model.infer", "model-group:" + workflow.modelGroupId());
        exact(live(authority.humanScopes(), now), "model.infer", "model-group:" + workflow.modelGroupId());
        for (var agent : workflow.agents()) {
            new WorkflowServiceAccess(codec).require(directory,agent.agentId(),agent.agentGroupId(),workflow,now);
            exact(current, "agent.execute", "agent:" + agent.agentId());
            exact(live(authority.humanScopes(), now), "agent.execute", "agent:" + agent.agentId());
            var definition = config.agentDefinitions().stream().filter(a -> a.agentId().equals(agent.agentId()))
                .findFirst().orElseThrow(ManagementAccess::denied);
            exact(current, "model.infer", "model-group:" + definition.modelGroupId());
            exact(live(authority.humanScopes(), now), "model.infer", "model-group:" + definition.modelGroupId());
            var entry = directory.entries().get(Kind.AGENT.id(agent.agentId()));
            var group = directory.entries().get(Kind.AGENT_GROUP.id(agent.agentGroupId()));
            if (entry == null || !entry.enabled() || group == null || !group.enabled()
                    || !GroupGraph.memberships(directory, codec, Kind.AGENT, agent.agentId(), true).contains(agent.agentGroupId()))
                throw ManagementAccess.denied();
        }
        // Native graphs choose tools dynamically. Cover all declared agent scopes, not just named context tools.
        for (var scope : agentScopes(directory, workflow, false)) {
            cover(authority.agentScopes(), scope);
            cover(live(authority.humanScopes(), now), scope);
            cover(current, scope);
        }
        for (var scope : agentScopes(directory, workflow, true)) {
            cover(authority.agentSecretScopes(), scope);
            cover(live(authority.secretScopes(), now), scope);
            cover(secrets, scope);
        }
        policies(directory,authority,workflow.id(),action,now);
    }
    private void policies(Directory directory,WorkflowUserAuthority authority,String workflow,String action,long now) {
        var teams = GroupGraph.memberships(directory, codec, Kind.USER, authority.userId(), true);
        var required=new ArrayList<>(authority.requiredScopes());required.add(requirement(action,"workflow:"+workflow));
        for (var entry : directory.entries().values()) if (entry.enabled() && entry.id().kind() == Kind.POLICY) {
            var policy = codec.model(entry.document(), ControlPolicy.class);
            // Human-only policies have no service-mode equivalent. Refuse overlapping ASK/BLOCK;
            // this endpoint cannot turn a human approval obligation into service authority.
            var selected=new ArrayList<>(required);
            if(!policy.teams().all())selected.addAll(authority.agentScopes());
            if ((policy.teams().all()||EnterpriseEvaluator.intersects(policy.teams(), teams))
                    &&Set.of(Decision.ASK,Decision.BLOCK).contains(policy.decision())
                    &&now>=policy.scope().conditions().notBeforeUnixMs()
                    && (policy.scope().conditions().expiresAtUnixMs() == 0 || now < policy.scope().conditions().expiresAtUnixMs())
                    && selected.stream().anyMatch(s -> overlap(s, policy.scope()))) throw ManagementAccess.denied();
        }
    }

    public void requireRun(Directory directory, WorkflowRunStore.QueuedRun run, WorkflowConfiguration config,
                           WorkflowDefinition workflow, long now) {
        if (run.userAuthority() != null)
            require(directory, codec.model(run.userAuthority(), WorkflowUserAuthority.class), config, workflow, "workflow.execute", now);
    }

    public void observe(Directory directory, DirectoryService.Actor actor, WorkflowRunStore.QueuedRun run, String action, long now) {
        if (new ManagementAccess(codec).superAdministrator(directory, actor.userId())) return;
        if (run.userAuthority() == null) throw ManagementAccess.denied();
        var authority = codec.model(run.userAuthority(), WorkflowUserAuthority.class);
        if (actor.userId() == null || !actor.userId().equals(authority.userId()) || !actor.id().equals(authority.principalId()))
            throw ManagementAccess.denied();
        new ManagementAccess(codec).access(directory, actor.userId());
        var current = scopes(directory, actor.userId(), EnterpriseGrantPurpose.HUMAN, now);
        // Data ownership survives reauthentication. Observation grants can be added later;
        // the resource set remains the run's original ceiling and execution is not resumed.
        exact(current, action, "workflow:" + run.workflowId());
        for (var scope : authority.requiredScopes()) cover(current, scope);
        for (var scope : authority.agentScopes()) cover(current, scope);
        var secrets = scopes(directory, actor.userId(), EnterpriseGrantPurpose.SECRET, now);
        for (var scope : authority.agentSecretScopes()) cover(secrets, scope);
        policies(directory,authority,run.workflowId(),action,now);
    }

    private static List<EnterpriseScope> requirements(WorkflowConfiguration config, WorkflowDefinition workflow) {
        var result = new ArrayList<EnterpriseScope>();
        result.add(requirement("model.infer", "model-group:" + workflow.modelGroupId()));
        for (var agent : workflow.agents()) {
            result.add(requirement("agent.execute", "agent:" + agent.agentId()));
            var definition = config.agentDefinitions().stream().filter(a -> a.agentId().equals(agent.agentId()))
                .findFirst().orElseThrow(ManagementAccess::denied);
            result.add(requirement("model.infer", "model-group:" + definition.modelGroupId()));
        }
        return List.copyOf(result);
    }
    private static EnterpriseScope requirement(String action, String locator) {
        return new EnterpriseScope(new GroupSelection(List.of(), true), new GroupSelection(List.of(), true), List.of(action), false,
            List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM, locator, EnterpriseResourceMatch.EXACT)),
            new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null));
    }
    private static List<EnterpriseScope> live(List<EnterpriseScope> scopes, long now) {
        return scopes.stream().filter(s -> ManagementAccess.current(s.conditions(), now)
            && !s.conditions().requireOnline() && !s.conditions().highRisk()).toList();
    }

    private List<EnterpriseScope> scopes(Directory directory, String user, EnterpriseGrantPurpose purpose, long now) {
        new ManagementAccess(codec).access(directory, user);
        if (new ManagementAccess(codec).superAdministrator(directory, user)) return List.of(TenantSuperuser.scope());
        var teams = GroupGraph.memberships(directory, codec, Kind.USER, user, true);
        return grants(directory).stream().filter(g -> g.purpose() == purpose && sourced(directory, g, Kind.TEAM, teams))
            .map(ControlAccessGrant::scope).filter(s -> ManagementAccess.current(s.conditions(), now)
                && !s.conditions().requireOnline() && !s.conditions().highRisk()).toList();
    }
    private List<EnterpriseScope> agentScopes(Directory directory, WorkflowDefinition workflow, boolean secrets) {
        var groups = workflow.agents().stream().flatMap(a -> GroupGraph.memberships(directory, codec, Kind.AGENT, a.agentId(), true).stream()).distinct().toList();
        return grants(directory).stream().filter(g -> sourced(directory, g, Kind.AGENT_GROUP, groups)
                && (secrets ? g.purpose() == EnterpriseGrantPurpose.SECRET
                    : Set.of(EnterpriseGrantPurpose.SERVICE, EnterpriseGrantPurpose.CAPABILITY).contains(g.purpose())))
            .map(ControlAccessGrant::scope).distinct().toList();
    }
    private List<ControlAccessGrant> grants(Directory directory) {
        return directory.entries().values().stream().filter(e -> e.enabled() && e.id().kind() == Kind.GRANT)
            .map(e -> codec.model(e.document(), ControlAccessGrant.class)).toList();
    }
    private boolean sourced(Directory directory, ControlAccessGrant grant, Kind kind, List<String> groups) {
        if (grant.sourceType().name().equals(kind.name())) return groups.contains(grant.sourceId());
        if (grant.sourceType() != EnterpriseSourceType.ROLE) return false;
        var role = directory.entries().get(Kind.ROLE.id(grant.sourceId()));
        return role != null && role.enabled() && groups.stream().anyMatch(id ->
            GroupGraph.roles(directory.entries().get(kind.id(id)), codec).contains(grant.sourceId()));
    }
    public String identities(Directory directory, String user) {
        var stamps = directory.entries().values().stream().filter(e -> e.id().kind() == Kind.IDENTITY_BINDING)
            .map(e -> codec.model(e.document(), ControlIdentityBinding.class)).filter(b -> b.userId().equals(user))
            .sorted(Comparator.comparing(ControlIdentityBinding::id))
            .map(b -> List.of(b.id(), b.enabled(), b.sessionEpoch(), b.sessionsValidAfterUnixMs())).toList();
        return DirectoryService.digest(codec.json(stamps));
    }
    private static EnterpriseScope unconditional(EnterpriseScope scope) {
        return new EnterpriseScope(scope.toolGroups(), scope.deviceGroups(), scope.actions(), scope.allActions(), scope.resources(),
            new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null));
    }
    private static void cover(List<EnterpriseScope> scopes, EnterpriseScope required) {
        if (scopes.stream().noneMatch(s -> ManagementAccess.contains(unconditional(s), unconditional(required)))) throw ManagementAccess.denied();
    }
    private static void exact(List<EnterpriseScope> scopes, String action, String locator) {
        if (scopes.stream().noneMatch(s -> s.toolGroups().all() && s.deviceGroups().all() && (s.allActions() || s.actions().contains(action))
                && s.resources().stream().anyMatch(r -> EnterpriseEvaluator.resource(r, new ResourceDescriptor(ResourceKind.CUSTOM, locator))))) throw ManagementAccess.denied();
    }
    private static boolean overlap(EnterpriseScope a, EnterpriseScope b) {
        if (!a.allActions() && !b.allActions() && Collections.disjoint(a.actions(), b.actions())) return false;
        if (!a.toolGroups().all() && !b.toolGroups().all() && Collections.disjoint(a.toolGroups().ids(), b.toolGroups().ids())) return false;
        if (!a.deviceGroups().all() && !b.deviceGroups().all() && Collections.disjoint(a.deviceGroups().ids(), b.deviceGroups().ids())) return false;
        return a.resources().stream().anyMatch(x -> b.resources().stream().anyMatch(y -> x.kind() == y.kind()
            && (x.match() == EnterpriseResourceMatch.ANY || y.match() == EnterpriseResourceMatch.ANY
                || x.locator().equals(y.locator()) || x.match() == EnterpriseResourceMatch.PREFIX && y.locator().startsWith(x.locator()+"/")
                || y.match() == EnterpriseResourceMatch.PREFIX && x.locator().startsWith(y.locator()+"/"))));
    }
}
