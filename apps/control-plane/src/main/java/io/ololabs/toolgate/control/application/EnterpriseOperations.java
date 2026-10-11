// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.time.Clock;
import java.util.*;

/** Online effect authority. Submission, approval, reservation and consumption share current group semantics.
 * Invocation IDs bind exact arguments/resources/versions. A consumed capability is never issued again.
 * Database commit proves consumption, not exactly-once completion of an external side effect.
 */
public final class EnterpriseOperations {
    private final Store store;private final Codec codec;private final EffectSigner signer;private final Clock clock;
    private final String issuer,audience;private final long lifetime;private final int capacity;
    public EnterpriseOperations(Store store,Codec codec,EffectSigner signer,Clock clock,String issuer,String audience,long lifetime,int capacity){
        if(lifetime<1000||lifetime>300000||capacity<1||capacity>10000)throw new IllegalArgumentException("Invalid operation limits");
        this.store=store;this.codec=codec;this.signer=signer;this.clock=clock;this.issuer=Ids.valid(issuer);this.audience=Ids.valid(audience);this.lifetime=lifetime;this.capacity=capacity;
    }
    private <T> T authoritative(Ids.TenantId tenant,String actor,String requestId,String digest,java.util.function.Function<Store.Session,T> work){
        long[] observed={-1};
        try{return store.transaction(tenant,true,tx->{long current=clock.millis();observed[0]=Math.max(current,tx.approvalClock(current));if(observed[0]>current)throw Failure.unavailable();return work.apply(tx);});}
        catch(Failure rejected){
            // The failed operation rolls back first. A separate redacted denial event never commits partial effects.
            store.transaction(tenant,true,tx->{if(observed[0]>=0)tx.approvalClock(observed[0]);tx.audit(actor,"ACCESS_DENIED","operation",tx.load().revision(),requestId,digest);return null;});throw rejected;
        }
    }
    private long now(Store.Session tx){long now=clock.millis();if(now<0||tx.approvalClock(now)>now)throw Failure.unavailable();return now;}
    private static void gateway(DirectoryService.Actor actor){if(actor.admin()||actor.userId()!=null)throw ManagementAccess.denied();}
    private Store.Reply reply(Object value,long revision){return new Store.Reply(200,codec.json(value),revision);}
    private static String approvalId(String invocation){return "operation-"+DirectoryService.digest(invocation).substring(0,40);}
    private String requestDigest(EnterpriseEvaluation e,String downstream){
        return DirectoryService.digest(codec.json(new EnterpriseEvaluation(e.context(),e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),0L,0L,true,e.amountMinorUnits(),e.operation()))+"\n"+(downstream==null?"":downstream));
    }
    private static EnterpriseEvaluation current(EnterpriseEvaluation e,Directory directory,long now){return new EnterpriseEvaluation(e.context(),e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),now,directory.revision(),true,e.amountMinorUnits(),e.operation());}
    private EnterpriseDecision decide(Ids.TenantId tenant,Store.Session tx,EnterpriseEvaluation e,long now,boolean existing){return decide(tenant,tx,e,now,existing,new java.util.TreeSet<>());}
    private EnterpriseDecision decide(Ids.TenantId tenant,Store.Session tx,EnterpriseEvaluation e,long now,boolean existing,java.util.Set<String> budgets){
        var usage=new java.util.HashMap<String,Long>();
        var d=new EnterpriseEvaluator(codec).evaluateWithQuotas(tenant,tx.load(),current(e,tx.load(),now),budget->{budgets.add(budget);return usage.computeIfAbsent(budget,id->tx.enterprise().budgetUsage(id,Math.max(0,now-60000),existing?e.context().requestId():null));});
        if(d.decision()==Decision.BLOCK)throw ManagementAccess.denied();return d;
    }
    private EnterpriseInvocation invocation(Store.Session tx,String id){var i=tx.enterprise().invocation(Ids.valid(id));if(i==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Invocation not found");
        long time=clock.millis();if(i.expiresAtUnixMs()<=time&&Set.of(EnterpriseInvocationState.PENDING_APPROVAL,EnterpriseInvocationState.QUEUED,EnterpriseInvocationState.RESERVED,EnterpriseInvocationState.EXECUTING).contains(i.state())){
            var state=i.state()==EnterpriseInvocationState.EXECUTING?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.EXPIRED;
            var next=changed(i,state,i.reservedNonce(),i.completedResources(),i.resultDigest());tx.enterprise().saveInvocation(next,time,i.revision());tx.audit("effect-watchdog","INVOCATION_"+state.name(),"invocation:"+id,next.revision(),i.evaluation().context().requestId(),i.requestDigest());i=next;
        }return i;}
    private EnterpriseInvocation changed(EnterpriseInvocation i,EnterpriseInvocationState state,String nonce,List<ResourceDescriptor> completed,String result){return new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),state,i.revision()+1,i.authorizationEpoch(),nonce,i.expiresAtUnixMs(),completed,i.downstreamIdempotencyKey(),result,i.diagnosticId());}
    private void audit(Store.Session tx,DirectoryService.Actor actor,String operation,EnterpriseInvocation i){tx.audit(actor.id(),operation,"invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest());}
    private void fresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation i,long now){
        if(i.expiresAtUnixMs()<=now||i.authorizationEpoch()!=tx.enterprise().authorizationEpoch())throw ManagementAccess.denied();
        var decision=decide(tenant,tx,i.evaluation(),now,true);
        if(decision.decision()==Decision.ASK){var approval=tx.enterprise().approval(approvalId(i.id()));
            if(approval==null||approval.approvalType()!=EnterpriseApprovalType.OPERATION||approval.state()!=EnterpriseApprovalState.APPROVED||approval.expiresAtUnixMs()<=now
                ||!approval.requestDigest().equals(i.requestDigest())||!java.util.Objects.equals(approval.authorizationEpoch(),i.authorizationEpoch())||!approval.obligationIds().equals(decision.obligations()))throw ManagementAccess.denied();
            // Reviewer privilege and membership remain live obligations, including bounded/JIT roles.
            for(var review:approval.reviews())if(review.decision()==EnterpriseReviewDecision.APPROVE)reviewer(tx,review.reviewerUserId(),i,review.obligationId(),now);
        }
    }
    public Store.Reply submit(DirectoryService.Actor actor,String document){gateway(actor);var request=codec.model(document,EnterpriseInvocationRequest.class);if(request.context().mode()==EnterpriseRequestMode.HUMAN)throw ManagementAccess.denied();return submitVerified(actor,request);}
    public Store.Reply submitHuman(DirectoryService.Actor actor,EnterpriseInvocationRequest request){if(actor.userId()==null||request.context().mode()!=EnterpriseRequestMode.HUMAN||!actor.userId().equals(request.context().userId()))throw ManagementAccess.denied();return submitVerified(actor,request);}
    private Store.Reply submitVerified(DirectoryService.Actor actor,EnterpriseInvocationRequest request){String document=codec.json(request);
        return authoritative(actor.tenant(),actor.id(),request.context().requestId(),DirectoryService.digest(document),tx->{long now=now(tx);var e=new RuntimeAccess(codec).evaluation(actor.tenant(),tx.load(),request.context(),request.request(),request.toolDigest(),request.packageDigest(),now);
            String digest=requestDigest(e,request.downstreamIdempotencyKey());var previous=tx.enterprise().invocation(e.context().requestId());
            if(previous!=null){if(!previous.requestDigest().equals(digest))throw Failure.conflict();decide(actor.tenant(),tx,previous.evaluation(),now,true);return reply(previous,previous.revision());}
            if(tx.enterprise().invocationsSince(Math.max(0,now-lifetime))>=capacity)throw Failure.conflict();var budgets=new java.util.TreeSet<String>();var decision=decide(actor.tenant(),tx,e,now,false,budgets);long epoch=tx.enterprise().authorizationEpoch();
            var state=decision.decision()==Decision.ASK?EnterpriseInvocationState.PENDING_APPROVAL:EnterpriseInvocationState.QUEUED;
            var i=new EnterpriseInvocation(e.context().requestId(),digest,e,state,1L,epoch,null,now+lifetime,List.of(),request.downstreamIdempotencyKey(),null,decision.diagnosticId());
            for(var budget:budgets)tx.enterprise().reserveBudget(budget,i.id(),now);tx.enterprise().saveInvocation(i,now,0);if(state==EnterpriseInvocationState.PENDING_APPROVAL)tx.enterprise().saveApproval(new EnterpriseApproval(approvalId(i.id()),EnterpriseApprovalType.OPERATION,i.id(),digest,epoch,tx.load().revision(),decision.obligations(),List.of(),EnterpriseApprovalState.PENDING,1L,i.expiresAtUnixMs()),0);
            audit(tx,actor,"INVOCATION_SUBMIT",i);return reply(i,i.revision());});
    }
    public Store.Reply reserve(DirectoryService.Actor actor,String document){gateway(actor);var request=codec.model(document,EnterpriseReservationRequest.class);
        return authoritative(actor.tenant(),actor.id(),request.invocationId(),DirectoryService.digest(document),tx->{var reservation=reserveInSession(actor.tenant(),tx,request,actor.id());return reply(reservation,reservation.invocation().revision());});
    }
    EnterpriseReservation reserveInSession(Ids.TenantId tenant,Store.Session tx,EnterpriseReservationRequest request,String actorId){
        long now=now(tx);var i=invocation(tx,request.invocationId());if(!java.util.Objects.equals(i.revision(),request.expectedRevision()))throw Failure.conflict();fresh(tenant,tx,i,now);
            if(i.state()==EnterpriseInvocationState.RESERVED){var old=tx.enterprise().nonce(i.reservedNonce());if(old==null||old.consumedAt()!=null||old.expiresAt()>now)throw Failure.conflict();}
            else if(i.state()!=EnterpriseInvocationState.QUEUED)throw Failure.conflict();
            var decision=decide(tenant,tx,i.evaluation(),now,true);String nonce=UUID.randomUUID().toString();long expires=Math.min(i.expiresAtUnixMs(),Math.min(now+10000,decision.validUntilUnixMs()));
            String evalDigest=DirectoryService.digest(codec.json(i.evaluation()));var e=i.evaluation();var approval=tx.enterprise().approval(approvalId(i.id()));var ids=approval==null?List.<String>of():List.of(approval.id());
            var claims=new EnterprisePermitClaims(issuer,audience,nonce,i.id(),i.requestDigest(),evalDigest,i.authorizationEpoch(),tx.load().revision(),now,expires,e.toolDigest(),e.packageDigest(),e.context().bindingId(),e.context().deviceId(),ids);
            var permit=signer.sign(claims);var next=changed(i,EnterpriseInvocationState.RESERVED,nonce,i.completedResources(),null);tx.enterprise().reserveNonce(new EnterpriseStore.Nonce(nonce,i.id(),evalDigest,i.authorizationEpoch(),expires,null));tx.enterprise().saveInvocation(next,now,i.revision());tx.audit(actorId,"PERMIT_RESERVE","invocation:"+next.id(),next.revision(),e.context().requestId(),next.requestDigest());return new EnterpriseReservation(next,permit);
    }
    void requireFresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation invocation){fresh(tenant,tx,invocation,now(tx));}
    EnterpriseInvocation secretAuthority(Ids.TenantId tenant,Store.Session tx,String device,String invocationId,String name){var i=invocation(tx,invocationId);if(i.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(i.evaluation().context().deviceId())||i.evaluation().resources().stream().noneMatch(r->r.kind()==ResourceKind.CUSTOM&&r.locator().equals("secret://"+name)))throw ManagementAccess.denied();fresh(tenant,tx,i,now(tx));return i;}
    void checkpoint(Ids.TenantId tenant,Store.Session tx,String deviceId,EnterpriseInvocation invocation,AuthorizationRequest request){
        if(invocation.state()!=EnterpriseInvocationState.EXECUTING||!deviceId.equals(invocation.evaluation().context().deviceId())
            ||!request.toolId().equals(invocation.evaluation().toolId())||!request.action().equals(invocation.evaluation().action())
            ||!DirectoryService.digest(codec.json(request.arguments())).equals(invocation.evaluation().argumentsDigest()))throw ManagementAccess.denied();
        fresh(tenant,tx,invocation,now(tx));
    }

    /** The adapter obtains deviceId from a verified client certificate, never from the submitted body. */
    public Store.Reply consume(Ids.TenantId tenant,String authenticatedDevice,String document){
        return consume(tenant,tx->authenticatedDevice,document);
    }
    public Store.Reply consume(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document){var request=codec.model(document,EnterprisePermitConsumption.class);var claims=signer.verify(request.permit());
        return authoritative(tenant,"device-effect",request.invocationId(),DirectoryService.digest(document),tx->{long now=now(tx);String authenticatedDevice=certificate.apply(tx);var i=invocation(tx,request.invocationId());var e=i.evaluation();var lease=tx.enterprise().nonce(claims.nonce());
            if(!issuer.equals(claims.issuer())||!audience.equals(claims.audience())||!authenticatedDevice.equals(claims.deviceId())||!authenticatedDevice.equals(e.context().deviceId())
                ||!claims.invocationId().equals(i.id())||!claims.requestDigest().equals(i.requestDigest())||!claims.evaluationDigest().equals(DirectoryService.digest(codec.json(e)))
                ||!claims.toolDigest().equals(request.toolDigest())||!claims.packageDigest().equals(request.packageDigest())||!e.toolDigest().equals(request.toolDigest())||!e.packageDigest().equals(request.packageDigest())
                ||!claims.bindingId().equals(e.context().bindingId())||!request.argumentsDigest().equals(e.argumentsDigest())||!request.resources().equals(e.resources())
                ||claims.issuedAtUnixMs()>now||claims.expiresAtUnixMs()<=now||claims.expiresAtUnixMs()-claims.issuedAtUnixMs()>10000
                ||!java.util.Objects.equals(claims.authorizationEpoch(),i.authorizationEpoch())||claims.directoryRevision()!=tx.load().revision()||i.state()!=EnterpriseInvocationState.RESERVED||!claims.nonce().equals(i.reservedNonce())
                ||lease==null||!lease.invocationId().equals(i.id())||!lease.evaluationDigest().equals(claims.evaluationDigest())||!java.util.Objects.equals(lease.authorizationEpoch(),i.authorizationEpoch())||!java.util.Objects.equals(lease.expiresAt(),claims.expiresAtUnixMs()))throw ManagementAccess.denied();
            fresh(tenant,tx,i,now);tx.enterprise().consumeNonce(claims.nonce(),now);var next=changed(i,EnterpriseInvocationState.EXECUTING,i.reservedNonce(),i.completedResources(),null);tx.enterprise().saveInvocation(next,now,i.revision());
            tx.audit(DirectoryService.digest(authenticatedDevice),"PERMIT_CONSUME","invocation:"+i.id(),next.revision(),e.context().requestId(),i.requestDigest());return reply(next,next.revision());});
    }
    private void reviewer(Store.Session tx,String user,EnterpriseInvocation i,String obligation,long now){
        var d=tx.load();var e=i.evaluation();if(new ManagementAccess(codec).superAdministrator(d,user)){var policyEntry=d.entries().get(Kind.POLICY.id(obligation));if(policyEntry==null||!policyEntry.enabled()||codec.model(policyEntry.document(),ControlPolicy.class).decision()!=Decision.ASK)throw ManagementAccess.denied();return;}if(user.equals(e.context().userId()))throw ManagementAccess.denied();
        var agentIds=new ArrayList<String>();e.context().chain().forEach(h->agentIds.add(h.agentId()));if(e.context().agentId()!=null)agentIds.add(e.context().agentId());
        for(var agent:agentIds){var a=codec.model(d.entries().get(Kind.AGENT.id(agent)).document(),ControlAgent.class);if(user.equals(a.ownerUserId()))throw ManagementAccess.denied();}
        var entry=d.entries().get(Kind.POLICY.id(obligation));if(entry==null||!entry.enabled())throw ManagementAccess.denied();var policy=codec.model(entry.document(),ControlPolicy.class);
        var teams=GroupGraph.memberships(d,codec,Kind.USER,user,true);if(policy.decision()!=Decision.ASK||!EnterpriseEvaluator.intersects(policy.approverTeams(),teams))throw ManagementAccess.denied();
        var binding=codec.model(d.entries().get(Kind.BINDING.id(e.context().bindingId())).document(),ControlExecutionBinding.class);var access=new ManagementAccess(codec);
        access.require(d,user,"approve-operation",Kind.TOOL_GROUP,binding.toolGroupId(),now);access.require(d,user,"approve-operation",Kind.DEVICE_GROUP,binding.deviceGroupId(),now);
    }
    public Store.Reply decide(DirectoryService.Actor actor,String id,String document,String key){if(actor.userId()==null)throw ManagementAccess.denied();Ids.valid(key);var request=codec.model(document,EnterpriseApprovalDecisionRequest.class);
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var a=tx.enterprise().approval(Ids.valid(id));if(a==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Approval not found");var i=invocation(tx,a.invocationId());
            reviewer(tx,actor.userId(),i,request.obligationId(),now);if(a.approvalType()!=EnterpriseApprovalType.OPERATION||!a.obligationIds().contains(request.obligationId())||a.expiresAtUnixMs()<=now||a.authorizationEpoch()!=tx.enterprise().authorizationEpoch())throw ManagementAccess.denied();
            String hash=DirectoryService.digest(id+"\n"+codec.json(request));var replay=tx.replay(actor.id(),key,hash);if(replay!=null)return replay;
            decide(actor.tenant(),tx,i.evaluation(),now,true);if(!java.util.Objects.equals(a.revision(),request.expectedRevision())||a.state()!=EnterpriseApprovalState.PENDING&&request.decision()!=EnterpriseReviewDecision.REVOKE)throw Failure.conflict();
            var reviews=new ArrayList<>(a.reviews());if(reviews.stream().anyMatch(r->r.obligationId().equals(request.obligationId())&&request.decision()!=EnterpriseReviewDecision.REVOKE))throw Failure.conflict();reviews.add(new EnterpriseApprovalReview(request.obligationId(),actor.userId(),request.decision(),now));
            var state=request.decision()==EnterpriseReviewDecision.REVOKE?EnterpriseApprovalState.REVOKED:request.decision()==EnterpriseReviewDecision.DENY?EnterpriseApprovalState.DENIED:
                a.obligationIds().stream().allMatch(ob->reviews.stream().anyMatch(r->r.obligationId().equals(ob)&&r.decision()==EnterpriseReviewDecision.APPROVE))?EnterpriseApprovalState.APPROVED:EnterpriseApprovalState.PENDING;
            var next=new EnterpriseApproval(a.id(),a.approvalType(),a.invocationId(),a.requestDigest(),a.authorizationEpoch(),a.directoryRevision(),a.obligationIds(),reviews,state,a.revision()+1,a.expiresAtUnixMs());tx.enterprise().saveApproval(next,a.revision());
            if(state==EnterpriseApprovalState.APPROVED){if(i.state()!=EnterpriseInvocationState.PENDING_APPROVAL)throw Failure.conflict();var queued=changed(i,EnterpriseInvocationState.QUEUED,null,List.of(),null);tx.enterprise().saveInvocation(queued,now,i.revision());}
            tx.audit(actor.id(),"OPERATION_"+state.name()+(new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId())?"_SUPER_ADMIN":""),"approval:"+a.id(),next.revision(),i.evaluation().context().requestId(),a.requestDigest());var reply=reply(next,next.revision());tx.remember(actor.id(),key,hash,reply);return reply;});
    }
    public Store.Reply report(Ids.TenantId tenant,String authenticatedDevice,String document){
        return report(tenant,tx->authenticatedDevice,document);
    }
    public Store.Reply report(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document){var report=codec.model(document,EnterpriseEffectReport.class);
        return authoritative(tenant,"device-effect",report.invocationId(),DirectoryService.digest(document),tx->{var next=reportInSession(tenant,tx,certificate.apply(tx),report);
            if(next.state()==EnterpriseInvocationState.SUCCEEDED)try{fresh(tenant,tx,next,now(tx));}catch(Failure denied){if(denied.status()!=403)throw denied;tx.audit("device-effect","RESULT_ACCESS_DENIED","invocation:"+next.id(),next.revision(),next.evaluation().context().requestId(),next.requestDigest());return new Store.Reply(403,codec.json(Map.of("code",ErrorCode.FORBIDDEN)),next.revision());}
            return reply(next,next.revision());});
    }
    EnterpriseInvocation reportInSession(Ids.TenantId tenant,Store.Session tx,String authenticatedDevice,EnterpriseEffectReport report){
        long now=now(tx);var i=invocation(tx,report.invocationId());if(!authenticatedDevice.equals(i.evaluation().context().deviceId()))throw ManagementAccess.denied();
        if(i.revision()==report.expectedRevision()+1&&i.state()==report.state()&&i.completedResources().equals(report.completedResources())&&Objects.equals(i.resultDigest(),report.resultDigest()))return i;
        if(!java.util.Objects.equals(i.revision(),report.expectedRevision())||i.state()!=EnterpriseInvocationState.EXECUTING)throw Failure.conflict();
            var completed=report.completedResources();var all=i.evaluation().resources();if(new HashSet<>(completed).size()!=completed.size()||!all.containsAll(completed)||!completed.containsAll(i.completedResources()))throw Failure.validation();
            switch(report.state()){
                case EXECUTING->{fresh(tenant,tx,i,now);}
                case SUCCEEDED->{if(!new HashSet<>(completed).equals(new HashSet<>(all))||report.resultDigest()==null)throw Failure.validation();}
                case PARTIAL->{if(completed.isEmpty()||completed.size()==all.size())throw Failure.validation();}
                case FAILED->{if(!completed.isEmpty())throw Failure.validation();}
                case OUTCOME_UNKNOWN->{}
                default->throw Failure.validation();
            }
            var next=changed(i,report.state(),i.reservedNonce(),completed,report.resultDigest());tx.enterprise().saveInvocation(next,now,i.revision());tx.audit(DirectoryService.digest(authenticatedDevice),"EFFECT_"+report.state().name(),"invocation:"+i.id(),next.revision(),i.evaluation().context().requestId(),i.requestDigest());return next;
    }

    private boolean approvalVisible(Store.Session tx,DirectoryService.Actor actor,EnterpriseApproval a,long now){
        if(actor.userId()==null)return false;var i=invocation(tx,a.invocationId());
        if(actor.userId().equals(i.evaluation().context().userId()))return true;
        for(var obligation:a.obligationIds())try{reviewer(tx,actor.userId(),i,obligation,now);return true;}catch(Failure denied){if(denied.status()!=403)throw denied;}
        return false;
    }
    public Store.Reply approval(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var a=tx.enterprise().approval(Ids.valid(id));if(a==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Approval not found");if(!approvalVisible(tx,actor,a,now))throw ManagementAccess.denied();return reply(a,a.revision());});}
    public Store.Reply approvals(DirectoryService.Actor actor,String after,int limit){if(actor.userId()==null||limit<1||limit>100)throw Failure.validation();if(after!=null)Ids.valid(after);
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var result=new ArrayList<EnterpriseApproval>();String cursor=after==null?"":after;boolean remaining=false;
            // Advance past hidden rows; bounded scanning prevents scope filters from leaking tenant totals.
            for(int scan=0;scan<100;scan++){var page=tx.enterprise().approvals(cursor,100);if(page.isEmpty())break;for(var a:page){cursor=a.id();if(approvalVisible(tx,actor,a,now))result.add(a);if(result.size()==limit){remaining=true;break;}}if(remaining||page.size()<100)break;}
            return reply(new EnterpriseApprovalPage(result,remaining?cursor:null),tx.load().revision());});
    }
    public Store.Reply cancel(DirectoryService.Actor actor,String id,long expected){if(actor.userId()==null)throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var i=invocation(tx,id);if(i.revision()!=expected)throw Failure.conflict();
            if(!actor.userId().equals(i.evaluation().context().userId())){var binding=codec.model(tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);new ManagementAccess(codec).require(tx.load(),actor.userId(),"cancel-operation",Kind.TOOL_GROUP,binding.toolGroupId(),now);new ManagementAccess(codec).require(tx.load(),actor.userId(),"cancel-operation",Kind.DEVICE_GROUP,binding.deviceGroupId(),now);}
            if(Set.of(EnterpriseInvocationState.SUCCEEDED,EnterpriseInvocationState.FAILED,EnterpriseInvocationState.PARTIAL,EnterpriseInvocationState.OUTCOME_UNKNOWN,EnterpriseInvocationState.CANCELLED,EnterpriseInvocationState.EXPIRED).contains(i.state()))throw Failure.conflict();
            var next=changed(i,i.state()==EnterpriseInvocationState.EXECUTING?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.CANCELLED,i.reservedNonce(),i.completedResources(),i.resultDigest());tx.enterprise().saveInvocation(next,now,i.revision());
            var a=tx.enterprise().approval(approvalId(i.id()));if(a!=null)tx.enterprise().saveApproval(new EnterpriseApproval(a.id(),a.approvalType(),a.invocationId(),a.requestDigest(),a.authorizationEpoch(),a.directoryRevision(),a.obligationIds(),a.reviews(),EnterpriseApprovalState.CANCELLED,a.revision()+1,a.expiresAtUnixMs()),a.revision());
            audit(tx,actor,"INVOCATION_CANCEL",next);return reply(next,next.revision());});
    }
    /** Reading an invocation/result checks current authority again; old approval never revives a grant. */
    public Store.Reply get(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var i=invocation(tx,id);if(actor.userId()!=null&&actor.userId().equals(i.evaluation().context().userId())){decide(actor.tenant(),tx,i.evaluation(),now,true);}else if(actor.userId()!=null){var b=codec.model(tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);new ManagementAccess(codec).require(tx.load(),actor.userId(),"read",Kind.TOOL_GROUP,b.toolGroupId(),now);new ManagementAccess(codec).require(tx.load(),actor.userId(),"read",Kind.DEVICE_GROUP,b.deviceGroupId(),now);}else {gateway(actor);fresh(actor.tenant(),tx,i,now);}return reply(i,i.revision());});}
    /** Own-device outcome recovery is metadata only; it never renews or reissues a permit. */
    public Store.Reply deviceOutcome(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String id){
        Ids.valid(id);sweep(tenant);return store.transaction(tenant,false,tx->{var device=certificate.apply(tx);var i=invocation(tx,id);if(!device.equals(i.evaluation().context().deviceId()))throw ManagementAccess.denied();return reply(i,i.revision());});
    }
    /** Bounded durable watchdog. Commit expiry separately from rejected authorizations. */
    public int sweep(Ids.TenantId tenant){return store.transaction(tenant,true,tx->{long time=now(tx);var expired=tx.enterprise().expiredInvocations(time,100);for(var i:expired)invocation(tx,i.id());return expired.size();});}
    private void scope(Store.Session tx,String user,EnterpriseInvocation i,String action,long time){
        var entry=tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId()));if(entry==null)throw ManagementAccess.denied();var b=codec.model(entry.document(),ControlExecutionBinding.class);var access=new ManagementAccess(codec);
        access.require(tx.load(),user,action,Kind.TOOL_GROUP,b.toolGroupId(),time);access.require(tx.load(),user,action,Kind.DEVICE_GROUP,b.deviceGroupId(),time);
    }
    public Store.Reply page(DirectoryService.Actor actor,String after,int limit){if(actor.userId()==null||limit<1||limit>100)throw Failure.validation();if(after!=null)Ids.valid(after);sweep(actor.tenant());
        return store.transaction(actor.tenant(),false,tx->{long time=clock.millis();String cursor=after==null?"":after;var values=new ArrayList<EnterpriseInvocation>();boolean more=false;
            for(int scan=0;scan<100;scan++){var rows=tx.enterprise().invocations(cursor,100);if(rows.isEmpty())break;for(var i:rows){cursor=i.id();try{scope(tx,actor.userId(),i,"read",time);values.add(i);}catch(Failure denied){if(denied.status()!=403)throw denied;}if(values.size()==limit){more=true;break;}}if(more||rows.size()<100)break;}
            return reply(new EnterpriseInvocationPage(values,more?cursor:null),tx.load().revision());});
    }
    private void independent(Store.Session tx,String user,EnterpriseInvocation i){if(new ManagementAccess(codec).superAdministrator(tx.load(),user))return;if(user.equals(i.evaluation().context().userId()))throw ManagementAccess.denied();var ids=new ArrayList<String>();i.evaluation().context().chain().forEach(h->ids.add(h.agentId()));if(i.evaluation().context().agentId()!=null)ids.add(i.evaluation().context().agentId());for(var id:ids){var entry=tx.load().entries().get(Kind.AGENT.id(id));if(entry!=null&&user.equals(codec.model(entry.document(),ControlAgent.class).ownerUserId()))throw ManagementAccess.denied();}}
    public Store.Reply proposeReconciliation(DirectoryService.Actor actor,String id,String document,String key){if(actor.userId()==null)throw ManagementAccess.denied();Ids.valid(key);var request=codec.model(document,EnterpriseReconciliationRequest.class);sweep(actor.tenant());
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var i=invocation(tx,id);scope(tx,actor.userId(),i,"approve-operation",time);independent(tx,actor.userId(),i);String digest=DirectoryService.digest(id+"\n"+codec.json(request)),rid="reconcile-"+DirectoryService.digest(actor.id()+"\n"+key).substring(0,40);var old=tx.enterprise().reconciliation(rid);if(old!=null){if(!old.requestDigest().equals(digest))throw Failure.conflict();return reply(old,old.revision());}
            if(i.state()!=EnterpriseInvocationState.OUTCOME_UNKNOWN||!java.util.Objects.equals(i.revision(),request.expectedRevision()))throw Failure.conflict();var all=i.evaluation().resources();var done=request.completedResources();if(new HashSet<>(done).size()!=done.size()||!all.containsAll(done)||!done.containsAll(i.completedResources()))throw Failure.validation();
            switch(request.state()){case SUCCEEDED->{if(!new HashSet<>(done).equals(new HashSet<>(all))||request.resultDigest()==null)throw Failure.validation();}case FAILED->{if(!done.isEmpty())throw Failure.validation();}case PARTIAL->{if(done.isEmpty()||done.size()==all.size())throw Failure.validation();}}
            var c=new EnterpriseReconciliation(rid,id,actor.userId(),request,digest,1L,null,EnterpriseReconciliationState.PENDING);tx.enterprise().saveReconciliation(c,0);tx.audit(actor.id(),"RECONCILIATION_PROPOSE",id,c.revision(),id,request.evidenceDigest());return reply(c,c.revision());});
    }
    public Store.Reply reconciliation(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),false,tx->{var c=tx.enterprise().reconciliation(Ids.valid(id));if(c==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Reconciliation not found");var i=tx.enterprise().invocation(c.invocationId());scope(tx,actor.userId(),i,"read",clock.millis());return reply(c,c.revision());});}
    public Store.Reply reconcile(DirectoryService.Actor actor,String id,String document){if(actor.userId()==null)throw ManagementAccess.denied();var request=codec.model(document,EnterpriseReconciliationDecision.class);
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var c=tx.enterprise().reconciliation(Ids.valid(id));if(c==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Reconciliation not found");var i=invocation(tx,c.invocationId());scope(tx,actor.userId(),i,"approve-operation",time);scope(tx,c.requesterUserId(),i,"approve-operation",time);independent(tx,actor.userId(),i);independent(tx,c.requesterUserId(),i);if(actor.userId().equals(c.requesterUserId())&&!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))throw ManagementAccess.denied();
            if(c.state()!=EnterpriseReconciliationState.PENDING||!java.util.Objects.equals(c.revision(),request.expectedRevision())||i.state()!=EnterpriseInvocationState.OUTCOME_UNKNOWN||i.revision()!=c.request().expectedRevision())throw Failure.conflict();
            var next=new EnterpriseReconciliation(c.id(),c.invocationId(),c.requesterUserId(),c.request(),c.requestDigest(),c.revision()+1,actor.userId(),request.approve()?EnterpriseReconciliationState.APPLIED:EnterpriseReconciliationState.DENIED);tx.enterprise().saveReconciliation(next,c.revision());
            if(request.approve()){var resolved=changed(i,EnterpriseInvocationState.valueOf(c.request().state().name()),i.reservedNonce(),c.request().completedResources(),c.request().resultDigest());tx.enterprise().saveInvocation(resolved,time,i.revision());}
            tx.audit(actor.id(),"RECONCILIATION_"+next.state()+(new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId())?"_SUPER_ADMIN":""),i.id(),next.revision(),i.id(),c.request().evidenceDigest());return reply(next,next.revision());});
    }

}
