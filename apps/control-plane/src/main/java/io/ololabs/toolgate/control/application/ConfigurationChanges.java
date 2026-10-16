// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.time.Clock;
import java.util.*;
import java.lang.reflect.*;

/** Maker/checker configuration authority. Preview and apply run the identical canonical mutation. */
public final class ConfigurationChanges {
    private final Store store;private final Codec codec;private final Clock clock;private final int maxRecords,maxBytes;
    public ConfigurationChanges(Store store,Codec codec,Clock clock,int maxRecords,int maxBytes){this.store=store;this.codec=codec;this.clock=clock;this.maxRecords=maxRecords;this.maxBytes=maxBytes;}
    private long now(Store.Session tx){long now=clock.millis();if(now<0||tx.approvalClock(now)>now)throw Failure.unavailable();return now;}
    private Store.Reply reply(EnterpriseConfigurationChange change,int status){return new Store.Reply(status,codec.json(change),change.revision());}
    private EnterpriseConfigurationChange row(Store.Session tx,String id){var c=tx.enterprise().configuration(Ids.valid(id));if(c==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Configuration change not found");return c;}
    private record Preview(Directory after,Store.Reply reply,String settings){}
    private Preview execute(Store.Session tx,DirectoryService.Actor actor,EnterpriseConfigurationCommand c,String key,String requestId,boolean preview,long mutationTime){
        Directory[] after={tx.load()};String[] settings={null};
        var session=preview?(Store.Session)Proxy.newProxyInstance(Store.Session.class.getClassLoader(),new Class<?>[]{Store.Session.class},(proxy,method,args)->{
            switch(method.getName()){case "save":after[0]=(Directory)args[1];return null;case "load":return after[0];case "saveServerSettings":settings[0]=(String)args[1];return null;case "serverSettings":if(settings[0]!=null)return settings[0];return tx.serverSettings();case "audit":case "remember":return null;case "replay":return null;default:try{return method.invoke(tx,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
        }):tx;
        Store bound=new Store(){public <T>T transaction(Ids.TenantId tenant,boolean write,java.util.function.Function<Session,T> work){if(!tenant.equals(actor.tenant()))throw ManagementAccess.denied();return work.apply(session);}};
        var directory=new DirectoryService(bound,codec,maxRecords,maxBytes,Clock.fixed(java.time.Instant.ofEpochMilli(mutationTime),java.time.ZoneOffset.UTC));
        if(c.kind()==ControlEntityKind.SETTINGS){if(c.operation()!=EnterpriseConfigurationOperation.UPDATE||!ServerSettingsService.ENTITY.equals(c.entityId()))throw Failure.validation();
            var saved=ServerSettingsService.apply(session,codec,codec.model(c.document(),io.ololabs.toolgate.contracts.v2.ControlServerSettingsPatch.class),c.expectedRevision(),session.load());if(!preview)tx.audit(actor.id(),"SERVER_SETTINGS_UPDATE",ServerSettingsService.ENTITY,saved.revision(),requestId,DirectoryService.digest(c.document()));
            return new Preview(after[0],new Store.Reply(200,codec.json(saved),saved.revision()),settings[0]);}
        var kind=Ids.Kind.valueOf(c.kind().name());
        var result=switch(c.operation()){
            case CREATE,UPDATE,DELETE->directory.mutate(actor,kind,c.entityId(),c.operation().name(),c.operation()==EnterpriseConfigurationOperation.DELETE?null:c.document(),c.expectedRevision(),key,requestId);
            case MEMBERSHIPS->directory.setMemberships(actor,kind,c.entityId(),c.document(),c.expectedRevision(),key,requestId);
            case IMPORT->directory.importConfig(actor,c.document(),false,c.expectedRevision(),key,requestId);
        };
        return new Preview(preview?after[0]:tx.load(),result,settings[0]);
    }
    private List<EnterpriseConfigurationImpact> impact(Directory before,Directory after){
        var keys=new TreeSet<Ids.RecordId>(Comparator.comparing((Ids.RecordId id)->id.kind().name()).thenComparing(Ids.RecordId::value));keys.addAll(before.entries().keySet());keys.addAll(after.entries().keySet());var result=new ArrayList<EnterpriseConfigurationImpact>();
        for(var id:keys){var old=before.entries().get(id);var next=after.entries().get(id);if(!Objects.equals(old,next))result.add(new EnterpriseConfigurationImpact(ControlEntityKind.valueOf(id.kind().name()),id.value(),old==null?EnterpriseConfigurationImpactOperation.CREATE:next==null?EnterpriseConfigurationImpactOperation.DELETE:EnterpriseConfigurationImpactOperation.UPDATE,recordDigest(old),recordDigest(next)));}
        return List.copyOf(result);
    }
    /** Directory impact plus the settings record a preview would save. */
    private List<EnterpriseConfigurationImpact> impact(Store.Session tx,Preview p){var result=new ArrayList<>(impact(tx.load(),p.after()));var before=tx.serverSettings();
        if(p.settings()!=null)result.add(new EnterpriseConfigurationImpact(ControlEntityKind.SETTINGS,ServerSettingsService.ENTITY,before==null?EnterpriseConfigurationImpactOperation.CREATE:EnterpriseConfigurationImpactOperation.UPDATE,DirectoryService.digest(before==null?"":before),DirectoryService.digest(p.settings())));return List.copyOf(result);}
    private String recordDigest(Directory.Entry entry){if(entry==null)return DirectoryService.digest("");if(entry.id().kind()!=Ids.Kind.IDENTITY_BINDING)return DirectoryService.digest(entry.document());var node=(com.fasterxml.jackson.databind.node.ObjectNode)codec.value(entry.document());node.put("sessionsValidAfterUnixMs",0);return DirectoryService.digest(codec.json(node));}
    private List<String> groups(Directory before,Directory after,List<EnterpriseConfigurationImpact> impact){
        var groups=new TreeSet<String>();
        // Exact source and destination groups are retained independently; no dimension cross-product.
        for(var i:impact){if(i.kind()==ControlEntityKind.SETTINGS)continue;var kind=Ids.Kind.valueOf(i.kind().name());for(var d:List.of(before,after)){var e=d.entries().get(kind.id(i.entityId()));if(e==null)continue;
            if(GroupGraph.group(kind))groups.add(kind.name()+":"+e.id().value());
            else if(GroupGraph.individual(kind))for(var group:GroupGraph.memberships(d,codec,kind,e.id().value(),false))groups.add(GroupGraph.groupKind(kind).name()+":"+group);
            else if(kind==Ids.Kind.BINDING){var b=codec.model(e.document(),ControlExecutionBinding.class);groups.add("TOOL_GROUP:"+b.toolGroupId());groups.add("DEVICE_GROUP:"+b.deviceGroupId());}
            else if(kind==Ids.Kind.GRANT){var g=codec.model(e.document(),ControlAccessGrant.class);if(g.sourceType()!=EnterpriseSourceType.ROLE)groups.add(g.sourceType().name()+":"+g.sourceId());for(var k:List.of(Ids.Kind.TOOL_GROUP,Ids.Kind.DEVICE_GROUP)){var s=k==Ids.Kind.TOOL_GROUP?g.scope().toolGroups():g.scope().deviceGroups();for(var group:d.entries().values())if(group.id().kind()==k&&EnterpriseEvaluator.selected(s,group.id().value()))groups.add(k.name()+":"+group.id().value());}}
            else for(var group:d.entries().values())if(GroupGraph.group(group.id().kind()))groups.add(group.id().kind().name()+":"+group.id().value());
        }}return List.copyOf(groups);
    }
    private List<String> individuals(Directory before,Directory after,List<String> groups,List<EnterpriseConfigurationImpact> impact){var result=new TreeSet<String>();for(var i:impact)if(i.kind()!=ControlEntityKind.SETTINGS&&GroupGraph.individual(Ids.Kind.valueOf(i.kind().name())))result.add(i.kind().name()+":"+i.entityId());for(var group:groups){int at=group.indexOf(':');var kind=Ids.Kind.valueOf(group.substring(0,at));var id=group.substring(at+1);for(var d:List.of(before,after)){var e=d.entries().get(kind.id(id));if(e!=null)for(var member:GroupGraph.members(e,codec))result.add(GroupGraph.entityKind(kind).name()+":"+member);}}return List.copyOf(result);}
    private void scoped(Store.Session tx,DirectoryService.Actor actor,EnterpriseConfigurationChange c,String action,long now){actor.requireAdmin();var access=new ManagementAccess(codec);access.access(tx.load(),actor.userId());String permission=action.contains(".")?action:"configuration_change."+action;if(c.command().kind()==ControlEntityKind.SETTINGS)access.requireAll(tx.load(),actor.userId(),permission,now);for(var group:c.affectedGroups()){int at=group.indexOf(':');access.require(tx.load(),actor.userId(),permission,Ids.Kind.valueOf(group.substring(0,at)),group.substring(at+1),now);}}
    private void fresh(Store.Session tx,EnterpriseConfigurationChange c,long now){if(c.expiresAtUnixMs()<=now||c.directoryRevision()!=tx.load().revision()||c.authorizationEpoch()!=tx.enterprise().authorizationEpoch())throw Failure.conflict();}
    private DirectoryService.Actor actor(Store.Session tx,Ids.TenantId tenant,String user){var a=new ManagementAccess(codec).access(tx.load(),user);return new DirectoryService.Actor(tenant,DirectoryService.digest("configuration-review\n"+user),a.portalRole()!=UserRole.BASIC,a.portalRole()==UserRole.SUPER_ADMIN,user);}
    /** Super Admin changes apply atomically; scoped administrators use the reviewed workflow under the threshold in force at creation. */
    public Store.Reply mutate(DirectoryService.Actor actor,EnterpriseConfigurationCommand command,String key,String requestId){
        actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);codec.model(codec.json(command),EnterpriseConfigurationCommand.class);
        var direct=store.transaction(actor.tenant(),true,tx->{if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))return null;var result=execute(tx,actor,command,key,requestId,false,now(tx)).reply();tx.audit(actor.id(),"CONFIGURATION_DIRECT_SUPER_ADMIN",command.kind()+":"+command.entityId(),result.revision(),requestId,DirectoryService.digest(codec.json(command)));return result;});
        return direct==null?create(actor,command,key,requestId):direct;
    }
    public Store.Reply create(DirectoryService.Actor actor,EnterpriseConfigurationCommand command,String key,String requestId){actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);codec.model(codec.json(command),EnterpriseConfigurationCommand.class);
        String id="config-"+DirectoryService.digest(actor.id()+"\n"+key).substring(0,40),digest=DirectoryService.digest(codec.json(command));
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var existing=tx.enterprise().configuration(id);if(existing!=null){if(!existing.requestDigest().equals(digest))throw Failure.conflict();scoped(tx,actor,existing,"read",now);return reply(existing,202);}
            int required=ServerSettingsService.requiredReviews(tx,codec);
            // Zero reviews: the change applies at once under the actor's own scope, like the Super Admin path.
            if(required==0&&command.kind()!=ControlEntityKind.SETTINGS){var result=execute(tx,actor,command,key,requestId,false,now).reply();tx.audit(actor.id(),"CONFIGURATION_DIRECT_NO_REVIEW",command.kind()+":"+command.entityId(),result.revision(),requestId,digest);return result;}
            if(tx.enterprise().configurations("",1000).size()>=1000)throw Failure.conflict();var preview=execute(tx,actor,command,"preview-"+id,requestId,true,now);var changes=impact(tx,preview);if(changes.isEmpty())throw Failure.validation();var groups=groups(tx.load(),preview.after(),changes);
            var c=new EnterpriseConfigurationChange(id,actor.userId(),command,digest,tx.load().revision(),tx.enterprise().authorizationEpoch(),EnterpriseConfigurationState.DRAFT,1L,now,now+1800000,(long)required,changes,groups,individuals(tx.load(),preview.after(),groups,changes),List.of());scoped(tx,actor,c,"read",now);tx.enterprise().saveConfiguration(c,0);tx.audit(actor.id(),"CONFIGURATION_PROPOSE",id,c.revision(),requestId,digest);return reply(c,202);});
    }
    public Store.Reply get(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),false,tx->{var c=row(tx,id);scoped(tx,actor,c,"read",clock.millis());return reply(c,200);});}
    public Store.Reply page(DirectoryService.Actor actor,String after){actor.requireAdmin();if(after!=null)Ids.valid(after);return store.transaction(actor.tenant(),false,tx->{var result=new ArrayList<EnterpriseConfigurationChange>();for(var c:tx.enterprise().configurations(after==null?"":after,100))try{scoped(tx,actor,c,"read",clock.millis());result.add(c);}catch(Failure denied){if(denied.status()!=403)throw denied;}return new Store.Reply(200,codec.json(new EnterpriseConfigurationPage(result)),tx.load().revision());});}
    public Store.Reply transition(DirectoryService.Actor actor,String id,String document,String key,String requestId){actor.requireAdmin();Ids.valid(key);Ids.valid(requestId);var request=codec.model(document,EnterpriseConfigurationTransition.class);String hash=DirectoryService.digest(id+"\n"+codec.json(request));
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var c=row(tx,id);String action=request.action().name();scoped(tx,actor,c,Set.of("APPROVE","DENY","REVOKE").contains(action)?"configuration_change.approve":"read",now);var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;if(!java.util.Objects.equals(c.revision(),request.expectedRevision()))throw Failure.conflict();if(Set.of(EnterpriseConfigurationState.APPLIED,EnterpriseConfigurationState.CANCELLED,EnterpriseConfigurationState.DENIED,EnterpriseConfigurationState.REVOKED).contains(c.state()))throw Failure.conflict();
            var reviews=new ArrayList<>(c.reviews());var state=c.state();boolean settings=c.command().kind()==ControlEntityKind.SETTINGS,exceptional=!settings&&new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId());
            switch(request.action()){
                case CANCEL->{if(!actor.userId().equals(c.requesterUserId()))scoped(tx,actor,c,"configuration_change.approve",now);state=EnterpriseConfigurationState.CANCELLED;}
                case REVOKE->{if(!exceptional&&actor.userId().equals(c.requesterUserId()))throw ManagementAccess.denied();state=EnterpriseConfigurationState.REVOKED;}
                case SUBMIT->{fresh(tx,c,now);if(!exceptional&&!actor.userId().equals(c.requesterUserId())||state!=EnterpriseConfigurationState.DRAFT)throw ManagementAccess.denied();execute(tx,actor,c.command(),"preview-"+id,requestId,true,c.createdAtUnixMs());state=EnterpriseConfigurationState.PENDING;}
                case APPROVE,DENY->{fresh(tx,c,now);if(!exceptional&&actor.userId().equals(c.requesterUserId())||state!=EnterpriseConfigurationState.PENDING||reviews.stream().anyMatch(r->r.reviewerUserId().equals(actor.userId())))throw ManagementAccess.denied();execute(tx,actor,c.command(),"preview-"+id,requestId,true,c.createdAtUnixMs());reviews.add(new EnterpriseConfigurationReview(actor.userId(),request.action()==EnterpriseConfigurationAction.APPROVE?EnterpriseReviewDecision.APPROVE:EnterpriseReviewDecision.DENY,now));state=request.action()==EnterpriseConfigurationAction.DENY?EnterpriseConfigurationState.DENIED:exceptional||reviews.size()>=c.requiredReviews()?EnterpriseConfigurationState.APPROVED:EnterpriseConfigurationState.PENDING;}
                case APPLY->{fresh(tx,c,now);if(state!=EnterpriseConfigurationState.APPROVED||!exceptional&&!actor.userId().equals(c.requesterUserId()))throw ManagementAccess.denied();boolean superReview=!settings&&reviews.stream().anyMatch(r->new ManagementAccess(codec).superAdministrator(tx.load(),r.reviewerUserId()));if(!superReview&&(reviews.size()<c.requiredReviews()||reviews.stream().anyMatch(r->r.reviewerUserId().equals(c.requesterUserId()))))throw ManagementAccess.denied();for(var review:reviews){var reviewer=actor(tx,actor.tenant(),review.reviewerUserId());scoped(tx,reviewer,c,"configuration_change.approve",now);execute(tx,reviewer,c.command(),"preview-"+id,requestId,true,now);}var p=execute(tx,actor,c.command(),"preview-"+id,requestId,true,now);if(!impact(tx,p).equals(c.impact()))throw Failure.conflict();execute(tx,actor,c.command(),"apply-"+id,requestId,false,now);state=EnterpriseConfigurationState.APPLIED;}
            }
            var next=new EnterpriseConfigurationChange(c.id(),c.requesterUserId(),c.command(),c.requestDigest(),c.directoryRevision(),c.authorizationEpoch(),state,c.revision()+1,c.createdAtUnixMs(),c.expiresAtUnixMs(),c.requiredReviews(),c.impact(),c.affectedGroups(),c.affectedIndividuals(),reviews);tx.enterprise().saveConfiguration(next,c.revision());tx.audit(actor.id(),"CONFIGURATION_"+action+(exceptional?"_SUPER_ADMIN":""),id,next.revision(),requestId,c.requestDigest());var reply=reply(next,200);tx.remember(actor.id(),key,hash,reply);return reply;});
    }
}
