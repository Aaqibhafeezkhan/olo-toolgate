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
        action=permission(group,action);if(!ManagementPermissionCatalog.ALL.contains(action))return false;
        if(superAdministrator(directory,user))return true;
        final String requested=action;
        return groupRules(directory,user).anyMatch(r->r.actions().contains(requested)&&r.groupType().name().equals(group.name())&&EnterpriseEvaluator.selected(r.groups(),id)&&current(r.conditions(),now));
    }
    /** Imperative service operations map to the canonical vocabulary here, never in role data. */
    public static String permission(Kind kind,String action){
        if(action.contains("."))return action;
        return ManagementPermissionCatalog.resource(kind)+"."+action;
    }
    /** Rules that confer authority over whole groups; relationship scopes require explicit target context. */
    public java.util.stream.Stream<EnterpriseManagementRule> groupRules(Directory directory,String user){return access(directory,user).grants().stream().filter(r->!own(r));}
    public static boolean own(EnterpriseManagementRule rule){return rule.deviceScope()!=null;}
    public String devicePlacementGroup(Directory directory,String user,String permission,String device,String currentDevice,long now){return access(directory,user).grants().stream().filter(r->r.actions().contains(permission)&&current(r.conditions(),now)&&("OWNED".equals(r.deviceScope())||"CURRENT".equals(r.deviceScope())&&device!=null&&device.equals(currentDevice))).map(r->r.groups().ids().getFirst()).sorted().findFirst().orElse(null);}
    public boolean mayEstablishDeviceContext(Directory directory,String user,boolean pending,long now){return superAdministrator(directory,user)||access(directory,user).grants().stream().anyMatch(r->current(r.conditions(),now)&&r.actions().stream().anyMatch(a->pending?a.equals("device.enroll"):a.startsWith("device."))&&(!r.groups().ids().isEmpty()||r.groups().all()));}
    public Set<String> capabilities(Directory directory,String user,long now){
        return capabilities(directory,user,now,null);
    }
    public Set<String> capabilities(Directory directory,String user,long now,String currentDevice){
        if(superAdministrator(directory,user))return ManagementPermissionCatalog.ALL;
        var result=new TreeSet<String>();access(directory,user).grants().stream().filter(r->current(r.conditions(),now)&&(!r.groups().ids().isEmpty()||r.groups().all())).forEach(r->r.actions().stream().filter(ManagementPermissionCatalog.ALL::contains).filter(p->!"CURRENT".equals(r.deviceScope())||currentDevice!=null||p.equals("device.enroll")).forEach(result::add));return Set.copyOf(result);
    }
    /** One target evaluator for directory reads, device APIs and rendered row capabilities. */
    public boolean groupDeviceAllowed(Directory directory,String user,String action,String id,long now){
        action=permission(Kind.DEVICE,action);var groups=GroupGraph.memberships(directory,codec,Kind.DEVICE,id,false);
        if(groups.isEmpty())groups=List.of("default-devices");
        for(var group:groups)if(!allowed(directory,user,action,Kind.DEVICE_GROUP,group,now))return false;
        return true;
    }
    public boolean deviceAllowed(Directory directory,String user,String action,String id,String owner,String currentDevice,long now){
        action=permission(Kind.DEVICE,action);if(!action.startsWith("device.")||!ManagementPermissionCatalog.ALL.contains(action))return false;
        if(groupDeviceAllowed(directory,user,action,id,now))return true;
        if(owner==null||!owner.equals(user))return false;
        var groups=GroupGraph.memberships(directory,codec,Kind.DEVICE,id,false);final String requested=action;
        return access(directory,user).grants().stream().anyMatch(rule->own(rule)&&rule.actions().contains(requested)&&current(rule.conditions(),now)
            &&("OWNED".equals(rule.deviceScope())||"CURRENT".equals(rule.deviceScope())&&id.equals(currentDevice))
            &&(groups.isEmpty()||groups.stream().allMatch(g->EnterpriseEvaluator.selected(rule.groups(),g))));
    }
    public List<String> deviceCapabilities(Directory directory,String user,String id,String owner,String currentDevice,long now){return ManagementPermissionCatalog.ALL.stream().filter(p->p.startsWith("device.")&&deviceAllowed(directory,user,p,id,owner,currentDevice,now)).sorted().toList();}
    /** Resolve live directory membership; never trust token roles or an actor's cached flag. */
    public boolean superAdministrator(Directory directory,String user) {return access(directory,user).portalRole()==UserRole.SUPER_ADMIN;}
    public void require(Directory directory,String user,String action,Kind group,String id,long now) {
        if(!allowed(directory,user,action,group,id,now))throw denied();
    }
    public void requireEntry(Directory directory,String user,String action,Directory.Entry entry,long now) {
        requireEntry(directory,user,action,entry,now,null);
    }
    public void requireEntry(Directory directory,String user,String action,Directory.Entry entry,long now,String currentDevice) {
        if(entry==null)throw denied();
        var requested=permission(entry.id().kind(),action);if(!ManagementPermissionCatalog.ALL.contains(requested))throw denied();
        if(superAdministrator(directory,user))return;
        var kind=entry.id().kind();
        if(GroupGraph.group(kind)) {require(directory,user,requested,kind,entry.id().value(),now);return;}
        if(kind==Kind.DEVICE&&requested.startsWith("device.")) {var device=codec.model(entry.document(),ControlDevice.class);if(!deviceAllowed(directory,user,requested,device.id(),device.ownerUserId(),currentDevice,now))throw denied();return;}
        if(GroupGraph.individual(kind)) {
            var groups=GroupGraph.memberships(directory,codec,kind,entry.id().value(),false);
            if(groups.isEmpty())groups=List.of(GroupGraph.DEFAULTS.get(GroupGraph.groupKind(kind)));
            for(var group:groups)require(directory,user,requested,GroupGraph.groupKind(kind),group,now);
            return;
        }
        if(kind==Kind.GRANT) {
            var grant=codec.model(entry.document(),ControlAccessGrant.class);
            if(requested.equals("access_grant.read")) { requireScopeRead(directory,user,requested,grant.scope(),now);return; }
            var rules=access(directory,user).grants();
            if(rules.stream().noneMatch(r->r.actions().contains(requested)&&r.actions().contains("access_grant.grant")&&current(r.conditions(),now)&&r.grantableScopes().stream().anyMatch(s->contains(s,grant.scope()))))throw denied();
            // A grantable ceiling does not grant authority over the receiving group/role.
            if(grant.sourceType()!=EnterpriseSourceType.ROLE)require(directory,user,requested,Kind.valueOf(grant.sourceType().name()),grant.sourceId(),now);
            else requireAll(directory,user,"role.manage_permissions",now);
            return;
        }
        if(kind==Kind.ROLE){requireAll(directory,user,requested,now);if(!requested.equals("role.read"))requireRoleCeilings(directory,user,codec.model(entry.document(),ControlRole.class),now);return;}
        if(kind==Kind.POLICY){requireScopeRead(directory,user,requested,codec.model(entry.document(),ControlPolicy.class).scope(),now);return;}
        if(kind==Kind.DELEGATION){var d=codec.model(entry.document(),ControlDelegation.class);require(directory,user,requested,Kind.TEAM,d.teamId(),now);require(directory,user,requested,Kind.AGENT_GROUP,d.agentGroupId(),now);return;}
        if(kind==Kind.AGENT_DELEGATION){var d=codec.model(entry.document(),ControlAgentDelegation.class);require(directory,user,requested,Kind.AGENT_GROUP,d.fromAgentGroupId(),now);require(directory,user,requested,Kind.AGENT_GROUP,d.toAgentGroupId(),now);return;}
        if(kind==Kind.BINDING){var b=codec.model(entry.document(),ControlExecutionBinding.class);require(directory,user,requested,Kind.TOOL_GROUP,b.toolGroupId(),now);require(directory,user,requested,Kind.DEVICE_GROUP,b.deviceGroupId(),now);return;}
        if(kind==Kind.WORKLOAD_BINDING){var b=codec.model(entry.document(),ControlWorkloadBinding.class);requireIndividual(directory,user,requested,Kind.AGENT,b.agentId(),now);return;}
        if(kind==Kind.IDENTITY_BINDING){var b=codec.model(entry.document(),ControlIdentityBinding.class);requireIndividual(directory,user,requested,Kind.USER,b.userId(),now);return;}
        if(kind==Kind.DEVICE_EVIDENCE){var b=codec.model(entry.document(),ControlDeviceEvidence.class);requireIndividual(directory,user,requested,Kind.DEVICE,b.deviceId(),now);return;}
        requireAll(directory,user,requested,now);
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
            if(inherited&&access(authority,user).grants().stream().noneMatch(rule->rule.actions().contains("access_grant.grant")&&current(rule.conditions(),now)&&rule.grantableScopes().stream().anyMatch(scope->contains(scope,grant.scope()))))throw denied();
        }
    }
    private void requireRoleCeilings(Directory directory,String user,ControlRole role,long now) {
        var authority=access(directory,user);
        if(authority.portalRole()==UserRole.SUPER_ADMIN)return;
        if(role.portalRole()==UserRole.SUPER_ADMIN&&authority.portalRole()!=UserRole.SUPER_ADMIN)throw denied();
        if(role.enabled())for(var entry:directory.entries().values())if(entry.id().kind()==Kind.GRANT&&entry.enabled()) {
            var grant=codec.model(entry.document(),ControlAccessGrant.class);
            if(grant.sourceType()==EnterpriseSourceType.ROLE&&grant.sourceId().equals(role.id())&&authority.grants().stream().noneMatch(rule->rule.actions().contains("access_grant.grant")&&current(rule.conditions(),now)&&rule.grantableScopes().stream().anyMatch(scope->contains(scope,grant.scope()))))throw denied();
        }
        for(var requested:role.managementRules()){
            if(authority.grants().stream().noneMatch(ceiling->ceiling.groupType()==requested.groupType()&&current(ceiling.conditions(),now)&&(!own(ceiling)||Objects.equals(ceiling.deviceScope(),requested.deviceScope()))
                &&selectionContains(ceiling.groups(),requested.groups())&&ceiling.actions().containsAll(requested.actions())
                &&requested.grantableScopes().stream().allMatch(scope->ceiling.grantableScopes().stream().anyMatch(candidate->contains(candidate,scope)))
                &&conditionsContain(ceiling.conditions(),requested.conditions())))throw denied();
        }
    }
    private static boolean conditionsContain(EnterpriseConditions ceiling,EnterpriseConditions requested){
        var none=new GroupSelection(List.of(),false);
        return contains(new EnterpriseScope(none,none,List.of(),false,List.of(),ceiling),new EnterpriseScope(none,none,List.of(),false,List.of(),requested));
    }
    public boolean globallyAllowed(Directory directory,String user,String action,long now) {
        if(!ManagementPermissionCatalog.ALL.contains(action))return false;
        if(superAdministrator(directory,user))return true;
        for(var group:GroupGraph.DEFAULTS.keySet())if(groupRules(directory,user).noneMatch(r->r.groupType().name().equals(group.name())&&r.groups().all()&&r.actions().contains(action)&&current(r.conditions(),now)))return false;
        return true;
    }
    public void requireAll(Directory directory,String user,String action,long now) {
        if(!globallyAllowed(directory,user,action,now))throw denied();
    }
    private void requireScopeRead(Directory d,String user,String action,EnterpriseScope scope,long now) {
        if(!scope.toolGroups().all()&&scope.toolGroups().ids().isEmpty()&&!scope.deviceGroups().all()&&scope.deviceGroups().ids().isEmpty()){requireAll(d,user,action,now);return;}
        for(var kind:List.of(Kind.TOOL_GROUP,Kind.DEVICE_GROUP)) {
            var selection=kind==Kind.TOOL_GROUP?scope.toolGroups():scope.deviceGroups();
            if(selection.all()) {
                if(groupRules(d,user).noneMatch(r->r.groupType().name().equals(kind.name())&&r.groups().all()&&r.actions().contains(action)&&current(r.conditions(),now)))throw denied();
            } else for(var id:selection.ids())require(d,user,action,kind,id,now);
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
