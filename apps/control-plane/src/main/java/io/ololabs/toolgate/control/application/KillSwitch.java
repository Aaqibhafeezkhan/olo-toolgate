// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.*;
import java.util.*;
import java.util.function.Supplier;

/** Reviewed revocation shares event, epoch, cancellation, closure, replay and audit commit boundaries. */
public final class KillSwitch {
    private final Store store;private final Codec codec;private final EnterpriseOperations operations;private final Supplier<KillCrypto> signing;
    public KillSwitch(Store store,Codec codec,EnterpriseOperations operations,Supplier<KillCrypto> signing){
        this.store=store;this.codec=codec;this.operations=operations;this.signing=signing;
    }
    private ObjectNode node(Object value){return (ObjectNode)codec.value(codec.json(value));}
    private void human(Store.Session tx,DirectoryService.Actor actor,String permission,long now){
        actor.requireAdmin();new ManagementAccess(codec).requireAll(tx.load(),actor.userId(),permission,now);
    }
    private KillStore.Proposal proposal(Store.Session tx,String id){
        var value=tx.kills().proposal(Ids.valid(id));if(value==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Kill proposal not found");return value;
    }
    private void intent(Store.Session tx,String kind,String document){
        if(kind.equals("KILL"))KillPolicy.validate(codec.model(document,KillRequest.class).scope());
        else if(kind.equals("RECOVERY")){
            var request=codec.model(document,RecoveryRequest.class);
            var active=new HashSet<String>();tx.kills().effective().forEach(e->active.add(e.id()));
            if(request.supersedes().stream().anyMatch(id->!active.contains(id)))throw Failure.conflict();
        }else throw Failure.validation();
    }
    public Store.Reply propose(DirectoryService.Actor actor,String kind,String document,String key){
        Ids.valid(key);if(!Set.of("KILL","RECOVERY").contains(kind))throw Failure.validation();
        String canonical=codec.json(codec.value(document)),digest=DirectoryService.digest(kind+"\n"+canonical);
        return store.transaction(actor.tenant(),true,tx->{
            long now=operations.clock(tx);human(tx,actor,kind.equals("RECOVERY")?"kill.recover":"kill.create",now);
            var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            intent(tx,kind,canonical);
            var value=new KillStore.Proposal("kill-"+UUID.randomUUID(),actor.userId(),kind,tx.enterprise().authorizationEpoch(),
                DirectoryService.digest(canonical),canonical,now,Math.addExact(now,600000));
            tx.kills().propose(value);tx.audit(actor.id(),"KILL_PROPOSE",value.id(),1,key,digest);
            var response=reply(view(tx,value,now),202);tx.remember(actor.id(),key,digest,response);return response;
        });
    }
    private KillProposal view(Store.Session tx,KillStore.Proposal proposal,long now){
        var reviews=tx.kills().reviews(proposal.id());var closure=tx.kills().closure(proposal.id());var request=(JsonNode)codec.value(proposal.document());
        var state=closure==null?(proposal.expiresAt()<=now?KillProposalState.EXPIRED:KillProposalState.PENDING):KillProposalState.valueOf(closure.state());
        var supersedes=new ArrayList<String>();request.path("supersedes").forEach(v->supersedes.add(v.asText()));
        return new KillProposal(proposal.id(),proposal.requester(),KillProposalKind.valueOf(proposal.kind()),state,
            1L+reviews.size()+(closure==null?0:1),proposal.epoch(),proposal.createdAt(),proposal.expiresAt(),
            request.has("scope")?codec.model(codec.json(request.path("scope")),KillScope.class):null,
            request.has("mode")?KillMode.valueOf(request.path("mode").asText()):null,
            request.path("reason").asText(),supersedes,reviews,closure==null?null:closure.event());
    }
    private Store.Reply reply(Object value,int status){return new Store.Reply(status,codec.json(value),value instanceof KillProposal p?p.revision():0);}
    public Store.Reply read(DirectoryService.Actor actor,String id){
        return store.transaction(actor.tenant(),true,tx->{long now=operations.clock(tx);human(tx,actor,"kill.read",now);return reply(view(tx,proposal(tx,id),now),200);});
    }
    public Store.Reply proposals(DirectoryService.Actor actor,String after,int limit){
        if(after!=null&&!after.isEmpty())Ids.valid(after);
        return store.transaction(actor.tenant(),true,tx->{long now=operations.clock(tx);human(tx,actor,"kill.read",now);
            return reply(tx.kills().proposals(after==null?"":after,limit).stream().map(p->view(tx,p,now)).toList(),200);});
    }
    private void reviewer(Store.Session tx,String user,boolean recovery,long now){
        var access=new ManagementAccess(codec);access.requireAll(tx.load(),user,"kill.approve",now);
        if(recovery)access.requireAll(tx.load(),user,"kill.recover",now);
    }
    public Store.Reply decide(DirectoryService.Actor actor,String id,String document,String key){
        Ids.valid(key);var decision=codec.model(document,KillProposalDecision.class);
        String digest=DirectoryService.digest(id+"\n"+codec.json(decision));
        return store.transaction(actor.tenant(),true,tx->{
            long now=operations.clock(tx);actor.requireAdmin();var proposal=proposal(tx,id);
            reviewer(tx,actor.userId(),proposal.kind().equals("RECOVERY"),now);
            var replay=tx.replay(actor.id(),key,digest);if(replay!=null)return replay;
            var current=view(tx,proposal,now);
            if(current.state()!=KillProposalState.PENDING||!Objects.equals(current.revision(),decision.expectedRevision())
                ||proposal.epoch()!=tx.enterprise().authorizationEpoch())throw Failure.conflict();
            if(proposal.requester().equals(actor.userId())||current.reviews().stream().anyMatch(r->r.reviewerUserId().equals(actor.userId())))throw ManagementAccess.denied();
            intent(tx,proposal.kind(),proposal.document());
            tx.kills().review(id,new KillReview(actor.userId(),decision.approve(),now));
            var reviews=tx.kills().reviews(id);
            if(!decision.approve())tx.kills().close(id,new KillStore.Closure("REJECTED",null,now));
            else if(reviews.size()==2){
                new ManagementAccess(codec).requireAll(tx.load(),proposal.requester(),proposal.kind().equals("RECOVERY")?"kill.recover":"kill.create",now);
                for(var review:reviews){if(!review.approved()||proposal.requester().equals(review.reviewerUserId()))throw ManagementAccess.denied();reviewer(tx,review.reviewerUserId(),proposal.kind().equals("RECOVERY"),now);}
                String event=commit(tx,actor.tenant(),proposal.kind(),proposal.document(),reviews.stream().map(KillReview::reviewerUserId).toList(),"control",actor.id(),now);
                tx.kills().close(id,new KillStore.Closure("APPLIED",event,now));
                // Refuse the transaction rather than publishing a state that cannot be delivered within the wire bound.
                push(tx,actor.tenant());
                if(operations.clock(tx)>=proposal.expiresAt())throw Failure.conflict();
            }
            tx.audit(actor.id(),decision.approve()?"KILL_REVIEW_APPROVE":"KILL_REVIEW_REJECT",id,current.revision()+1,key,digest);
            var response=reply(view(tx,proposal,now),200);tx.remember(actor.id(),key,digest,response);return response;
        });
    }
    /** Only the dedicated operator TLS adapter supplies the accepted peer fingerprint. */
    public Store.Reply breakGlass(Ids.TenantId tenant,String peerFingerprint,String requestId,String document,OperatorApprovals approvals){
        Ids.valid(requestId);if(peerFingerprint==null||!peerFingerprint.matches("[a-f0-9]{64}"))throw ManagementAccess.denied();
        String digest=DirectoryService.digest(document);
        try{
            return store.transaction(tenant,true,tx->{
                long now=operations.clock(tx);var request=codec.model(document,BreakGlassRequest.class);
                var verified=approvals.verify(tenant,requestId,request,now);
                if(tx.kills().emergency(requestId)!=null)throw Failure.conflict();
                String intent=codec.json(new KillRequest(request.scope(),request.mode(),request.reason()));
                String event=commit(tx,tenant,"KILL",intent,List.of(),"control-breakglass",peerFingerprint,now);
                tx.kills().recordEmergency(new KillStore.EmergencyRequest(requestId,digest,event,peerFingerprint,verified.claims().notAfterUnixMs(),now));
                var value=push(tx,tenant);
                if(operations.clock(tx)>=verified.claims().notAfterUnixMs())throw ManagementAccess.denied();
                tx.audit(peerFingerprint,"KILL_BREAKGLASS_ACCEPT",event,value.authorizationEpoch(),requestId,
                    DirectoryService.digest(codec.json(verified.operators())));
                return new Store.Reply(201,codec.json(codec.model(tx.kills().byId(event).document(),KillEvent.class)),value.authorizationEpoch());
            });
        }catch(Failure refusal){
            try{store.transaction(tenant,true,tx->{tx.audit(peerFingerprint,"KILL_BREAKGLASS_REFUSE","kill",tx.enterprise().authorizationEpoch(),requestId,digest);return null;});}
            catch(Failure unavailable){/* Listener additionally records bounded refusal without approval tokens. */}
            throw refusal;
        }
    }
    String commit(Store.Session tx,Ids.TenantId tenant,String kind,String document,List<String> reviewers,String issuer,String actor,long now){
        intent(tx,kind,document);long counter=tx.kills().nextCounter(issuer);String id=issuer+":"+counter;
        var directory=tx.load();tx.save(directory,new Directory(Math.addExact(directory.revision(),1),directory.entries()));
        long epoch=tx.enterprise().authorizationEpoch();ObjectNode unsigned;KillScope scope=null;KillMode mode=null;
        if(kind.equals("KILL")){
            var request=codec.model(document,KillRequest.class);scope=request.scope();mode=request.mode();
            unsigned=node(Map.of("eventId",id,"issuer",issuer,"scope",scope,"mode",mode,"reason",request.reason(),"issuedAtUnixMs",now,"tenantId",tenant.value(),"authorizationEpoch",epoch));
        }else{
            if(!issuer.equals("control")||reviewers.size()!=2||new HashSet<>(reviewers).size()!=2)throw ManagementAccess.denied();
            var request=codec.model(document,RecoveryRequest.class);
            unsigned=node(Map.of("eventId",id,"issuer","control","supersedes",request.supersedes(),"reviewers",reviewers,"issuedAtUnixMs",now,"tenantId",tenant.value(),"authorizationEpoch",epoch));
        }
        unsigned.put("signature",signing.get().signEvent(unsigned));String signed=codec.json(unsigned);
        tx.kills().append(new KillStore.Event(id,issuer,counter,kind,scope==null?null:scope.kind().name(),scope==null?null:scope.value(),mode==null?null:mode.name(),epoch,now,signed));
        if(kind.equals("RECOVERY"))for(var kill:codec.model(document,RecoveryRequest.class).supersedes())tx.kills().supersede(kill,id);
        else cancelStored(tx,scope,mode,actor,id,now);
        tx.audit(actor,"KILL_EVENT_"+kind,id,epoch,id,DirectoryService.digest(signed));
        return id;
    }
    private void cancelStored(Store.Session tx,KillScope scope,KillMode mode,String actor,String event,long now){
        String after="";
        for(int page=0;page<=1000;page++){
            var values=tx.enterprise().invocations(after,100);if(values.isEmpty())return;
            for(var invocation:values){
                after=invocation.id();
                if(Set.of(EnterpriseInvocationState.SUCCEEDED,EnterpriseInvocationState.FAILED,EnterpriseInvocationState.PARTIAL,
                    EnterpriseInvocationState.OUTCOME_UNKNOWN,EnterpriseInvocationState.CANCELLED,EnterpriseInvocationState.EXPIRED).contains(invocation.state()))continue;
                var pinned=invocation.reservedNonce()==null?null:tx.kills().scope(invocation.reservedNonce());
                var subject=pinned==null?KillEnforcement.resolve(tx,codec,invocation.evaluation()):pinned.subject();
                if(KillPolicy.blocks(mode,scope,subject,invocation.state()==EnterpriseInvocationState.EXECUTING))
                    operations.cancelForKill(tx,invocation,now,actor,event);
            }
            if(values.size()<100)return;
        }
        throw Failure.unavailable();
    }
    KillPush push(Store.Session tx,Ids.TenantId tenant){
        var kills=new ArrayList<KillEvent>();var recoveries=new ArrayList<RecoveryEvent>();
        var active=new HashSet<String>();tx.kills().effective().forEach(e->active.add(e.id()));
        var crypto=signing.get();
        for(var event:tx.kills().visible()){
            var signed=node(codec.value(event.document()));String signature=signed.remove("signature").asText();
            if(!signed.equals(crypto.verifyEvent(signature)))throw Failure.unavailable();
            if(event.kind().equals("KILL")){if(active.contains(event.id()))kills.add(codec.model(event.document(),KillEvent.class));}
            else recoveries.add(codec.model(event.document(),RecoveryEvent.class));
        }
        var unsigned=node(Map.of("tenantId",tenant.value(),"authorizationEpoch",tx.enterprise().authorizationEpoch(),"kills",kills,"recoveries",recoveries));
        String signature=crypto.signPush(unsigned);unsigned.put("signature",signature);
        return codec.model(codec.json(unsigned),KillPush.class);
    }
    private static final long RETENTION_MS=30L*24*60*60*1000;
    /** Archive only superseded facts; missing acknowledgements never remove an active kill. */
    private void archive(Store.Session tx,long now){
        var components=tx.kills().components();var acknowledgements=new HashMap<String,Long>();
        for(var ack:tx.kills().acknowledgements())acknowledgements.put(ack.kind()+"\n"+ack.component(),ack.epoch());
        for(var event:tx.kills().visible()){
            if(!event.kind().equals("RECOVERY"))continue;
            boolean all=components.stream().allMatch(component->
                acknowledgements.getOrDefault(component.kind()+"\n"+component.id(),-1L)>=event.epoch());
            if(!all&&now<Math.addExact(event.issuedAt(),RETENTION_MS))continue;
            var signed=node(codec.value(event.document()));String signature=signed.remove("signature").asText();
            if(!signed.equals(signing.get().verifyEvent(signature)))throw Failure.unavailable();
            var recovery=codec.model(event.document(),RecoveryEvent.class);
            for(var id:recovery.supersedes())tx.kills().archive(id,now);
            tx.kills().archive(event.id(),now);
            tx.audit(DirectoryService.digest("kill-retention"),"KILL_ARCHIVE",event.id(),event.epoch(),event.id(),DirectoryService.digest(event.document()));
        }
    }
    public void sweep(Ids.TenantId tenant){
        store.transaction(tenant,true,tx->{archive(tx,operations.clock(tx));return null;});
    }
    public Store.Reply overview(DirectoryService.Actor actor){
        return store.transaction(actor.tenant(),true,tx->{
            long now=operations.clock(tx);human(tx,actor,"kill.read",now);archive(tx,now);
            var acknowledgements=new HashMap<String,KillStore.Acknowledgement>();
            for(var ack:tx.kills().acknowledgements())acknowledgements.put(ack.kind()+"\n"+ack.component(),ack);
            var components=new ArrayList<KillComponentDelivery>();
            for(var component:tx.kills().components()){
                var ack=acknowledgements.get(component.kind()+"\n"+component.id());
                components.add(new KillComponentDelivery(KillComponentKind.valueOf(component.kind()),component.id(),
                    ack==null?null:ack.epoch(),ack==null?null:ack.at(),ack==null?null:KillDeliveryPath.valueOf(ack.path())));
            }
            var value=new KillOverview(push(tx,actor.tenant()),components,tx.kills().retainedCount(),tx.kills().archivedCount(),now);
            return new Store.Reply(200,codec.json(value),value.state().authorizationEpoch());
        });
    }
    public Store.Reply gatewayKeys(DirectoryService.Actor actor){
        if(actor.admin()||actor.userId()!=null)throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{
            tx.kills().registerGateway(actor.id(),operations.clock(tx));
            long epoch=tx.enterprise().authorizationEpoch();
            return new Store.Reply(200,codec.json(new KillTrustDocument(actor.tenant().value(),epoch,signing.get().publicKeys())),epoch);
        });
    }
    public Store.Reply gatewayState(DirectoryService.Actor actor){
        if(actor.admin()||actor.userId()!=null)throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{
            tx.kills().registerGateway(actor.id(),operations.clock(tx));archive(tx,operations.clock(tx));
            var value=push(tx,actor.tenant());
            return new Store.Reply(200,codec.json(value),value.authorizationEpoch());
        });
    }
    public Store.Reply gatewayAcknowledge(DirectoryService.Actor actor,String document){
        if(actor.admin()||actor.userId()!=null)throw ManagementAccess.denied();
        var request=codec.model(document,KillAcknowledgement.class);if(request.deliveryPath()!=KillDeliveryPath.POLL)throw Failure.validation();
        return store.transaction(actor.tenant(),true,tx->{
            if(!tx.kills().registeredGateway(actor.id()))throw ManagementAccess.denied();
            long epoch=tx.enterprise().authorizationEpoch(),now=operations.clock(tx);
            if(request.authorizationEpoch()>epoch)throw Failure.conflict();
            tx.kills().acknowledge(new KillStore.Acknowledgement("GATEWAY",actor.id(),request.authorizationEpoch(),now,"POLL"));
            tx.audit(actor.id(),"KILL_ACK",actor.id(),request.authorizationEpoch(),"kill-ack-"+request.authorizationEpoch(),DirectoryService.digest(codec.json(request)));
            archive(tx,now);return new Store.Reply(200,codec.json(request),epoch);
        });
    }
    public Store.Reply state(DirectoryService.Actor actor){
        return store.transaction(actor.tenant(),true,tx->{long now=operations.clock(tx);human(tx,actor,"kill.read",now);archive(tx,now);var value=push(tx,actor.tenant());return new Store.Reply(200,codec.json(value),value.authorizationEpoch());});
    }
    private String host(Store.Session tx,Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        var device=certificate.apply(tx);
        if(device==null||!tenant.value().equals(device.tenantId())||tx.packages().toolHost(device.deviceId())==null)throw ManagementAccess.denied();
        return device.deviceId();
    }
    private String nativeEndpoint(Store.Session tx,Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        var device=certificate.apply(tx);
        if(device==null||!tenant.value().equals(device.tenantId())||tx.packages().toolHost(device.deviceId())!=null)throw ManagementAccess.denied();
        return device.deviceId();
    }
    public Store.Reply nativeKeys(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        return store.transaction(tenant,false,tx->{nativeEndpoint(tx,tenant,certificate);long epoch=tx.enterprise().authorizationEpoch();
            return new Store.Reply(200,codec.json(new KillTrustDocument(tenant.value(),epoch,signing.get().publicKeys())),epoch);});
    }
    public Store.Reply nativeState(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        return store.transaction(tenant,false,tx->{nativeEndpoint(tx,tenant,certificate);var value=push(tx,tenant);return new Store.Reply(200,codec.json(value),value.authorizationEpoch());});
    }
    public Store.Reply nativeAcknowledge(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate,String document){
        var request=codec.model(document,KillAcknowledgement.class);
        if(request.deliveryPath()!=KillDeliveryPath.CHECK_IN)throw Failure.validation();
        return store.transaction(tenant,true,tx->{
            String device=nativeEndpoint(tx,tenant,certificate);long epoch=tx.enterprise().authorizationEpoch(),now=operations.clock(tx);
            if(request.authorizationEpoch()>epoch)throw Failure.conflict();
            tx.kills().acknowledge(new KillStore.Acknowledgement("ENDPOINT",device,request.authorizationEpoch(),now,"CHECK_IN"));
            tx.audit(DirectoryService.digest(device),"KILL_ACK",device,request.authorizationEpoch(),"kill-ack-"+request.authorizationEpoch(),DirectoryService.digest(codec.json(request)));
            archive(tx,now);
            return new Store.Reply(200,codec.json(request),epoch);
        });
    }
    public Store.Reply hostKeys(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        return store.transaction(tenant,false,tx->{host(tx,tenant,certificate);long epoch=tx.enterprise().authorizationEpoch();return new Store.Reply(200,codec.json(new KillTrustDocument(tenant.value(),epoch,signing.get().publicKeys())),epoch);});
    }
    public Store.Reply hostState(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate){
        return store.transaction(tenant,false,tx->{host(tx,tenant,certificate);var value=push(tx,tenant);return new Store.Reply(200,codec.json(value),value.authorizationEpoch());});
    }
    private static final java.util.concurrent.Semaphore POLLS=new java.util.concurrent.Semaphore(256);
    private static final java.util.Set<String> ACTIVE_POLLS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** No database connection, writer lock or tenant transaction is held while waiting. */
    public Store.Reply poll(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate,long after,long waitMs){
        if(after<0||after>9007199254740991L||waitMs<0||waitMs>20000)throw Failure.validation();
        String device=store.transaction(tenant,false,tx->host(tx,tenant,certificate)),slot=tenant.value()+":"+device;
        if(!POLLS.tryAcquire())throw Failure.unavailable();
        if(!ACTIVE_POLLS.add(slot)){POLLS.release();throw Failure.conflict();}
        try{
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMs);
            while(true){
                long epoch=store.transaction(tenant,false,tx->{if(!device.equals(host(tx,tenant,certificate)))throw ManagementAccess.denied();return tx.enterprise().authorizationEpoch();});
                if(epoch<after)throw Failure.conflict();
                if(epoch>after||System.nanoTime()>=until)return hostState(tenant,certificate);
                try{long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0,until-System.nanoTime()));Thread.sleep(Math.min(100,Math.max(1,remaining)));}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw Failure.unavailable();}
            }
        }finally{ACTIVE_POLLS.remove(slot);POLLS.release();}
    }
    public Store.Reply acknowledge(Ids.TenantId tenant,java.util.function.Function<Store.Session,EndpointDeviceRecord> certificate,String document){
        var request=codec.model(document,KillAcknowledgement.class);
        if(request.deliveryPath()==KillDeliveryPath.CHECK_IN)throw Failure.validation();
        return store.transaction(tenant,true,tx->{String device=host(tx,tenant,certificate);long epoch=tx.enterprise().authorizationEpoch(),now=operations.clock(tx);
            if(request.authorizationEpoch()>epoch)throw Failure.conflict();
            tx.kills().acknowledge(new KillStore.Acknowledgement("TOOL_HOST",device,request.authorizationEpoch(),now,request.deliveryPath().name()));
            tx.audit(DirectoryService.digest(device),"KILL_ACK",device,request.authorizationEpoch(),"kill-ack-"+request.authorizationEpoch(),DirectoryService.digest(codec.json(request)));
            archive(tx,now);
            return new Store.Reply(200,codec.json(request),epoch);
        });
    }

}
