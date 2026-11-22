// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
/** Read-only provenance keeps each inherited scope intact. Execution always uses EnterpriseEvaluator. */
public final class EffectiveAccess {
    private final Store store;private final Codec codec;
    public EffectiveAccess(Store store,Codec codec){this.store=store;this.codec=codec;}
    public Store.Reply get(DirectoryService.Actor actor,Ids.Kind kind,String id){if(!GroupGraph.individual(kind))throw Failure.validation();Ids.valid(id);
        return store.transaction(actor.tenant(),false,tx->{var graph=tx.load();var entry=graph.entries().get(kind.id(id));if(entry==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Record not found");var access=new ManagementAccess(codec);long now=System.currentTimeMillis();access.requireEntry(graph,actor.userId(),"read",entry,now);
            var memberships=new ArrayList<EnterpriseEffectiveMembership>();var roles=new TreeMap<String,ControlRole>();var groups=GroupGraph.memberships(graph,codec,kind,id,false);var groupKind=GroupGraph.groupKind(kind);var grants=graph.entries().values().stream().filter(e->e.id().kind()==Ids.Kind.GRANT).sorted(Comparator.comparing(e->e.id().value())).toList();
            for(var groupId:groups){var group=graph.entries().get(groupKind.id(groupId));var roleIds=GroupGraph.roles(group,codec);var inherited=new ArrayList<ControlAccessGrant>();for(var grantEntry:grants){var grant=codec.model(grantEntry.document(),ControlAccessGrant.class);boolean source=grant.sourceType().name().equals(groupKind.name())&&grant.sourceId().equals(groupId)||grant.sourceType()==EnterpriseSourceType.ROLE&&roleIds.contains(grant.sourceId());boolean target=kind==Ids.Kind.TOOL&&EnterpriseEvaluator.selected(grant.scope().toolGroups(),groupId)||kind==Ids.Kind.DEVICE&&EnterpriseEvaluator.selected(grant.scope().deviceGroups(),groupId);if(source||target)try{access.requireEntry(graph,actor.userId(),"read",grantEntry,now);inherited.add(grant);}catch(Failure denied){if(denied.status()!=403)throw denied;}}
                if(kind==Ids.Kind.USER&&entry.enabled())for(var entitlement:TenantSuperuser.grants(graph,codec,id))if(entitlement.sourceId().equals(groupId))try{access.requireEntry(graph,actor.userId(),"read",codec.entry(Ids.Kind.GRANT,codec.json(entitlement)),now);inherited.add(entitlement);}catch(Failure denied){if(denied.status()!=403)throw denied;}
                memberships.add(new EnterpriseEffectiveMembership(EnterpriseGroupType.valueOf(groupKind.name()),groupId,group.enabled(),roleIds,inherited));for(var roleId:roleIds){var r=graph.entries().get(Ids.Kind.ROLE.id(roleId));if(r!=null)try{access.requireEntry(graph,actor.userId(),"read",r,now);var role=codec.model(r.document(),ControlRole.class);if(role.roleType()==EnterpriseRoleType.MANAGEMENT)roles.put(role.id(),role);}catch(Failure denied){if(denied.status()!=403)throw denied;}}
            }
            var bindings=new ArrayList<ControlExecutionBinding>();for(var e:graph.entries().values().stream().filter(e->e.id().kind()==Ids.Kind.BINDING).sorted(Comparator.comparing(e->e.id().value())).toList())try{access.requireEntry(graph,actor.userId(),"read",e,now);var b=codec.model(e.document(),ControlExecutionBinding.class);if(kind==Ids.Kind.TOOL&&groups.contains(b.toolGroupId())||kind==Ids.Kind.DEVICE&&groups.contains(b.deviceGroupId())||memberships.stream().flatMap(m->m.grants().stream()).anyMatch(g->EnterpriseEvaluator.selected(g.scope().toolGroups(),b.toolGroupId())&&EnterpriseEvaluator.selected(g.scope().deviceGroups(),b.deviceGroupId())))bindings.add(b);}catch(Failure denied){if(denied.status()!=403)throw denied;}
            return new Store.Reply(200,codec.json(new EnterpriseEffectiveAccess(id,ControlEntityKind.valueOf(kind.name()),graph.revision(),tx.enterprise().authorizationEpoch(),memberships,bindings,List.copyOf(roles.values()))),graph.revision());});
    }
}
