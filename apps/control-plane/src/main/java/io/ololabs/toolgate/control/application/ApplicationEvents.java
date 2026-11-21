// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.*;

/** One source/permission evaluator for polling, live streams and webhook delivery. */
public final class ApplicationEvents {
    private final Store store; private final Codec codec; private final TriggerService triggers; private final Clock clock;
    public ApplicationEvents(Store store, Codec codec, TriggerService triggers, Clock clock) {
        this.store=store;this.codec=codec;this.triggers=triggers;this.clock=clock;
    }
    public record Source(String key,String identityDigest){}
    public Source authorize(Store.Session tx, DirectoryService.Actor actor, ApplicationEventSource source, String id,
                            Set<ApplicationEventKind> kinds,boolean subscribing) {
        Ids.valid(id);
        if(actor.userId()==null || kinds.isEmpty())throw ManagementAccess.denied();
        var directory=tx.load();new ManagementAccess(codec).access(directory,actor.userId());
        if(actor.sessionExpiresAt()!=null && actor.sessionExpiresAt()<=clock.millis())throw ManagementAccess.denied();
        var users=new WorkflowUserAccess(codec);
        if(source==ApplicationEventSource.WORKFLOW) {
            if(kinds.contains(ApplicationEventKind.TRIGGER_STATUS))throw Failure.validation();
            var run=tx.workflowRuns().get(id);
            if(run==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Event source unavailable");
            if(subscribing)users.observe(directory,actor,run,"workflow.subscribe",clock.millis());
            for(var kind:kinds)users.observe(directory,actor,run,kind==ApplicationEventKind.RESULT?"workflow.result.read":"workflow.progress.read",clock.millis());
            return new Source(id,users.identities(directory,actor.userId()));
        }
        if(!kinds.equals(Set.of(ApplicationEventKind.TRIGGER_STATUS)))throw Failure.validation();
        var assignment=triggers.observable(tx,id);
        if(new ManagementAccess(codec).superAdministrator(directory,actor.userId()))
            return new Source(assignment.uid(),users.identities(directory,actor.userId()));
        var groups=GroupGraph.memberships(directory,codec,Ids.Kind.USER,actor.userId(),true);
        var scopes=directory.entries().values().stream().filter(e->e.enabled()&&e.id().kind()==Ids.Kind.GRANT)
            .map(e->codec.model(e.document(),ControlAccessGrant.class)).filter(g->g.purpose()==EnterpriseGrantPurpose.HUMAN)
            .filter(g->g.sourceType()==EnterpriseSourceType.TEAM&&groups.contains(g.sourceId())
                ||g.sourceType()==EnterpriseSourceType.ROLE&&activeRole(directory,g.sourceId(),groups))
            .map(ControlAccessGrant::scope).filter(s->ManagementAccess.current(s.conditions(),clock.millis())
                &&!s.conditions().requireOnline()&&!s.conditions().highRisk()).toList();
        var target=assignment.spec().path("target");
        var targets=new TreeSet<String>(TriggerService.strings(target.path("deviceGroups")));
        for(var device:TriggerService.strings(target.path("devices"))) {
            var entry=directory.entries().get(Ids.Kind.DEVICE.id(device));
            if(entry==null||!entry.enabled())throw ManagementAccess.denied();
            var membership=GroupGraph.memberships(directory,codec,Ids.Kind.DEVICE,device,true);
            targets.addAll(membership.isEmpty()?List.of("default-devices"):membership);
        }
        for(var group:targets){var entry=directory.entries().get(Ids.Kind.DEVICE_GROUP.id(group));if(entry==null||!entry.enabled())throw ManagementAccess.denied();}
        for(String action:subscribing?List.of("trigger.read","trigger.subscribe"):List.of("trigger.read"))
            if(scopes.stream().noneMatch(s->s.actions().contains(action)&&s.toolGroups().all()
                &&(s.deviceGroups().all()||s.deviceGroups().ids().containsAll(targets))
                &&s.resources().stream().anyMatch(r->r.kind()==ResourceKind.CUSTOM&&r.match()==EnterpriseResourceMatch.EXACT
                    &&r.locator().equals("trigger-assignment:"+assignment.uid()))))throw ManagementAccess.denied();
        return new Source(assignment.uid(),users.identities(directory,actor.userId()));
    }
    private boolean activeRole(Directory directory,String role,List<String> teams) {
        var entry=directory.entries().get(Ids.Kind.ROLE.id(role));
        return entry!=null&&entry.enabled()&&codec.model(entry.document(),ControlRole.class).roleType()==EnterpriseRoleType.HUMAN
            &&teams.stream().anyMatch(t->GroupGraph.roles(directory.entries().get(Ids.Kind.TEAM.id(t)),codec).contains(role));
    }
    public Store.Reply page(DirectoryService.Actor actor,ApplicationEventSource source,String id,long after,Set<ApplicationEventKind> kinds) {
        if(after<0||after>9007199254740991L)throw Failure.validation();
        return store.transaction(actor.tenant(),false,tx->{
            var resolved=authorize(tx,actor,source,id,kinds,true);
            var scanned=tx.applicationEvents().page(source,resolved.key(),after,200);
            var visible=scanned.stream().filter(e->kinds.contains(e.kind())).toList();
            return new Store.Reply(200,codec.json(new ApplicationEventPage(visible,scanned.isEmpty()?after:scanned.getLast().sequence())),0);
        });
    }
    public Store.Reply triggerStatus(DirectoryService.Actor actor,String name) {
        return store.transaction(actor.tenant(),false,tx->{
            authorize(tx,actor,ApplicationEventSource.TRIGGER,name,Set.of(ApplicationEventKind.TRIGGER_STATUS),false);
            var assignment=triggers.observable(tx,name);
            return new Store.Reply(200,codec.json(Map.of("assignmentId",assignment.uid(),"status",triggers.withStatus(tx,assignment).path("status"))),0);
        });
    }
}
