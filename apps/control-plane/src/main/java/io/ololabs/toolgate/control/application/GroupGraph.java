// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.*;

/** Canonical membership graph. Mutations require a serialized tenant transaction. */
public final class GroupGraph {
    public static final Map<Kind,String> DEFAULTS=Map.of(Kind.TEAM,"team-default",Kind.AGENT_GROUP,"default-agents",Kind.TOOL_GROUP,"default-tools",Kind.DEVICE_GROUP,"default-devices");
    private GroupGraph() {}
    public static Kind groupKind(Kind entity) { return switch(entity) { case USER->Kind.TEAM; case AGENT->Kind.AGENT_GROUP; case TOOL->Kind.TOOL_GROUP; case DEVICE->Kind.DEVICE_GROUP; default->throw Failure.validation(); }; }
    public static Kind entityKind(Kind group) { return switch(group) { case TEAM->Kind.USER; case AGENT_GROUP->Kind.AGENT; case TOOL_GROUP->Kind.TOOL; case DEVICE_GROUP->Kind.DEVICE; default->throw Failure.validation(); }; }
    public static boolean individual(Kind kind) { return Set.of(Kind.USER,Kind.AGENT,Kind.TOOL,Kind.DEVICE).contains(kind); }
    public static boolean group(Kind kind) { return DEFAULTS.containsKey(kind); }
    public static boolean protectedDefault(Ids.RecordId id) { return id.value().equals(DEFAULTS.get(id.kind())); }
    public static List<String> members(Directory.Entry entry,Codec codec) {
        return switch(entry.id().kind()) {
            case TEAM->codec.model(entry.document(),ControlTeam.class).userIds();
            case AGENT_GROUP->codec.model(entry.document(),ControlAgentGroup.class).agentIds();
            case TOOL_GROUP->codec.model(entry.document(),ControlToolGroup.class).toolIds();
            case DEVICE_GROUP->codec.model(entry.document(),ControlDeviceGroup.class).deviceIds();
            default->throw Failure.validation();
        };
    }
    public static List<String> roles(Directory.Entry entry,Codec codec) { return switch(entry.id().kind()) { case TEAM->codec.model(entry.document(),ControlTeam.class).roleIds(); case AGENT_GROUP->codec.model(entry.document(),ControlAgentGroup.class).roleIds(); default->List.of(); }; }
    public static List<String> memberships(Directory directory,Codec codec,Kind entity,String id,boolean enabledOnly) {
        return directory.entries().values().stream().filter(e->e.id().kind()==groupKind(entity)&&(!enabledOnly||e.enabled())).filter(e->members(e,codec).contains(id)).map(e->e.id().value()).sorted().toList();
    }
    public static Directory.Entry withMembers(Directory.Entry entry,Codec codec,List<String> members) {
        Object value=switch(entry.id().kind()) {
            case TEAM->{var g=codec.model(entry.document(),ControlTeam.class); yield new ControlTeam(g.id(),g.name(),g.enabled(),g.revision()+1,members,g.roleIds());}
            case AGENT_GROUP->{var g=codec.model(entry.document(),ControlAgentGroup.class); yield new ControlAgentGroup(g.id(),g.name(),g.slug(),g.enabled(),g.revision()+1,members,g.roleIds());}
            case TOOL_GROUP->{var g=codec.model(entry.document(),ControlToolGroup.class); yield new ControlToolGroup(g.id(),g.name(),g.enabled(),g.revision()+1,members);}
            case DEVICE_GROUP->{var g=codec.model(entry.document(),ControlDeviceGroup.class); yield new ControlDeviceGroup(g.id(),g.name(),g.enabled(),g.revision()+1,members);}
            default->throw Failure.validation();
        };
        return codec.entry(entry.id().kind(),codec.json(value));
    }
    public static void defaults(Map<Ids.RecordId,Directory.Entry> entries,Codec codec) {
        for(var kind:DEFAULTS.keySet()) {
            var id=kind.id(DEFAULTS.get(kind)); if(entries.containsKey(id))continue;
            Object value=switch(kind) {
                case TEAM->new ControlTeam(id.value(),"Default team",true,1L,List.of(),List.of());
                case AGENT_GROUP->new ControlAgentGroup(id.value(),"Default agent group",null,true,1L,List.of(),List.of());
                case TOOL_GROUP->new ControlToolGroup(id.value(),"Default tool group",true,1L,List.of());
                case DEVICE_GROUP->new ControlDeviceGroup(id.value(),"Default device group",true,1L,List.of());
                default->throw Failure.validation();
            };
            entries.put(id,codec.entry(kind,codec.json(value)));
        }
    }
    public static void addDefault(Map<Ids.RecordId,Directory.Entry> entries,Codec codec,Kind entity,String id) {
        defaults(entries,codec); var kind=groupKind(entity);
        if(entity==Kind.TOOL && entries.values().stream().filter(e->e.id().kind()==kind).anyMatch(e->members(e,codec).contains(id)))return;
        var group=entries.get(kind.id(DEFAULTS.get(kind))); var members=new TreeSet<>(members(group,codec));
        if(members.add(id))entries.put(group.id(),withMembers(group,codec,List.copyOf(members)));
    }
    public static void removeMember(Map<Ids.RecordId,Directory.Entry> entries,Codec codec,Kind entity,String id) {
        for(var group:List.copyOf(entries.values()))if(group.id().kind()==groupKind(entity)) {
            var members=new TreeSet<>(members(group,codec)); if(members.remove(id))entries.put(group.id(),withMembers(group,codec,List.copyOf(members)));
        }
    }
    public static void setMemberships(Map<Ids.RecordId,Directory.Entry> entries,Codec codec,Kind entity,String id,List<String> groups) {
        if(groups.isEmpty() || entity==Kind.TOOL&&groups.size()!=1 || new HashSet<>(groups).size()!=groups.size())throw Failure.validation();
        if(!entries.containsKey(entity.id(id)))throw Failure.validation(); var kind=groupKind(entity);
        for(var group:groups)if(!entries.containsKey(kind.id(group)))throw Failure.validation();
        for(var group:List.copyOf(entries.values()))if(group.id().kind()==kind) {
            var members=new TreeSet<>(members(group,codec)); boolean changed=groups.contains(group.id().value())?members.add(id):members.remove(id);
            if(changed)entries.put(group.id(),withMembers(group,codec,List.copyOf(members)));
        }
    }
    public static void validate(Directory directory,Codec codec) {
        for(var kind:DEFAULTS.keySet()) {
            var group=directory.entries().get(kind.id(DEFAULTS.get(kind))); if(group==null||!group.enabled())throw new IllegalArgumentException("Protected default group is required");
        }
        // Slugs address the scoped MCP routes, so one slug names at most one agent and at most one agent group.
        var slugs=new java.util.HashSet<String>();
        for(var e:directory.entries().values()) {
            var slug=e.id().kind()==Kind.AGENT?codec.model(e.document(),ControlAgent.class).slug():e.id().kind()==Kind.AGENT_GROUP?codec.model(e.document(),ControlAgentGroup.class).slug():null;
            if(slug!=null&&!slugs.add(e.id().kind()+"\n"+slug))throw new IllegalArgumentException("Slug is already used");
        }
        for(var e:directory.entries().values()) {
            var kind=e.id().kind();
            if(individual(kind)) { var memberships=memberships(directory,codec,kind,e.id().value(),false); if(memberships.isEmpty() || kind==Kind.TOOL&&memberships.size()!=1)throw new IllegalArgumentException("Mandatory group membership is missing or ambiguous"); }
            if(kind==Kind.TEAM||kind==Kind.AGENT_GROUP)for(var id:roles(e,codec)) {
                var role=codec.model(directory.entries().get(Kind.ROLE.id(id)).document(),ControlRole.class);
                if(kind==Kind.TEAM&&role.roleType()==EnterpriseRoleType.ACTOR_SERVICE || kind==Kind.AGENT_GROUP&&role.roleType()!=EnterpriseRoleType.ACTOR_SERVICE)throw new IllegalArgumentException("Role is incompatible with group type");
            }
            if(kind==Kind.ROLE) {
                var role=codec.model(e.document(),ControlRole.class);
                if(role.roleType()!=EnterpriseRoleType.MANAGEMENT && (!role.managementRules().isEmpty()||role.portalRole()!=UserRole.BASIC))throw new IllegalArgumentException("Runtime role cannot confer management permissions");
                for(var rule:role.managementRules()) { selection(rule.groups()); conditions(rule.conditions()); rule.grantableScopes().forEach(GroupGraph::scope); }
            }
            if(kind==Kind.GRANT) {
                var grant=codec.model(e.document(),ControlAccessGrant.class); scope(grant.scope());
                if(grant.sourceType()==EnterpriseSourceType.TEAM&&grant.purpose()!=EnterpriseGrantPurpose.HUMAN&&grant.purpose()!=EnterpriseGrantPurpose.SECRET || grant.sourceType()==EnterpriseSourceType.AGENT_GROUP&&grant.purpose()==EnterpriseGrantPurpose.HUMAN)throw new IllegalArgumentException("Grant source is incompatible with purpose");
                if(grant.sourceType()==EnterpriseSourceType.ROLE) {
                    var role=codec.model(directory.entries().get(Kind.ROLE.id(grant.sourceId())).document(),ControlRole.class);
                    if(role.roleType()==EnterpriseRoleType.MANAGEMENT || grant.purpose()==EnterpriseGrantPurpose.HUMAN&&role.roleType()!=EnterpriseRoleType.HUMAN || (grant.purpose()==EnterpriseGrantPurpose.CAPABILITY||grant.purpose()==EnterpriseGrantPurpose.SERVICE)&&role.roleType()!=EnterpriseRoleType.ACTOR_SERVICE)throw new IllegalArgumentException("Grant cannot use this role type");
                }
            }
            if(kind==Kind.DELEGATION)scope(codec.model(e.document(),ControlDelegation.class).scope());
            if(kind==Kind.AGENT_DELEGATION)scope(codec.model(e.document(),ControlAgentDelegation.class).scope());
            if(kind==Kind.POLICY) {
                var p=codec.model(e.document(),ControlPolicy.class);scope(p.scope()); selection(p.teams());selection(p.agentGroups());selection(p.approverTeams());
                if(p.decision()==Decision.ASK&&!p.approverTeams().all()&&p.approverTeams().ids().isEmpty())throw new IllegalArgumentException("ASK requires an explicit reviewer group");
            }
            if(kind==Kind.BINDING)StandardAccessPresets.validateBinding(codec.model(e.document(),ControlExecutionBinding.class));
            if(kind==Kind.WORKLOAD_BINDING) {
                var b=codec.model(e.document(),ControlWorkloadBinding.class);
                if(b.mode()==RequestMode.HUMAN || b.mode()==RequestMode.DELEGATED&&(b.delegatedUserId()==null||b.delegatedSessionEpoch()==null) || b.mode()==RequestMode.SERVICE&&(b.delegatedUserId()!=null||b.delegatedSessionEpoch()!=null))throw new IllegalArgumentException("Invalid workload identity mode");
            }
        }
        var credentials=new HashSet<String>();for(var e:directory.entries().values())if(e.id().kind()==Kind.WORKLOAD_BINDING&&!credentials.add(codec.model(e.document(),ControlWorkloadBinding.class).credentialSha256()))throw new IllegalArgumentException("Workload credentials must be individually distinct");
        var stable=new HashSet<String>();
        for(var e:directory.entries().values())if(e.id().kind()==Kind.IDENTITY_BINDING) { var b=codec.model(e.document(),ControlIdentityBinding.class); if(!stable.add(b.issuer()+"\n"+b.subject()))throw new IllegalArgumentException("Duplicate stable identity"); }
    }
    public static void selection(GroupSelection s) { if(s.all()&&!s.ids().isEmpty())throw new IllegalArgumentException("Wildcard must be explicit and unambiguous"); }
    public static void scope(EnterpriseScope s) {
        selection(s.toolGroups());selection(s.deviceGroups());conditions(s.conditions()); if(s.allActions()&&!s.actions().isEmpty())throw new IllegalArgumentException("Action wildcard cannot include an allowlist");
        for(var r:s.resources())if(r.match()==EnterpriseResourceMatch.ANY&&!r.locator().isEmpty() || r.match()!=EnterpriseResourceMatch.ANY&&r.locator().isEmpty())throw new IllegalArgumentException("Ambiguous resource selector");
    }
    public static void conditions(EnterpriseConditions c) {
        if(c.expiresAtUnixMs()!=0&&c.expiresAtUnixMs()<=c.notBeforeUnixMs())throw new IllegalArgumentException("Invalid validity interval");
        for(var h:c.hoursUtc())if(h.endMinute()<=h.startMinute())throw new IllegalArgumentException("Invalid hours interval");
        for(var cidr:c.networkCidrs()) NetworkRange.parse(cidr);
    }
}
