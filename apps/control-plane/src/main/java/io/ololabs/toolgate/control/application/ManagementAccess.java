// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Scoped administration derives exclusively from Management Roles on enabled Teams. */
public final class ManagementAccess {
    private final Codec codec;
    public ManagementAccess(Codec codec) { this.codec=codec; }
    public RoleResolver.Access access(Directory directory,String userId) {
        var entry=userId==null?null:directory.entries().get(Kind.USER.id(userId));
        if(entry==null||!entry.enabled())throw denied();
        return new RoleResolver(codec).resolve(directory,codec.model(entry.document(),ControlUser.class));
    }
    public boolean allowed(Directory directory,String user,String action,Kind group,String id,long now) {
        if(superAdministrator(directory,user))return true;
        return access(directory,user).grants().stream().anyMatch(r->r.actions().contains(action)&&r.groupType().name().equals(group.name())&&EnterpriseEvaluator.selected(r.groups(),id)&&current(r.conditions(),now));
    }
    /** Resolve live directory membership; never trust token roles or an actor's cached flag. */
    public boolean superAdministrator(Directory directory,String user) {return access(directory,user).portalRole()==UserRole.SUPER_ADMIN;}
    public void require(Directory directory,String user,String action,Kind group,String id,long now) {
        if(!allowed(directory,user,action,group,id,now))throw denied();
    }
    public void requireEntry(Directory directory,String user,String action,Directory.Entry entry,long now) {
        if(entry==null)throw denied();
        if(superAdministrator(directory,user))return;
        var kind=entry.id().kind();
        if(GroupGraph.group(kind)) {require(directory,user,action,kind,entry.id().value(),now);return;}
        if(GroupGraph.individual(kind)) {
            var groups=GroupGraph.memberships(directory,codec,kind,entry.id().value(),false);
            if(groups.isEmpty())groups=List.of(GroupGraph.DEFAULTS.get(GroupGraph.groupKind(kind)));
            for(var group:groups)require(directory,user,action,GroupGraph.groupKind(kind),group,now);
            return;
        }
        if(kind==Kind.GRANT) {
            var grant=codec.model(entry.document(),ControlAccessGrant.class);
            if(action.equals("read")) { requireScopeRead(directory,user,grant.scope(),now);return; }
            var rules=access(directory,user).grants();
            if(rules.stream().noneMatch(r->r.actions().contains("grant")&&current(r.conditions(),now)&&r.grantableScopes().stream().anyMatch(s->contains(s,grant.scope()))))throw denied();
            // A grantable ceiling does not grant authority over the receiving group/role.
            if(grant.sourceType()!=EnterpriseSourceType.ROLE)require(directory,user,"grant",Kind.valueOf(grant.sourceType().name()),grant.sourceId(),now);
            else requireAll(directory,user,"manage-role",now);
            return;
        }
        if(kind==Kind.ROLE){requireAll(directory,user,action.equals("read")?"read":"manage-role",now);if(!action.equals("read")&&!action.equals("delete"))requireRoleCeilings(directory,user,codec.model(entry.document(),ControlRole.class),now);return;}
        if(kind==Kind.POLICY){requireScopeRead(directory,user,codec.model(entry.document(),ControlPolicy.class).scope(),now);if(!action.equals("read"))requireAll(directory,user,"policy",now);return;}
        if(kind==Kind.DELEGATION){var d=codec.model(entry.document(),ControlDelegation.class);require(directory,user,action,Kind.TEAM,d.teamId(),now);require(directory,user,action,Kind.AGENT_GROUP,d.agentGroupId(),now);return;}
        if(kind==Kind.AGENT_DELEGATION){var d=codec.model(entry.document(),ControlAgentDelegation.class);require(directory,user,action,Kind.AGENT_GROUP,d.fromAgentGroupId(),now);require(directory,user,action,Kind.AGENT_GROUP,d.toAgentGroupId(),now);return;}
        if(kind==Kind.BINDING){var b=codec.model(entry.document(),ControlExecutionBinding.class);require(directory,user,action,Kind.TOOL_GROUP,b.toolGroupId(),now);require(directory,user,action,Kind.DEVICE_GROUP,b.deviceGroupId(),now);return;}
        if(kind==Kind.WORKLOAD_BINDING){var b=codec.model(entry.document(),ControlWorkloadBinding.class);requireIndividual(directory,user,action.equals("read")?"read":"rotate-credential",Kind.AGENT,b.agentId(),now);return;}
        if(kind==Kind.IDENTITY_BINDING){var b=codec.model(entry.document(),ControlIdentityBinding.class);requireIndividual(directory,user,action.equals("read")?"read":"rotate-credential",Kind.USER,b.userId(),now);return;}
        if(kind==Kind.DEVICE_EVIDENCE){var b=codec.model(entry.document(),ControlDeviceEvidence.class);requireIndividual(directory,user,action.equals("read")?"read":"attest",Kind.DEVICE,b.deviceId(),now);return;}
        requireAll(directory,user,action,now);
    }
    private void requireIndividual(Directory directory,String user,String action,Kind kind,String id,long now){var entry=directory.entries().get(kind.id(id));if(entry!=null)requireEntry(directory,user,action,entry,now);else require(directory,user,action,GroupGraph.groupKind(kind),GroupGraph.DEFAULTS.get(GroupGraph.groupKind(kind)),now);}
    /** Receiving-group membership cannot confer management or runtime rights beyond the allocating actor's ceilings. */
    public void requireAllocation(Directory authority,Directory proposed,String user,Directory.Entry group,long now) {
        if(group==null)throw Failure.validation();
        if(superAdministrator(authority,user))return;
        var roles=GroupGraph.roles(group,codec);
        for(var id:roles){var entry=proposed.entries().get(Kind.ROLE.id(id));if(entry==null)throw Failure.validation();var role=codec.model(entry.document(),ControlRole.class);if(role.enabled())requireRoleCeilings(authority,user,role,now);}
        for(var entry:proposed.entries().values())if(entry.id().kind()==Kind.GRANT&&entry.enabled()) {
            var grant=codec.model(entry.document(),ControlAccessGrant.class);
            boolean inherited=grant.sourceType()==EnterpriseSourceType.ROLE&&roles.contains(grant.sourceId())
                ||grant.sourceType().name().equals(group.id().kind().name())&&grant.sourceId().equals(group.id().value());
            if(inherited&&access(authority,user).grants().stream().noneMatch(rule->rule.actions().contains("grant")&&current(rule.conditions(),now)&&rule.grantableScopes().stream().anyMatch(scope->contains(scope,grant.scope()))))throw denied();
        }
    }
    private void requireRoleCeilings(Directory directory,String user,ControlRole role,long now) {
        var authority=access(directory,user);
        if(authority.portalRole()==UserRole.SUPER_ADMIN)return;
        if(role.portalRole()==UserRole.SUPER_ADMIN&&authority.portalRole()!=UserRole.SUPER_ADMIN)throw denied();
        if(role.enabled())for(var entry:directory.entries().values())if(entry.id().kind()==Kind.GRANT&&entry.enabled()) {
            var grant=codec.model(entry.document(),ControlAccessGrant.class);
            if(grant.sourceType()==EnterpriseSourceType.ROLE&&grant.sourceId().equals(role.id())&&authority.grants().stream().noneMatch(rule->rule.actions().contains("grant")&&current(rule.conditions(),now)&&rule.grantableScopes().stream().anyMatch(scope->contains(scope,grant.scope()))))throw denied();
        }
        for(var requested:role.managementRules()){
            if(authority.grants().stream().noneMatch(ceiling->ceiling.groupType()==requested.groupType()&&current(ceiling.conditions(),now)
                &&selectionContains(ceiling.groups(),requested.groups())&&ceiling.actions().containsAll(requested.actions())
                &&requested.grantableScopes().stream().allMatch(scope->ceiling.grantableScopes().stream().anyMatch(candidate->contains(candidate,scope)))
                &&conditionsContain(ceiling.conditions(),requested.conditions())))throw denied();
        }
    }
    private static boolean conditionsContain(EnterpriseConditions ceiling,EnterpriseConditions requested){
        var none=new GroupSelection(List.of(),false);
        return contains(new EnterpriseScope(none,none,List.of(),false,List.of(),ceiling),new EnterpriseScope(none,none,List.of(),false,List.of(),requested));
    }
    public void requireAll(Directory directory,String user,String action,long now) {
        if(superAdministrator(directory,user))return;
        for(var group:GroupGraph.DEFAULTS.keySet())if(access(directory,user).grants().stream().noneMatch(r->r.groupType().name().equals(group.name())&&r.groups().all()&&r.actions().contains(action)&&current(r.conditions(),now)))throw denied();
    }
    private void requireScopeRead(Directory d,String user,EnterpriseScope scope,long now) {
        for(var kind:List.of(Kind.TOOL_GROUP,Kind.DEVICE_GROUP)) {
            var selection=kind==Kind.TOOL_GROUP?scope.toolGroups():scope.deviceGroups();
            if(selection.all()) {
                if(access(d,user).grants().stream().noneMatch(r->r.groupType().name().equals(kind.name())&&r.groups().all()&&r.actions().contains("read")&&current(r.conditions(),now)))throw denied();
            } else for(var id:selection.ids())require(d,user,"read",kind,id,now);
        }
    }
    public static boolean current(EnterpriseConditions c,long now) {
        if(now<c.notBeforeUnixMs()||c.expiresAtUnixMs()!=0&&now>=c.expiresAtUnixMs())return false;
        // Management authentication currently supplies no trusted network/posture/amount evidence.
        if(!c.networkCidrs().isEmpty()||!c.devicePosture().isEmpty()||!c.regions().isEmpty()||c.maxAmountMinorUnits()!=null||c.maxInvocationsPerMinute()!=null)return false;
        if(c.hoursUtc().isEmpty())return true;
        var time=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC);int day=time.getDayOfWeek().getValue()%7,minute=time.getHour()*60+time.getMinute();
        return c.hoursUtc().stream().anyMatch(h->h.dayOfWeek()==day&&h.startMinute()<=minute&&minute<h.endMinute());
    }
    public static boolean selectionContains(GroupSelection ceiling,GroupSelection requested) {return ceiling.all()||!requested.all()&&ceiling.ids().containsAll(requested.ids());}
    public static boolean contains(EnterpriseScope ceiling,EnterpriseScope requested) {
        if(!selectionContains(ceiling.toolGroups(),requested.toolGroups())||!selectionContains(ceiling.deviceGroups(),requested.deviceGroups())||!ceiling.allActions()&&(requested.allActions()||!ceiling.actions().containsAll(requested.actions())))return false;
        for(var r:requested.resources())if(ceiling.resources().stream().noneMatch(c->c.kind()==r.kind()&&(c.match()==EnterpriseResourceMatch.ANY||c.match()==r.match()&&c.locator().equals(r.locator())||c.match()==EnterpriseResourceMatch.PREFIX&&r.match()!=EnterpriseResourceMatch.ANY&&EnterpriseEvaluator.resource(c,new ResourceDescriptor(r.kind(),r.locator())))))return false;
        var a=ceiling.conditions();var b=requested.conditions();
        return b.notBeforeUnixMs()>=a.notBeforeUnixMs()&&(a.expiresAtUnixMs()==0||b.expiresAtUnixMs()!=0&&b.expiresAtUnixMs()<=a.expiresAtUnixMs())
            &&(!a.requireOnline()||b.requireOnline())&&(!a.highRisk()||b.highRisk())&&b.devicePosture().containsAll(a.devicePosture())
            &&(a.networkCidrs().isEmpty()||!b.networkCidrs().isEmpty()&&a.networkCidrs().containsAll(b.networkCidrs()))
            &&(a.regions().isEmpty()||!b.regions().isEmpty()&&a.regions().containsAll(b.regions()))
            &&(a.hoursUtc().isEmpty()||!b.hoursUtc().isEmpty()&&a.hoursUtc().containsAll(b.hoursUtc()))
            &&(a.maxAmountMinorUnits()==null||b.maxAmountMinorUnits()!=null&&b.maxAmountMinorUnits()<=a.maxAmountMinorUnits())
            &&(a.maxInvocationsPerMinute()==null||b.maxInvocationsPerMinute()!=null&&b.maxInvocationsPerMinute()<=a.maxInvocationsPerMinute());
    }
    public static Failure denied() {return new Failure(ErrorCode.FORBIDDEN,403,"Group management access denied");}
}
