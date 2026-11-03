// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.time.Clock;
import java.util.*;

/** Online effect authority. Submission, approval, reservation and consumption share current group semantics.
 * Invocation IDs bind exact arguments/resources/versions. A consumed capability is never issued again.
 * Database commit proves consumption, not exactly-once completion of an external side effect.
 */
public final class EnterpriseOperations {
    private final Store store;private final Codec codec;private final EffectSigner signer;private final Clock clock;
    private final String issuer,audience;private final long lifetime;private final int capacity;
    /** How long an operation may wait for approval (OLO M2.4: 7 days); defaults to the invocation lifetime. */
    private volatile long approvalExpiry=-1;
    public static final long MAX_APPROVAL_EXPIRY_MS=7L*24*60*60*1000;
    public EnterpriseOperations approvalExpiry(long ms){if(ms<1000||ms>MAX_APPROVAL_EXPIRY_MS)throw new IllegalArgumentException("Approval expiry must be 1 s to 7 days");approvalExpiry=ms;return this;}
    private long approvalExpiry(){return approvalExpiry>0?approvalExpiry:lifetime;}
    private volatile ClientCheckInPolicy checkIn=ClientCheckInPolicy.configure(null,null,null);
    /** The running check-in policy decides which devices are online for placement. */
    public EnterpriseOperations checkInPolicy(ClientCheckInPolicy value){checkIn=java.util.Objects.requireNonNull(value);return this;}
    private volatile String userCredentialIssuer;
    /** Accept Gateway {@code HUMAN} contexts verified from user credentials of this issuer (M3.4); null refuses all. */
    public EnterpriseOperations userCredentialIssuer(String issuer){userCredentialIssuer=issuer==null||issuer.isBlank()?null:Ids.valid(issuer);return this;}
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
    /** The M2.4 request fingerprint: what is asked, by whom, of what. Session and credential epochs are left out, so a
     *  renewed or rotated credential replays the same invocation; they are rechecked when it is decided. */
    private String requestDigest(EnterpriseEvaluation e,String downstream){var c=e.context();
        var stable=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),null,null,c.bindingId(),c.deviceId());
        return DirectoryService.digest(codec.json(new EnterpriseEvaluation(stable,e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),0L,0L,true,e.amountMinorUnits(),e.operation()))+"\n"+(downstream==null?"":downstream));
    }
    private static EnterpriseEvaluation current(EnterpriseEvaluation e,Directory directory,long now){return new EnterpriseEvaluation(e.context(),e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),now,directory.revision(),true,e.amountMinorUnits(),e.operation());}
    private EnterpriseDecision decide(Ids.TenantId tenant,Store.Session tx,EnterpriseEvaluation e,long now,boolean existing){return decide(tenant,tx,e,now,existing,new java.util.TreeSet<>());}
    private EnterpriseDecision decide(Ids.TenantId tenant,Store.Session tx,EnterpriseEvaluation e,long now,boolean existing,java.util.Set<String> budgets){
        var usage=new java.util.HashMap<String,Long>();
        var d=new EnterpriseEvaluator(codec).evaluateWithQuotas(tenant,tx.load(),current(e,tx.load(),now),budget->{budgets.add(budget);return usage.computeIfAbsent(budget,id->tx.enterprise().budgetUsage(id,Math.max(0,now-60000),existing?e.context().requestId():null));});
        if(d.decision()==Decision.BLOCK)throw ManagementAccess.denied();return d;
    }
    private EnterpriseInvocation invocation(Store.Session tx,String id){var i=tx.enterprise().invocation(Ids.valid(id));if(i==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Invocation not found");
        long time=clock.millis();var executionLease=i.state()==EnterpriseInvocationState.EXECUTING?tx.enterprise().executionLease(i.id()):null;
        boolean expired=i.expiresAtUnixMs()<=time||executionLease!=null
                &&Objects.equals(executionLease.fencingToken(),i.reservedNonce())&&executionLease.expiresAt()<=time;
        if(expired&&Set.of(EnterpriseInvocationState.PENDING_APPROVAL,EnterpriseInvocationState.QUEUED,EnterpriseInvocationState.RESERVED,EnterpriseInvocationState.EXECUTING).contains(i.state())){
            var state=i.state()==EnterpriseInvocationState.EXECUTING?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.EXPIRED;
            var next=changed(i,state,i.reservedNonce(),i.completedResources(),i.resultDigest());tx.enterprise().saveInvocation(next,time,i.revision());tx.audit("effect-watchdog","INVOCATION_"+state.name(),"invocation:"+id,next.revision(),i.evaluation().context().requestId(),i.requestDigest(),evidence(next));i=next;
        }return i;}
    /** A replay under a renewed or rotated credential: the stored invocation takes the current session and credential
     *  epochs, so the decision that follows checks the caller's current authority, never the authority it once had. */
    private EnterpriseInvocation renewed(Store.Session tx,DirectoryService.Actor actor,EnterpriseInvocation i,EnterpriseContext current,long now){
        var c=i.evaluation().context();
        if(java.util.Objects.equals(c.sessionEpoch(),current.sessionEpoch())&&java.util.Objects.equals(c.credentialEpoch(),current.credentialEpoch()))return i;
        var context=new EnterpriseContext(c.requestId(),c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),current.sessionEpoch(),current.credentialEpoch(),c.bindingId(),c.deviceId());
        var e=i.evaluation();
        var evaluation=new EnterpriseEvaluation(context,e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),e.nowUnixMs(),e.authorityRevision(),e.online(),e.amountMinorUnits(),e.operation());
        var next=new EnterpriseInvocation(i.id(),i.requestDigest(),evaluation,i.state(),i.revision()+1,i.authorizationEpoch(),i.reservedNonce(),i.expiresAtUnixMs(),i.completedResources(),i.downstreamIdempotencyKey(),i.resultDigest(),i.diagnosticId(),i.reconciliation());
        tx.enterprise().saveInvocation(next,now,i.revision());audit(tx,actor,"INVOCATION_RENEWED",next);return next;}
    /** The reconciliation method the tool declared (OLO M5.4); a tool with effects that declared none is settled manually. */
    private ReconciliationMethod reconciliation(Directory directory,String toolId){
        var entry=directory.entries().get(Kind.TOOL.id(toolId));if(entry==null)return ReconciliationMethod.MANUAL;
        var declared=codec.model(entry.document(),ControlTool.class).definition().reconciliation();
        return declared!=null?declared:ReconciliationMethod.MANUAL;}
    private EnterpriseInvocation changed(EnterpriseInvocation i,EnterpriseInvocationState state,String nonce,List<ResourceDescriptor> completed,String result){return new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),state,i.revision()+1,i.authorizationEpoch(),nonce,i.expiresAtUnixMs(),completed,i.downstreamIdempotencyKey(),result,i.diagnosticId(),i.reconciliation());}
    /** M4.4: who, as whom, through which workload, what operation on which resources, the decision, the policy and
     *  authorization epoch, and the resulting state, as separate audit fields. Never arguments, payloads or credentials;
     *  more than 16 resources are recorded by count and digest only. */
    String evidence(EnterpriseInvocation i){var e=i.evaluation();var c=e.context();var m=new java.util.LinkedHashMap<String,Object>();
        m.put("invocationId",i.id());m.put("mode",c.mode().name());if(c.userId()!=null)m.put("subjectUserId",c.userId());if(c.agentId()!=null)m.put("actorAgentId",c.agentId());
        if(!c.chain().isEmpty())m.put("delegationChain",c.chain().stream().map(h->h.agentId()).limit(16).toList());if(c.workloadBindingId()!=null)m.put("workloadBindingId",c.workloadBindingId());
        m.put("toolId",e.toolId());m.put("operation",e.action());var resources=e.resources().stream().map(r->r.kind().name()+":"+r.locator()).toList();
        if(resources.size()<=16&&resources.stream().allMatch(r->r.length()<=512))m.put("resources",resources);m.put("resourceCount",resources.size());
        m.put("resourcesDigest",DirectoryService.digest(String.join("\n",resources)));m.put("decision",i.state()==EnterpriseInvocationState.PENDING_APPROVAL?"ASK":"ALLOW");
        m.put("state",i.state().name());m.put("authorizationEpoch",i.authorizationEpoch());m.put("policyRevision",e.authorityRevision());
        String json=codec.json(m);if(json.length()<=4096)return json;m.remove("resources");return codec.json(m);}
    private void audit(Store.Session tx,DirectoryService.Actor actor,String operation,EnterpriseInvocation i){tx.audit(actor.id(),operation,"invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest(),evidence(i));}
    private void fresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation i,long now){
        fresh(tenant,tx,i,now,false);
    }
    private void fresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation i,long now,boolean metadataOrProvenRecovery){
        var executionLease=tx.enterprise().executionLease(i.id());
        // Metadata reads and positively proven recovery may inspect an ended execution lease.
        // This does not grant execution; deadlines, credentials, epoch and approvals still apply.
        if(!metadataOrProvenRecovery&&i.state()==EnterpriseInvocationState.EXECUTING&&executionLease!=null&&Objects.equals(executionLease.fencingToken(),i.reservedNonce())
                &&executionLease.expiresAt()<=now)throw ManagementAccess.denied();
        Long credentialDeadline=tx.enterprise().userCredentialDeadline(i.id());
        if(credentialDeadline!=null&&credentialDeadline<=now)throw ManagementAccess.denied();
        if(i.expiresAtUnixMs()<=now||i.authorizationEpoch()!=tx.enterprise().authorizationEpoch())throw ManagementAccess.denied();
        var decision=decide(tenant,tx,i.evaluation(),now,true);
        if(decision.decision()==Decision.ASK){var approval=tx.enterprise().approval(approvalId(i.id()));
            if(approval==null||approval.approvalType()!=EnterpriseApprovalType.OPERATION||approval.state()!=EnterpriseApprovalState.APPROVED||approval.expiresAtUnixMs()<=now
                ||!approval.requestDigest().equals(i.requestDigest())||!java.util.Objects.equals(approval.authorizationEpoch(),i.authorizationEpoch())||!approval.obligationIds().equals(decision.obligations()))throw ManagementAccess.denied();
            // Reviewer privilege and membership remain live obligations, including bounded/JIT roles.
            for(var review:approval.reviews())if(review.decision()==EnterpriseReviewDecision.APPROVE)reviewer(tx,review.reviewerUserId(),i,review.obligationId(),now);
        }
        EffectConsents.require(tx,codec,i,now);
    }
    public Store.Reply submit(DirectoryService.Actor actor,String document){gateway(actor);var request=codec.model(document,EnterpriseInvocationRequest.class);if(request.context().mode()==RequestMode.HUMAN&&!gatewayUser(request.context())||request.context().mode()==RequestMode.DELEGATED&&onBehalfOf(actor.tenant(),request.context())&&!gatewayDelegated(request.context())||request.context().mode()==RequestMode.DELEGATED&&laundered(request.context()))throw ManagementAccess.denied();return submitVerified(actor,request,null);}
    /** A Gateway HUMAN context exists only for a verified user credential: JWT kind, the configured issuer, a credential digest,
     *  and no agent or workload. The evaluator then requires the user enabled and the identity binding at this session epoch. */
    private boolean gatewayUser(io.ololabs.toolgate.contracts.v2.RequestContext c){String trusted=userCredentialIssuer;
        return trusted!=null&&c.credentialKind()==io.ololabs.toolgate.contracts.v2.CredentialKind.JWT&&trusted.equals(c.issuer())&&c.credentialSha256()!=null
            &&c.userId()!=null&&c.sessionEpoch()!=null&&c.agentId()==null&&c.workloadBindingId()==null&&c.chain().isEmpty();}
    /** M3.10: whether any hop of a DELEGATED context uses an on-behalf-of workload binding. */
    private boolean onBehalfOf(Ids.TenantId tenant,io.ololabs.toolgate.contracts.v2.RequestContext c){
        var directory=store.transaction(tenant,false,Store.Session::load);return onBehalfOf(directory,c);
    }
    private boolean onBehalfOf(Directory directory,io.ololabs.toolgate.contracts.v2.RequestContext c){
        var hops=new ArrayList<String>();c.chain().forEach(h->hops.add(h.workloadBindingId()));if(c.workloadBindingId()!=null)hops.add(c.workloadBindingId());
        return hops.stream().map(id->directory.entries().get(Kind.WORKLOAD_BINDING.id(id))).anyMatch(e->
                e!=null&&EnterpriseEvaluator.onBehalfOf(codec.model(e.document(),ControlWorkloadBinding.class)));
    }
    /** An on-behalf-of context carries a verified user credential from the configured issuer, plus the agent's workload credential. */
    private boolean gatewayDelegated(io.ololabs.toolgate.contracts.v2.RequestContext c){String trusted=userCredentialIssuer;
        return trusted!=null&&c.credentialKind()==io.ololabs.toolgate.contracts.v2.CredentialKind.JWT&&trusted.equals(c.issuer())&&c.userId()!=null&&c.sessionEpoch()!=null
            &&c.agentId()!=null&&c.workloadBindingId()!=null&&c.credentialSha256()!=null;}
    /** M3.5: the reserved service principal never carries delegated authority, as the agent or as a chain hop. */
    private boolean laundered(io.ololabs.toolgate.contracts.v2.RequestContext c){var olo=codec.protections();
        return olo.reserved(c.agentId())||c.chain().stream().anyMatch(h->olo.reserved(h.agentId()));}
    /** A person names the device; it must belong to the execution binding's device group. */
    public Store.Reply submitHuman(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String device){if(actor.userId()==null||device==null||request.context().mode()!=RequestMode.HUMAN||!actor.userId().equals(request.context().userId()))throw ManagementAccess.denied();return submitVerified(actor,request,device);}
    /** M2.4 atomic create: concurrent submissions of one key insert at most one invocation. A submission that loses the
     *  race is decided once more, so it gets the winner's invocation, or a conflict if its fingerprint differs. */
    private Store.Reply submitVerified(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String chosen){
        var supplied=request;
        request=store.transaction(actor.tenant(),false,tx->{
            var binding=BusinessKeyPolicies.bind(actor.tenant().value(),supplied,BusinessKeyPolicies.configured(tx,codec,supplied.request().toolId()));
            return BusinessKeyPolicies.identified(actor.tenant().value(),supplied,binding);
        });
        return submitRetry(actor,request,chosen);
    }
    private Store.Reply submitRetry(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String chosen){
        try{return submitOnce(actor,request,chosen);}
        catch(Failure lost){
            if(lost.status()!=409||store.transaction(actor.tenant(),false,tx->tx.enterprise().invocation(request.context().requestId()))==null)throw lost;
            return submitOnce(actor,request,chosen);
        }
    }
    private Store.Reply submitOnce(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String chosen){
        // Correlation context is not part of the logical operation.
        var operation=request.executionContext()==null?request:new EnterpriseInvocationRequest(request.context(),request.request(),request.toolDigest(),request.packageDigest(),request.downstreamIdempotencyKey(),null,request.businessKeyHash());
        String document=codec.json(operation)+(chosen==null?"":"\n"+chosen);
        return authoritative(actor.tenant(),actor.id(),request.context().requestId(),DirectoryService.digest(document),tx->{
            long time=now(tx);
            var key=BusinessKeyPolicies.bind(actor.tenant().value(),request,BusinessKeyPolicies.configured(tx,codec,request.request().toolId()));
            if(key!=null&&key.hash()==null)return submitWindow(actor,request,chosen,tx,time,key);
            if(key!=null&&!BusinessKeyPolicies.identified(actor.tenant().value(),request,key).context().requestId().equals(request.context().requestId()))throw Failure.conflict();
            return admit(actor,request,chosen,tx,time,key,false,null);
        });
    }
    private String windowFingerprint(EnterpriseInvocationRequest request){
        var c=request.context();
        var stable=new EnterpriseContext("window-request",c.tenantId(),c.mode(),c.userId(),c.agentId(),c.workloadBindingId(),
                c.chain(),null,null,c.bindingId(),"window-device");
        // The extractor/profile semantics are bound by toolDigest. Correlation,
        // credential renewal and which replica/device is selected are not a key.
        var value=new java.util.LinkedHashMap<String,Object>();
        value.put("context",stable);value.put("request",request.request());
        value.put("toolDigest",request.toolDigest());value.put("packageDigest",request.packageDigest());
        value.put("downstream",request.downstreamIdempotencyKey()==null?"":request.downstreamIdempotencyKey());
        return DirectoryService.digest("toolgate-business-window-v1\n"+codec.json(value));
    }
    private static void keyOwner(EnterpriseStore.BusinessKey business,io.ololabs.toolgate.contracts.v2.RequestContext context){
        // Namespace scoping never supplies cross-owner result authority.
        if(!Objects.equals(business.agent(),context.agentId()==null?"":context.agentId())
                ||!Objects.equals(business.user(),context.userId()))throw Failure.conflict();
    }
    private Store.Reply submitWindow(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String chosen,
                                    Store.Session tx,long time,BusinessKeyPolicies.Binding policy){
        String fingerprint=windowFingerprint(request);
        String hash=DirectoryService.digest(request.request().toolId()+"\0sha256:"+fingerprint);
        var key=new BusinessKeyPolicies.Binding(policy.namespace(),policy.identity(),hash,"DEDUPE_WINDOW",
                policy.retentionMs(),policy.dedupeWindowMs());
        var previous=tx.enterprise().businessKey(request.request().toolId(),key.namespace(),key.identity(),hash);
        boolean replace=false;
        String predecessor="initial";
        if(previous!=null){
            keyOwner(previous,request.context());
            if(!"DEDUPE_WINDOW".equals(previous.source())||!previous.fingerprint().equals("sha256:"+fingerprint))throw Failure.conflict();
            invocation(tx,previous.invocation());
            if(previous.dedupeExpiresAt()==null)throw Failure.conflict();
            if(previous.dedupeExpiresAt()<=time){
                var proof=new OperationFinalizer(codec).refresh(tx,previous.invocation(),time);
                if(!proof.finalized()||!Set.of(EnterpriseOperationState.FAILED,EnterpriseOperationState.SUCCEEDED).contains(proof.state()))throw Failure.conflict();
                replace=true;predecessor=previous.invocation();
            }else{
                if(previous.tombstonedAt()!=null||previous.retainUntil()<=time)throw Failure.conflict();
                var replay=BusinessKeyPolicies.identified(request,previous.invocation(),null);
                return admit(actor,replay,chosen,tx,time,key,false,fingerprint);
            }
        }
        String id="op-"+DirectoryService.digest("toolgate-window-operation-v1\n"+actor.tenant().value()+"\n"+
                request.request().toolId()+"\n"+key.namespace()+"\n"+key.identity()+"\n"+hash+"\n"+predecessor);
        return admit(actor,BusinessKeyPolicies.identified(request,id,null),chosen,tx,time,key,replace,fingerprint);
    }
    private Store.Reply admit(DirectoryService.Actor actor,EnterpriseInvocationRequest request,String chosen,
                              Store.Session tx,long time,BusinessKeyPolicies.Binding key,boolean replace,String windowFingerprint){
        if(tx.enterprise().operationCancelled(request.context().requestId()))throw Failure.conflict();
        var placement=new DevicePlacement(codec,checkIn);
        if(chosen!=null&&!placement.member(tx,request.context().bindingId(),chosen))throw ManagementAccess.denied();
        var context=request.context();
        var business=key==null?null:tx.enterprise().businessKey(request.request().toolId(),key.namespace(),key.identity(),key.hash());
        if(business!=null){
            keyOwner(business,context);
            if(!business.source().equals(key.source()))throw Failure.conflict();
            if(!replace&&(business.tombstonedAt()!=null||business.retainUntil()<=time))throw Failure.conflict();
        }
        var placed=tx.enterprise().invocation(context.requestId());
        var e=new RuntimeAccess(codec).evaluation(actor.tenant(),tx.load(),context,chosen!=null?chosen:
                placed!=null?placed.evaluation().context().deviceId():placement.place(tx,context,time,request.request(),request.toolDigest(),request.packageDigest()),
                request.request(),request.toolDigest(),request.packageDigest(),time);
        Long credentialExpiry=context.expiresAtUnixMs();
        if(credentialExpiry!=null){
            if(credentialExpiry<=time)throw ManagementAccess.denied();
            tx.enterprise().userCredentialDeadline(e.context().requestId(),credentialExpiry);
        }
        String digest=requestDigest(e,request.downstreamIdempotencyKey());
        String keyFingerprint=windowFingerprint==null?digest:windowFingerprint;
        if(business!=null&&(!business.fingerprint().equals("sha256:"+keyFingerprint)
                ||!replace&&!business.invocation().equals(e.context().requestId())))throw Failure.conflict();
        var previous=tx.enterprise().invocation(e.context().requestId());
        if(previous!=null){
            if(key!=null&&business==null)throw Failure.conflict();
            if(!previous.requestDigest().equals(digest))throw Failure.conflict();
            previous=invocation(tx,previous.id());previous=renewed(tx,actor,previous,e.context(),time);
            var proof=OperationRebindingPolicy.proof(tx.enterprise().recoveryFacts(previous.id()),time);
            var latestOutcome=tx.enterprise().latestOperationOutcome(previous.id());
            if(latestOutcome!=null&&latestOutcome.value().state()==EnterpriseOperationState.RECONCILIATION_REQUIRED)proof=null;
            if(proof!=null){
                fresh(actor.tenant(),tx,previous,time,true);
                previous=tx.enterprise().rebind(previous,proof,time);
                audit(tx,actor,"INVOCATION_REBOUND",previous);
            }else decide(actor.tenant(),tx,previous.evaluation(),time,true);
            return reply(projected(tx,previous,time),previous.revision());
        }
        if(tx.enterprise().invocationsSince(Math.max(0,time-lifetime))>=capacity)throw Failure.conflict();
        var budgets=new java.util.TreeSet<String>();var decision=decide(actor.tenant(),tx,e,time,false,budgets);
        long epoch=tx.enterprise().authorizationEpoch();
        var consentPlan=EffectConsents.plan(tx,codec,e);
        var state=decision.decision()==Decision.ASK||consentPlan!=null?EnterpriseInvocationState.PENDING_APPROVAL:EnterpriseInvocationState.QUEUED;
        var i=new EnterpriseInvocation(context.requestId(),digest,e,state,1L,epoch,null,
                time+(state==EnterpriseInvocationState.PENDING_APPROVAL?approvalExpiry():lifetime),List.of(),
                request.downstreamIdempotencyKey(),null,decision.diagnosticId(),reconciliation(tx.load(),e.toolId()));
        for(var budget:budgets)tx.enterprise().reserveBudget(budget,i.id(),time);
        tx.enterprise().saveInvocation(i,time,0);
        if(consentPlan!=null)consentPlan.save(tx,codec,i,(com.fasterxml.jackson.databind.JsonNode)codec.value(codec.json(request.request().arguments())),credentialExpiry,time);
        var owners=new TreeSet<String>();if(context.userId()!=null)owners.add(context.userId());
        var agents=new ArrayList<String>();if(context.agentId()!=null)agents.add(context.agentId());context.chain().forEach(h->agents.add(h.agentId()));
        for(String agent:agents){
            var entry=tx.load().entries().get(Kind.AGENT.id(agent));if(entry==null)throw ManagementAccess.denied();
            owners.add(codec.model(entry.document(),ControlAgent.class).ownerUserId());
        }
        tx.enterprise().saveReviewOwners(i.id(),tx.load().revision(),owners);
        if(key!=null){
            var next=new EnterpriseStore.BusinessKey(e.toolId(),key.namespace(),key.identity(),key.hash(),"sha256:"+keyFingerprint,
                    context.agentId()==null?"":context.agentId(),context.userId(),i.id(),Math.addExact(time,key.retentionMs()),
                    null,key.source(),"DEDUPE_WINDOW".equals(key.source())?Math.addExact(time,key.dedupeWindowMs()):null);
            if(business==null)tx.enterprise().saveBusinessKey(next,time);
            else if(replace)tx.enterprise().replaceBusinessKey(business,next,time);
            else throw Failure.unavailable();
        }
        tx.enterprise().insightsContext(i.id(),request.executionContext());
        if(decision.decision()==Decision.ASK)tx.enterprise().saveApproval(new EnterpriseApproval(approvalId(i.id()),
                EnterpriseApprovalType.OPERATION,i.id(),digest,epoch,tx.load().revision(),decision.obligations(),List.of(),
                EnterpriseApprovalState.PENDING,1L,i.expiresAtUnixMs()),0);
        audit(tx,actor,"INVOCATION_SUBMIT",i);return reply(projected(tx,i,time),i.revision());
    }
    public Store.Reply reserve(DirectoryService.Actor actor,String document){gateway(actor);var request=codec.model(document,EnterpriseReservationRequest.class);
        return authoritative(actor.tenant(),actor.id(),request.invocationId(),DirectoryService.digest(document),tx->{var reservation=reserveInSession(actor.tenant(),tx,request,actor.id());return reply(reservation,reservation.invocation().revision());});
    }
    EnterpriseReservation reserveInSession(Ids.TenantId tenant,Store.Session tx,EnterpriseReservationRequest request,String actorId){
        var lease=lease(tenant,tx,request.invocationId(),request.expectedRevision(),actorId);var i=lease.invocation();var e=i.evaluation();
        var claims=new EnterprisePermitClaims(issuer,audience,lease.nonce(),i.id(),i.requestDigest(),lease.evaluationDigest(),i.authorizationEpoch(),tx.load().revision(),lease.issuedAt(),lease.expiresAt(),e.toolDigest(),e.packageDigest(),e.context().bindingId(),e.context().deviceId(),lease.approvalIds());
        return new EnterpriseReservation(projected(tx,i,now(tx)),signer.sign(claims));
    }
    /** One single-use nonce for a fresh attempt, at most 10 s; the invocation moves to RESERVED. Signing is the caller's. */
    record Lease(EnterpriseInvocation invocation,String nonce,String evaluationDigest,long issuedAt,long expiresAt,List<String> approvalIds) {}
    Lease lease(Ids.TenantId tenant,Store.Session tx,String invocationId,long expectedRevision,String actorId){
        long now=now(tx);var i=invocation(tx,invocationId);if(i.revision()!=expectedRevision)throw Failure.conflict();fresh(tenant,tx,i,now);executable(tx,i,now);
            if(i.state()==EnterpriseInvocationState.RESERVED){var old=tx.enterprise().nonce(i.reservedNonce());if(old==null||old.consumedAt()!=null||old.expiresAt()>now)throw Failure.conflict();}
            else if(i.state()!=EnterpriseInvocationState.QUEUED)throw Failure.conflict();
            var decision=decide(tenant,tx,i.evaluation(),now,true);
            int previousAttempts=tx.enterprise().attempts(i.id(),now).size();
            if(previousAttempts>=64)throw Failure.conflict();
            String nonce=String.format(java.util.Locale.ROOT,"attempt-%03d-%s",previousAttempts+1,UUID.randomUUID());long expires=Math.min(i.expiresAtUnixMs(),Math.min(now+10000,decision.validUntilUnixMs()));
            String evalDigest=DirectoryService.digest(codec.json(i.evaluation()));var e=i.evaluation();var approval=tx.enterprise().approval(approvalId(i.id()));var ids=approval==null?List.<String>of():List.of(approval.id());
            var next=changed(i,EnterpriseInvocationState.RESERVED,nonce,i.completedResources(),null);tx.enterprise().reserveExecutionNonce(new EnterpriseStore.Nonce(nonce,i.id(),evalDigest,i.authorizationEpoch(),now,expires,null));tx.enterprise().saveInvocation(next,now,i.revision());tx.audit(actorId,"PERMIT_RESERVE","invocation:"+next.id(),next.revision(),e.context().requestId(),next.requestDigest(),evidence(next));
            return new Lease(next,nonce,evalDigest,now,expires,ids);
    }
    String issuer(){return issuer;}
    EffectSigner signer(){return signer;}
    EnterpriseInvocation invocationIn(Store.Session tx,String id){return invocation(tx,id);}
    /** Result/history access preserves current authority without renewing an ended execution lease. */
    void requireMetadataFresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation invocation){
        fresh(tenant,tx,invocation,now(tx),true);
    }
    private void executable(Store.Session tx,EnterpriseInvocation i,long now){
        if(new OperationFinalizer(codec).refresh(tx,i.id(),now).state()==EnterpriseOperationState.RECONCILIATION_REQUIRED)
            throw ManagementAccess.denied();
    }
    void requireFresh(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation invocation){
        long now=now(tx);fresh(tenant,tx,invocation,now);executable(tx,invocation,now);
    }
    EnterpriseInvocation secretAuthority(Ids.TenantId tenant,Store.Session tx,String device,String invocationId,String name,String fencingToken){
        var i=invocation(tx,invocationId);
        // Revalidate current authority before considering caller-supplied fencing claims.
        fresh(tenant,tx,i,now(tx));executable(tx,i,now(tx));
        if(fencingToken==null||fencingToken.isEmpty()||fencingToken.length()>128||i.reservedNonce()==null
            ||!java.security.MessageDigest.isEqual(i.reservedNonce().getBytes(java.nio.charset.StandardCharsets.UTF_8),fencingToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            ||i.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(i.evaluation().context().deviceId())
            ||i.evaluation().resources().stream().noneMatch(r->r.kind()==ResourceKind.CUSTOM&&r.locator().equals("secret://"+name)))throw ManagementAccess.denied();
        var nonce=tx.enterprise().nonce(i.reservedNonce());
        // Consumption starts the attempt. Its admission expiry does not shorten a running invocation's authority.
        if(nonce==null||nonce.consumedAt()==null||!nonce.invocationId().equals(i.id())||nonce.authorizationEpoch()!=i.authorizationEpoch())throw ManagementAccess.denied();
        return i;
    }
    void checkpoint(Ids.TenantId tenant,Store.Session tx,String deviceId,EnterpriseInvocation invocation,AuthorizationRequest request){
        if(invocation.state()!=EnterpriseInvocationState.EXECUTING||!deviceId.equals(invocation.evaluation().context().deviceId())
            ||!request.toolId().equals(invocation.evaluation().toolId())||!request.action().equals(invocation.evaluation().action())
            ||!DirectoryService.digest(codec.json(request.arguments())).equals(invocation.evaluation().argumentsDigest()))throw ManagementAccess.denied();
        fresh(tenant,tx,invocation,now(tx));executable(tx,invocation,now(tx));
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
            fresh(tenant,tx,i,now);executable(tx,i,now);tx.enterprise().consumeNonce(claims.nonce(),now);var next=changed(i,EnterpriseInvocationState.EXECUTING,i.reservedNonce(),i.completedResources(),null);tx.enterprise().saveInvocation(next,now,i.revision());
            tx.audit(DirectoryService.digest(authenticatedDevice),"PERMIT_CONSUME","invocation:"+i.id(),next.revision(),e.context().requestId(),i.requestDigest(),evidence(next));return reply(next,next.revision());});
    }
    /** v2 consumption by a Tool Host (platform-requirements.md ÃÆ’Æ’â€Å¡ÃÆ’â€Å¡§11.3): the host's certificate names the device the permit is addressed to.
     *  The fencing token is the permit's nonce; reports and secret deliveries carry it back. */
    public Store.Reply consumeInvocation(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document){var request=codec.model(document,io.ololabs.toolgate.contracts.v2.PermitConsumption.class);var claims=signer.verifyInvocation(request.permit());var target=claims.target();
        return authoritative(tenant,"device-effect",request.invocationId(),DirectoryService.digest(document),tx->{long now=now(tx);String authenticatedDevice=certificate.apply(tx);var i=invocation(tx,request.invocationId());var e=i.evaluation();var lease=tx.enterprise().nonce(claims.nonce());
            if(!issuer.equals(claims.iss())||!authenticatedDevice.equals(claims.aud())||!authenticatedDevice.equals(e.context().deviceId())||tx.packages().toolHost(authenticatedDevice)==null
                ||!claims.invocationId().equals(i.id())||!request.argumentsDigest().equals(claims.argumentsDigest())||!request.toolDescriptorDigest().equals(target.toolDescriptorDigest())
                ||!request.packageDigest().equals(target.packageDigest())||!request.artifactDigest().equals(target.artifactDigest())||!target.packageDigest().equals("sha256:"+e.packageDigest())||!target.toolId().equals(e.toolId())
                ||claims.iat()>now||claims.exp()<=now||claims.exp()-claims.iat()>10000||!java.util.Objects.equals(claims.identity().authorizationEpoch(),i.authorizationEpoch())
                ||i.state()!=EnterpriseInvocationState.RESERVED||!claims.nonce().equals(i.reservedNonce())
                ||lease==null||lease.consumedAt()!=null||!lease.invocationId().equals(i.id())||!java.util.Objects.equals(lease.authorizationEpoch(),i.authorizationEpoch())||!java.util.Objects.equals(lease.expiresAt(),claims.exp()))throw ManagementAccess.denied();
            fresh(tenant,tx,i,now);executable(tx,i,now);tx.enterprise().consumeNonce(claims.nonce(),now);var next=changed(i,EnterpriseInvocationState.EXECUTING,i.reservedNonce(),i.completedResources(),null);tx.enterprise().saveInvocation(next,now,i.revision());
            tx.audit(DirectoryService.digest(authenticatedDevice),"PERMIT_CONSUME","invocation:"+i.id(),next.revision(),e.context().requestId(),i.requestDigest(),evidence(next));
            long leaseExpiry=Math.min(now+30000,i.expiresAtUnixMs());
            tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease(i.id(),claims.nonce(),authenticatedDevice,leaseExpiry));
            return reply(new io.ololabs.toolgate.contracts.v2.PermitConsumed(i.id(),claims.nonce(),leaseExpiry),next.revision());});
    }
    /** Renewal is never replay-cached: each request checks current authority in the tenant transaction. */
    public Store.Reply heartbeat(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String id,String document){
        var request=codec.model(document,io.ololabs.toolgate.contracts.v2.LeaseHeartbeat.class);
        if(!Ids.valid(id).equals(request.invocationId())||request.progress()!=null||request.inputRequest()!=null)throw Failure.validation();
        return store.transaction(tenant,true,tx->{
            long now=now(tx);String device=certificate.apply(tx);var i=invocation(tx,id);var lease=tx.enterprise().executionLease(id);
            if(lease==null||!device.equals(lease.deviceId())||!device.equals(i.evaluation().context().deviceId())
                ||!request.fencingToken().equals(lease.fencingToken())||!request.fencingToken().equals(i.reservedNonce()))throw ManagementAccess.denied();
            boolean cancel=i.state()!=EnterpriseInvocationState.EXECUTING||lease.expiresAt()<=now||i.expiresAtUnixMs()<=now;
            if(!cancel)try{fresh(tenant,tx,i,now);executable(tx,i,now);}catch(Failure denied){if(denied.status()!=403)throw denied;cancel=true;}
            long expiry=cancel?Math.min(now,lease.expiresAt()):Math.min(now+30000,i.expiresAtUnixMs());
            tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease(id,lease.fencingToken(),device,expiry));
            tx.audit(DirectoryService.digest(device),cancel?"EXECUTION_LEASE_REFUSED":"EXECUTION_LEASE_RENEW","invocation:"+id,i.revision(),i.evaluation().context().requestId(),i.requestDigest());
            return reply(new io.ololabs.toolgate.contracts.v2.LeaseRenewal(id,lease.fencingToken(),expiry,cancel,null),i.revision());
        });
    }
    /** v2 result from a Tool Host: COMPLETED completed every resource and FAILED none (the host reports a lost run of a
     *  tool that needs the execution ledger as OUTCOME_UNKNOWN); anything else may have had effects. */
    public Store.Reply reportInvocation(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document,String fencingToken){var report=codec.model(document,io.ololabs.toolgate.contracts.v2.InvocationResultReport.class);
        if(!report.fencingToken().equals(fencingToken))throw ManagementAccess.denied();
        return authoritative(tenant,"device-effect",report.invocationId(),DirectoryService.digest(document),tx->{String device=certificate.apply(tx);var i=invocation(tx,report.invocationId());
            requireReportedAttempt(tx,i,device,report.fencingToken());
            tx.enterprise().saveExecutionEvidence(i.id(),report.fencingToken(),DirectoryService.digest(document),document,now(tx));
            if(!report.fencingToken().equals(i.reservedNonce())||i.state()!=EnterpriseInvocationState.EXECUTING){
                // Evidence may arrive after cancellation, expiry, or reconciliation. Keep
                // the report without silently overriding a reviewed business outcome.
                tx.audit(DirectoryService.digest(device),"LATE_EXECUTION_EVIDENCE","invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),DirectoryService.digest(document));
                new OperationFinalizer(codec).refresh(tx,i.id(),now(tx));
                return reply(i,i.revision());
            }
            var result=report.result()==null?null:(com.fasterxml.jackson.databind.JsonNode)codec.value(codec.json(report.result()));
            var state=switch(report.outcome()){case COMPLETED->report.result()!=null&&Boolean.TRUE.equals(report.result().isError())?EnterpriseInvocationState.FAILED:EnterpriseInvocationState.SUCCEEDED;case FAILED->EnterpriseInvocationState.FAILED;default->EnterpriseInvocationState.OUTCOME_UNKNOWN;};
            if(state==EnterpriseInvocationState.SUCCEEDED&&result==null)throw Failure.validation();
            var effect=new EnterpriseEffectReport(i.id(),i.state()==EnterpriseInvocationState.EXECUTING?i.revision():i.revision()-1,state,state==EnterpriseInvocationState.SUCCEEDED?i.evaluation().resources():List.of(),
                state==EnterpriseInvocationState.SUCCEEDED?Canonical.digest(result).substring(7):null);
            var next=reportInSession(tenant,tx,device,effect);
            Integer attempt=tx.runEvents().attempt(i.id(),report.fencingToken());new RunEvents(store,codec,this).recordResult(tx,i,attempt==null?attemptOrdinal(tx,i.id(),report.fencingToken()):attempt,report.fencingToken(),device,report.result(),clock.millis());
            // A call the Gateway took to the host directly: keep its result as a finished dispatch, as for a call the host
            // fetched, so a repeated call returns it instead of running the tool again.
            if(tx.packages().relayed(i.id())==null){
                var finished=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();var status=finished.putObject("status");
                status.put("invocationId",i.id()).put("hostId",device).put("state","FINISHED");
                status.set("result",(com.fasterxml.jackson.databind.JsonNode)codec.value(codec.json(new io.ololabs.toolgate.contracts.v2.ResultMessage(report.invocationId(),report.fencingToken(),report.outcome(),report.result()))));
                tx.packages().saveRelayed(new PackageStore.Relayed(i.id(),device,"FINISHED",i.expiresAtUnixMs(),codec.json(finished)));
            }
            return reply(next,next.revision());});
    }
    int attemptOrdinal(Store.Session tx,String operation,String fence){
        var history=tx.enterprise().attempts(operation,clock.millis());
        for(int index=0;index<history.size();index++)if(history.get(index).id().equals(fence))return index+1;
        return 1; // Historical metadata without a journal cannot qualify for recovery.
    }
    private void requireReportedAttempt(Store.Session tx,EnterpriseInvocation i,String device,String fence){
        if(fence==null||!device.equals(i.evaluation().context().deviceId())||tx.packages().toolHost(device)==null)
            throw ManagementAccess.denied();
        var admission=tx.enterprise().nonce(fence);
        if(admission==null||admission.consumedAt()==null||!admission.invocationId().equals(i.id())
                ||tx.enterprise().attempts(i.id(),clock.millis()).stream().noneMatch(a->a.id().equals(fence)))
            throw ManagementAccess.denied();
    }
    /** Receipt only: never changes the current invocation, dispatch pointer, result or execution authority. */
    EnterpriseInvocation retainHistoricalExecutionEvidence(Ids.TenantId tenant,Store.Session tx,String device,
                                                            String id,String fence,String document){
        var i=invocation(tx,id);
        if(Objects.equals(i.reservedNonce(),fence))throw Failure.conflict();
        requireReportedAttempt(tx,i,device,fence);
        tx.enterprise().saveExecutionEvidence(id,fence,DirectoryService.digest(document),document,now(tx));
        tx.audit(DirectoryService.digest(device),"LATE_EXECUTION_EVIDENCE","invocation:"+id,i.revision(),
                i.evaluation().context().requestId(),DirectoryService.digest(document));
        new OperationFinalizer(codec).refresh(tx,i.id(),now(tx));
        return i;
    }
    private void reviewer(Store.Session tx,String user,EnterpriseInvocation i,String obligation,long now){
        var d=tx.load();var e=i.evaluation();if(new ManagementAccess(codec).superAdministrator(d,user)){var policyEntry=d.entries().get(Kind.POLICY.id(obligation));if(policyEntry==null||!policyEntry.enabled()||codec.model(policyEntry.document(),ControlPolicy.class).decision()!=Decision.ASK)throw ManagementAccess.denied();return;}if(user.equals(e.context().userId()))throw ManagementAccess.denied();
        var agentIds=new ArrayList<String>();e.context().chain().forEach(h->agentIds.add(h.agentId()));if(e.context().agentId()!=null)agentIds.add(e.context().agentId());
        for(var agent:agentIds){var a=codec.model(d.entries().get(Kind.AGENT.id(agent)).document(),ControlAgent.class);if(user.equals(a.ownerUserId()))throw ManagementAccess.denied();}
        var entry=d.entries().get(Kind.POLICY.id(obligation));if(entry==null||!entry.enabled())throw ManagementAccess.denied();var policy=codec.model(entry.document(),ControlPolicy.class);
        var teams=GroupGraph.memberships(d,codec,Kind.USER,user,true);if(policy.decision()!=Decision.ASK||!EnterpriseEvaluator.intersects(policy.approverTeams(),teams))throw ManagementAccess.denied();
        var binding=codec.model(d.entries().get(Kind.BINDING.id(e.context().bindingId())).document(),ControlExecutionBinding.class);var access=new ManagementAccess(codec);
        access.require(d,user,"operation.approve",Kind.TOOL_GROUP,binding.toolGroupId(),now);access.require(d,user,"operation.approve",Kind.DEVICE_GROUP,binding.deviceGroupId(),now);
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
            if(state==EnterpriseApprovalState.APPROVED&&EffectConsents.approved(tx,codec,i,now)){if(i.state()!=EnterpriseInvocationState.PENDING_APPROVAL)throw Failure.conflict();var approved=changed(i,EnterpriseInvocationState.QUEUED,null,List.of(),null);
                // The approved operation resumes (M5.5) with a fresh dispatch window, however long the review took.
                var queued=new EnterpriseInvocation(approved.id(),approved.requestDigest(),approved.evaluation(),approved.state(),approved.revision(),approved.authorizationEpoch(),approved.reservedNonce(),consentDeadline(tx,i,now+lifetime),approved.completedResources(),approved.downstreamIdempotencyKey(),approved.resultDigest(),approved.diagnosticId(),approved.reconciliation());tx.enterprise().saveInvocation(queued,now,i.revision());}
            else if((state==EnterpriseApprovalState.DENIED||state==EnterpriseApprovalState.REVOKED)&&Set.of(EnterpriseInvocationState.PENDING_APPROVAL,EnterpriseInvocationState.QUEUED,EnterpriseInvocationState.RESERVED,EnterpriseInvocationState.DISPATCHED,EnterpriseInvocationState.EXECUTING).contains(i.state())){
                // End the original wait atomically with the review. Once dispatch may have started, cancellation
                // cannot prove the effect did not happen: retain uncertainty for reconciliation instead.
                var endedState=state==EnterpriseApprovalState.DENIED?EnterpriseInvocationState.FAILED:
                    Set.of(EnterpriseInvocationState.DISPATCHED,EnterpriseInvocationState.EXECUTING).contains(i.state())?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.CANCELLED;
                var ended=changed(i,endedState,i.reservedNonce(),i.completedResources(),i.resultDigest());
                tx.enterprise().saveInvocation(ended,now,i.revision());audit(tx,actor,"INVOCATION_APPROVAL_"+state.name(),ended);
            }
            tx.audit(actor.id(),"OPERATION_"+state.name()+(new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId())?"_SUPER_ADMIN":""),"approval:"+a.id(),next.revision(),i.evaluation().context().requestId(),a.requestDigest());var reply=reply(next,next.revision());tx.remember(actor.id(),key,hash,reply);return reply;});
    }
    public Store.Reply report(Ids.TenantId tenant,String authenticatedDevice,String document){
        return report(tenant,tx->authenticatedDevice,document);
    }
    public Store.Reply report(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String document){var report=codec.model(document,EnterpriseEffectReport.class);
        return authoritative(tenant,"device-effect",report.invocationId(),DirectoryService.digest(document),tx->{var next=reportInSession(tenant,tx,certificate.apply(tx),report);
            if(next.state()==EnterpriseInvocationState.SUCCEEDED)try{fresh(tenant,tx,next,now(tx));}catch(Failure denied){if(denied.status()!=403)throw denied;tx.audit("device-effect","RESULT_ACCESS_DENIED","invocation:"+next.id(),next.revision(),next.evaluation().context().requestId(),next.requestDigest(),evidence(next));return new Store.Reply(403,codec.json(Map.of("code",ErrorCode.FORBIDDEN)),next.revision());}
            return reply(next,next.revision());});
    }
    EnterpriseInvocation reportInSession(Ids.TenantId tenant,Store.Session tx,String authenticatedDevice,EnterpriseEffectReport report){
        long now=now(tx);var i=invocation(tx,report.invocationId());if(!authenticatedDevice.equals(i.evaluation().context().deviceId()))throw ManagementAccess.denied();
        if(i.revision()==report.expectedRevision()+1&&i.state()==report.state()&&i.completedResources().equals(report.completedResources())&&Objects.equals(i.resultDigest(),report.resultDigest()))return i;
        if(!java.util.Objects.equals(i.revision(),report.expectedRevision())||i.state()!=EnterpriseInvocationState.EXECUTING)throw Failure.conflict();
            var completed=report.completedResources();var all=i.evaluation().resources();if(new HashSet<>(completed).size()!=completed.size()||!all.containsAll(completed)||!completed.containsAll(i.completedResources()))throw Failure.validation();
            switch(report.state()){
                case EXECUTING->{fresh(tenant,tx,i,now);executable(tx,i,now);}
                case SUCCEEDED->{if(!new HashSet<>(completed).equals(new HashSet<>(all))||report.resultDigest()==null)throw Failure.validation();}
                case PARTIAL->{if(completed.isEmpty()||completed.size()==all.size())throw Failure.validation();}
                case FAILED->{if(!completed.isEmpty())throw Failure.validation();}
                case OUTCOME_UNKNOWN->{}
                default->throw Failure.validation();
            }
            var next=changed(i,report.state(),i.reservedNonce(),completed,report.resultDigest());tx.enterprise().saveInvocation(next,now,i.revision());tx.audit(DirectoryService.digest(authenticatedDevice),"EFFECT_"+report.state().name(),"invocation:"+i.id(),next.revision(),i.evaluation().context().requestId(),i.requestDigest(),evidence(next));
            new OperationFinalizer(codec).refresh(tx,next.id(),now);return next;
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
    public Store.Reply cancelFromGateway(DirectoryService.Actor actor,String document){
        gateway(actor);var c=codec.model(document,io.ololabs.toolgate.contracts.v2.RequestContext.class);
        if(!actor.tenant().value().equals(c.tenantId()))throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{var i=tx.enterprise().invocation(c.requestId());
            if(i==null&&c.requestId().startsWith("op-"))throw new Failure(ErrorCode.NOT_FOUND,404,"Operation not found");
            if(i!=null){var old=i.evaluation().context();if(old.mode()!=c.mode()||!Objects.equals(old.userId(),c.userId())||!Objects.equals(old.agentId(),c.agentId())||!old.chain().stream().map(h->h.agentId()).toList().equals(c.chain().stream().map(h->h.agentId()).toList()))throw ManagementAccess.denied();}
            tx.enterprise().cancelOperation(c.requestId());
            if(i!=null&&!Set.of(EnterpriseInvocationState.SUCCEEDED,EnterpriseInvocationState.FAILED,EnterpriseInvocationState.PARTIAL,EnterpriseInvocationState.OUTCOME_UNKNOWN,EnterpriseInvocationState.CANCELLED,EnterpriseInvocationState.EXPIRED).contains(i.state())){
                var next=changed(i,i.state()==EnterpriseInvocationState.EXECUTING?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.CANCELLED,i.reservedNonce(),i.completedResources(),i.resultDigest());
                tx.enterprise().saveInvocation(next,now(tx),i.revision());audit(tx,actor,"INVOCATION_CANCEL",next);
                var a=tx.enterprise().approval(approvalId(i.id()));if(a!=null)tx.enterprise().saveApproval(new EnterpriseApproval(a.id(),a.approvalType(),a.invocationId(),a.requestDigest(),a.authorizationEpoch(),a.directoryRevision(),a.obligationIds(),a.reviews(),EnterpriseApprovalState.CANCELLED,a.revision()+1,a.expiresAtUnixMs()),a.revision());
            }
            return reply(Map.of("cancelled",true),tx.load().revision());});
    }
    public Store.Reply cancel(DirectoryService.Actor actor,String id,long expected){if(actor.userId()==null)throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var i=invocation(tx,id);if(i.revision()!=expected)throw Failure.conflict();
            if(!actor.userId().equals(i.evaluation().context().userId())){var binding=codec.model(tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);new ManagementAccess(codec).require(tx.load(),actor.userId(),"operation.cancel",Kind.TOOL_GROUP,binding.toolGroupId(),now);new ManagementAccess(codec).require(tx.load(),actor.userId(),"operation.cancel",Kind.DEVICE_GROUP,binding.deviceGroupId(),now);}
            if(Set.of(EnterpriseInvocationState.SUCCEEDED,EnterpriseInvocationState.FAILED,EnterpriseInvocationState.PARTIAL,EnterpriseInvocationState.OUTCOME_UNKNOWN,EnterpriseInvocationState.CANCELLED,EnterpriseInvocationState.EXPIRED).contains(i.state()))throw Failure.conflict();
            var next=changed(i,i.state()==EnterpriseInvocationState.EXECUTING?EnterpriseInvocationState.OUTCOME_UNKNOWN:EnterpriseInvocationState.CANCELLED,i.reservedNonce(),i.completedResources(),i.resultDigest());tx.enterprise().saveInvocation(next,now,i.revision());
            var a=tx.enterprise().approval(approvalId(i.id()));if(a!=null)tx.enterprise().saveApproval(new EnterpriseApproval(a.id(),a.approvalType(),a.invocationId(),a.requestDigest(),a.authorizationEpoch(),a.directoryRevision(),a.obligationIds(),a.reviews(),EnterpriseApprovalState.CANCELLED,a.revision()+1,a.expiresAtUnixMs()),a.revision());
            audit(tx,actor,"INVOCATION_CANCEL",next);return reply(next,next.revision());});
    }
    /** Reading an invocation/result checks current authority again; old approval never revives a grant. */
    EnterpriseInvocation projected(Store.Session tx,EnterpriseInvocation i,long now){
        var outcome=new OperationFinalizer(codec).refresh(tx,i.id(),now);
        return new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),i.state(),i.revision(),
                i.authorizationEpoch(),i.reservedNonce(),i.expiresAtUnixMs(),i.completedResources(),
                i.downstreamIdempotencyKey(),i.resultDigest(),i.diagnosticId(),i.reconciliation(),outcome);
    }
    /** Authorized, bounded metadata. A cursor is pinned to one derived evidence revision. */
    public Store.Reply history(DirectoryService.Actor actor,String id,String cursor,int limit){
        historyPageInput(cursor,limit);
        return store.transaction(actor.tenant(),true,tx->{
            long now=now(tx);var i=invocation(tx,id);requireRead(tx,actor,i,now);
            return historyIn(tx,i,cursor,limit,now);
        });
    }
    private void historyPageInput(String cursor,int limit){
        if(limit<1||limit>100||cursor!=null&&!cursor.matches("[1-9][0-9]{0,15}:[0-9]{1,4}"))throw Failure.validation();
    }
    private Store.Reply historyIn(Store.Session tx,EnterpriseInvocation i,String cursor,int limit,long now){
            var outcome=new OperationFinalizer(codec).refresh(tx,i.id(),now);
            int offset=0;
            if(cursor!=null){
                String[] parts=cursor.split(":");long revision=Long.parseLong(parts[0]);
                offset=Integer.parseInt(parts[1]);
                if(revision!=outcome.revision()||offset>4096)throw Failure.conflict();
            }
            var attempts=tx.enterprise().attempts(i.id(),now);var entries=new ArrayList<EnterpriseAttemptHistory>();
            var facts=tx.enterprise().finalizationFacts(i.id(),now);
            for(int n=0;n<Math.min(attempts.size(),64);n++){
                var a=attempts.get(n);var seal=tx.enterprise().manifestSeal(i.id(),a.id());
                var settlement=tx.enterprise().attemptSettlement(i.id(),a.id());var reviewed=tx.enterprise().attemptReview(i.id(),a.id());
                var scope=reviewed!=null?EnterpriseAttemptEffectScope.REVIEWER_WITNESSED:tx.enterprise().recoveryScope(a.id())!=null?EnterpriseAttemptEffectScope.QUALIFIED_BROKER_ONLY
                        :a.consumedAt()==null?EnterpriseAttemptEffectScope.ADMISSION_ONLY:EnterpriseAttemptEffectScope.LEGACY_UNKNOWN;
                String reported=facts.attempts().stream().filter(f->f.id().equals(a.id())).map(OperationFinalizationPolicy.Attempt::state).findFirst().orElse(a.state());
                entries.add(new EnterpriseAttemptHistory(a.id(),(long)n+1,EnterpriseInvocationState.valueOf(reported),
                        a.consumedAt()!=null,a.issuedAt(),a.expiresAt(),a.evidenceCount(),scope,seal!=null,
                        seal==null?0L:(long)seal.effectCount(),seal==null?null:seal.digest(),settlement!=null||reviewed!=null,
                        reviewed!=null?EnterpriseOutcomeGrade.REVIEWER_CONFIRMED:settlement==null||Set.of("SUCCEEDED","FAILED").contains(reported)?EnterpriseOutcomeGrade.REPORTED:EnterpriseOutcomeGrade.CONTROL_PROVEN));
            }
            var rows=tx.enterprise().effectHistory(i.id(),offset,limit+1);
            boolean more=rows.size()>limit;var effects=more?rows.subList(0,limit):rows;
            String next=more&&offset+limit<=4096?outcome.revision()+":"+(offset+limit):null;
            return reply(new EnterpriseOperationHistoryPage(outcome,entries,effects,next,
                    !outcome.journalComplete()||attempts.size()>64||more&&next==null),outcome.revision());
    }
    private EnterpriseInvocation gatewayRead(Store.Session tx,Ids.TenantId tenant,String id,
            io.ololabs.toolgate.contracts.v2.RequestContext c,long time){
        if(!tenant.value().equals(c.tenantId())||!id.equals(c.requestId()))throw ManagementAccess.denied();
        var i=tx.enterprise().invocation(Ids.valid(id));
        if(i==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Operation not found");
        var old=i.evaluation().context();
        if(old.mode()!=c.mode()||!Objects.equals(old.userId(),c.userId())||!Objects.equals(old.agentId(),c.agentId())
                ||!Objects.equals(old.workloadBindingId(),c.workloadBindingId())||!old.chain().equals(c.chain())
                ||!old.bindingId().equals(c.bindingId()))
            throw new Failure(ErrorCode.NOT_FOUND,404,"Operation not found");
        if(c.mode()==RequestMode.HUMAN&&!gatewayUser(c)
                ||c.mode()==RequestMode.DELEGATED&&(onBehalfOf(tx.load(),c)&&!gatewayDelegated(c)||laundered(c)))
            throw ManagementAccess.denied();
        if(c.expiresAtUnixMs()!=null&&c.expiresAtUnixMs()<=time)throw ManagementAccess.denied();
        var context=new RuntimeAccess(codec).boundContext(tenant,tx.load(),c,old.deviceId());var e=i.evaluation();
        var current=new EnterpriseEvaluation(context,e.toolId(),e.action(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest(),
                time,tx.load().revision(),true,e.amountMinorUnits(),e.operation());
        decide(tenant,tx,current,time,true);return invocation(tx,id);
    }
    /** Fresh caller context authorizes retained metadata, never a reservation or execution. */
    public Store.Reply readFromGateway(DirectoryService.Actor actor,String id,String document){
        gateway(actor);Ids.valid(id);var context=codec.model(document,io.ololabs.toolgate.contracts.v2.RequestContext.class);
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var i=gatewayRead(tx,actor.tenant(),id,context,time);
            return reply(projected(tx,i,time),i.revision());});
    }
    public Store.Reply historyFromGateway(DirectoryService.Actor actor,String id,String document,String cursor,int limit){
        gateway(actor);Ids.valid(id);historyPageInput(cursor,limit);
        var context=codec.model(document,io.ololabs.toolgate.contracts.v2.RequestContext.class);
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var i=gatewayRead(tx,actor.tenant(),id,context,time);
            return historyIn(tx,i,cursor,limit,time);});
    }
    private long consentDeadline(Store.Session tx,EnterpriseInvocation i,long requested){
        var p=tx.effectConsents().forOperation(i.id());return p==null?requested:Math.min(requested,p.expiresAt());
    }
    public Store.Reply effectConsent(DirectoryService.Actor actor,String id){
        if(actor.userId()==null)throw ManagementAccess.denied();
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var i=invocation(tx,id);requireRead(tx,actor,i,time);
            if(!actor.userId().equals(i.evaluation().context().userId()))EffectConsents.reviewer(tx,codec,actor.userId(),i,time);
            var p=tx.effectConsents().forOperation(i.id());if(p==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Effect consent not found");
            var view=EffectConsents.view(tx,codec,p);return reply(view,view.revision());});
    }
    public Store.Reply decideEffectConsent(DirectoryService.Actor actor,String id,String document,boolean revoke){
        if(actor.userId()==null)throw ManagementAccess.denied();
        var request=codec.model(document,io.ololabs.toolgate.contracts.v2.EffectConsentApprovalRequest.class);
        return store.transaction(actor.tenant(),true,tx->{long time=now(tx);var i=invocation(tx,id);
            EffectConsents.reviewer(tx,codec,actor.userId(),i,time);
            decide(actor.tenant(),tx,i.evaluation(),time,true);
            if(i.authorizationEpoch()!=tx.enterprise().authorizationEpoch())throw ManagementAccess.denied();
            var p=tx.effectConsents().forOperation(i.id());if(p==null)throw EffectConsents.required();
            var view=EffectConsents.view(tx,codec,p);
            if(!view.revision().equals(request.expectedRevision())||!p.digest().equals(request.previewDigest()))throw Failure.conflict();
            if(revoke)tx.effectConsents().revoke(p.consentId(),actor.userId(),time);
            else{
                if(i.state()!=EnterpriseInvocationState.PENDING_APPROVAL||view.state()!=io.ololabs.toolgate.contracts.v2.EffectConsentState.PENDING)throw Failure.conflict();
                var credential=tx.enterprise().userCredentialDeadline(i.id());
                if(i.expiresAtUnixMs()<=time||credential!=null&&credential<=time)throw ManagementAccess.denied();
                tx.effectConsents().approve(p.consentId(),p.digest(),actor.userId(),i.authorizationEpoch(),time);
                var decision=decide(actor.tenant(),tx,i.evaluation(),time,true);var policy=tx.enterprise().approval(approvalId(i.id()));
                if(decision.decision()!=Decision.ASK||policy!=null&&policy.state()==EnterpriseApprovalState.APPROVED){
                    fresh(actor.tenant(),tx,i,time);
                    var queued=new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),EnterpriseInvocationState.QUEUED,i.revision()+1,
                        i.authorizationEpoch(),null,consentDeadline(tx,i,time+lifetime),i.completedResources(),i.downstreamIdempotencyKey(),i.resultDigest(),i.diagnosticId(),i.reconciliation());
                    tx.enterprise().saveInvocation(queued,time,i.revision());
                }
            }
            var next=EffectConsents.view(tx,codec,p);
            tx.audit(actor.id(),revoke?"EFFECT_CONSENT_REVOKE":"EFFECT_CONSENT_APPROVE","invocation:"+i.id(),next.revision(),i.evaluation().context().requestId(),p.digest().substring(7));
            return reply(next,next.revision());
        });
    }
    public Store.Reply get(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),true,tx->{long now=now(tx);var i=invocation(tx,id);requireRead(tx,actor,i,now);return reply(projected(tx,i,now),i.revision());});}
    /** The owner's current authorization is re-evaluated on every read, so revocation ends access to a running stream;
     *  anyone else needs operation.read on both the Tool Group and the Device Group of the invocation's binding. */
    void requireRead(Store.Session tx,DirectoryService.Actor actor,EnterpriseInvocation i,long now){if(actor.userId()!=null&&actor.userId().equals(i.evaluation().context().userId())){decide(actor.tenant(),tx,i.evaluation(),now,true);}else if(actor.userId()!=null){var b=codec.model(tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId())).document(),ControlExecutionBinding.class);new ManagementAccess(codec).require(tx.load(),actor.userId(),"operation.read",Kind.TOOL_GROUP,b.toolGroupId(),now);new ManagementAccess(codec).require(tx.load(),actor.userId(),"operation.read",Kind.DEVICE_GROUP,b.deviceGroupId(),now);}else {gateway(actor);fresh(actor.tenant(),tx,i,now,true);}}
    long clock(Store.Session tx){return now(tx);}
    long clock(){return clock.millis();}
    /** Own-device outcome recovery is metadata only; it never renews or reissues a permit. */
    public Store.Reply deviceOutcome(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,String id){
        Ids.valid(id);sweep(tenant);return store.transaction(tenant,false,tx->{var device=certificate.apply(tx);var i=invocation(tx,id);if(!device.equals(i.evaluation().context().deviceId()))throw ManagementAccess.denied();return reply(i,i.revision());});
    }
    /** Bounded durable watchdog. Commit expiry separately from rejected authorizations. */
    public int sweep(Ids.TenantId tenant){return store.transaction(tenant,true,tx->{long time=now(tx);var expired=tx.enterprise().expiredInvocations(time,100);for(var i:expired)invocation(tx,i.id());tx.effects().expire(time);tx.enterprise().tombstoneExpiredBusinessKeys(time,100);return expired.size();});}
    private void scope(Store.Session tx,String user,EnterpriseInvocation i,String action,long time){
        action=action.equals("read")?"operation.read":ManagementAccess.permission(Kind.BINDING,action);
        var entry=tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId()));if(entry==null)throw ManagementAccess.denied();var b=codec.model(entry.document(),ControlExecutionBinding.class);var access=new ManagementAccess(codec);
        access.require(tx.load(),user,action,Kind.TOOL_GROUP,b.toolGroupId(),time);access.require(tx.load(),user,action,Kind.DEVICE_GROUP,b.deviceGroupId(),time);
    }
    public Store.Reply page(DirectoryService.Actor actor,String after,int limit){if(actor.userId()==null||limit<1||limit>100)throw Failure.validation();if(after!=null)Ids.valid(after);sweep(actor.tenant());
        return store.transaction(actor.tenant(),false,tx->{long time=clock.millis();String cursor=after==null?"":after;var values=new ArrayList<EnterpriseInvocation>();boolean more=false;
            for(int scan=0;scan<100;scan++){var rows=tx.enterprise().invocations(cursor,100);if(rows.isEmpty())break;for(var i:rows){cursor=i.id();try{scope(tx,actor.userId(),i,"read",time);values.add(i);}catch(Failure denied){if(denied.status()!=403)throw denied;}if(values.size()==limit){more=true;break;}}if(more||rows.size()<100)break;}
            return reply(new EnterpriseInvocationPage(values,more?cursor:null),tx.load().revision());});
    }
    private void independent(Store.Session tx,String user,EnterpriseInvocation i){if(new ManagementAccess(codec).superAdministrator(tx.load(),user))return;if(user.equals(i.evaluation().context().userId()))throw ManagementAccess.denied();var ids=new ArrayList<String>();i.evaluation().context().chain().forEach(h->ids.add(h.agentId()));if(i.evaluation().context().agentId()!=null)ids.add(i.evaluation().context().agentId());for(var id:ids){var entry=tx.load().entries().get(Kind.AGENT.id(id));if(entry!=null&&user.equals(codec.model(entry.document(),ControlAgent.class).ownerUserId()))throw ManagementAccess.denied();}}
    private void independentAttemptReviewer(Store.Session tx,String user,EnterpriseInvocation i){
        if(user==null)throw ManagementAccess.denied();
        var context=i.evaluation().context();var original=tx.enterprise().reviewOwners(i.id());
        if(original==null){
            if(context.mode()!=RequestMode.HUMAN||context.userId()==null||context.agentId()!=null||!context.chain().isEmpty())
                throw Failure.conflict();
            original=Set.of(context.userId());
        }
        if(original.contains(user)||user.equals(context.userId()))throw ManagementAccess.denied();
        var agents=new ArrayList<String>();if(context.agentId()!=null)agents.add(context.agentId());context.chain().forEach(h->agents.add(h.agentId()));
        for(String agent:agents){
            var entry=tx.load().entries().get(Kind.AGENT.id(agent));if(entry==null)throw ManagementAccess.denied();
            if(user.equals(codec.model(entry.document(),ControlAgent.class).ownerUserId()))throw ManagementAccess.denied();
        }
    }
    private void attemptReviewSource(Store.Session tx,EnterpriseInvocation i,EnterpriseReconciliationRequest request,long time){
        var attestation=request.attemptSettlement();
        if(attestation==null||!Boolean.TRUE.equals(attestation.noFurtherEffects())
                ||attestation.attemptIds().isEmpty()||attestation.attemptIds().size()>64
                ||new HashSet<>(attestation.attemptIds()).size()!=attestation.attemptIds().size())throw Failure.validation();
        if(!Objects.equals(i.revision(),request.expectedRevision()))throw Failure.conflict();
        var outcome=new OperationFinalizer(codec).refresh(tx,i.id(),time);
        if(outcome.state()!=EnterpriseOperationState.OUTCOME_UNKNOWN||!outcome.journalComplete()
                ||outcome.unresolvedEffectCount()!=0||!Objects.equals(outcome.revision(),attestation.expectedOutcomeRevision()))throw Failure.conflict();
        var attempts=tx.enterprise().attempts(i.id(),time);var lease=tx.enterprise().executionLease(i.id());
        for(String id:attestation.attemptIds()){
            var attempt=attempts.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(Failure::conflict);
            if(attempt.consumedAt()==null||tx.enterprise().manifestSeal(i.id(),id)==null
                    ||tx.enterprise().attemptSettlement(i.id(),id)!=null||tx.enterprise().attemptReview(i.id(),id)!=null
                    ||lease!=null&&lease.fencingToken().equals(id)&&lease.expiresAt()>time)throw Failure.conflict();
        }
    }
    public Store.Reply proposeReconciliation(DirectoryService.Actor actor,String id,String document,String key){
        if(actor.userId()==null)throw ManagementAccess.denied();Ids.valid(key);
        var request=codec.model(document,EnterpriseReconciliationRequest.class);sweep(actor.tenant());
        return store.transaction(actor.tenant(),true,tx->{
            long time=now(tx);var i=invocation(tx,id);scope(tx,actor.userId(),i,"operation.approve",time);
            independent(tx,actor.userId(),i);
            if(request.attemptSettlement()!=null)independentAttemptReviewer(tx,actor.userId(),i);
            String digest=DirectoryService.digest(id+"\n"+codec.json(request));
            String rid="reconcile-"+DirectoryService.digest(actor.id()+"\n"+key).substring(0,40);
            var old=tx.enterprise().reconciliation(rid);
            if(old!=null){if(!old.requestDigest().equals(digest))throw Failure.conflict();return reply(old,old.revision());}
            if(request.attemptSettlement()!=null)attemptReviewSource(tx,i,request,time);
            else if(i.state()!=EnterpriseInvocationState.OUTCOME_UNKNOWN||!Objects.equals(i.revision(),request.expectedRevision()))throw Failure.conflict();
            var all=i.evaluation().resources();var done=request.completedResources();
            if(new HashSet<>(done).size()!=done.size()||!all.containsAll(done)
                    ||request.attemptSettlement()==null&&!done.containsAll(i.completedResources()))throw Failure.validation();
            switch(request.state()){
                case SUCCEEDED->{if(!new HashSet<>(done).equals(new HashSet<>(all))||request.resultDigest()==null)throw Failure.validation();}
                case FAILED->{if(!done.isEmpty())throw Failure.validation();}
                case PARTIAL->{if(request.attemptSettlement()==null&&(done.isEmpty()||done.size()==all.size()))throw Failure.validation();}
            }
            var c=new EnterpriseReconciliation(rid,id,actor.userId(),request,digest,1L,null,EnterpriseReconciliationState.PENDING);
            tx.enterprise().saveReconciliation(c,0);
            tx.audit(actor.id(),"RECONCILIATION_PROPOSE",id,c.revision(),id,request.evidenceDigest());return reply(c,c.revision());
        });
    }
    public Store.Reply reconciliation(DirectoryService.Actor actor,String id){return store.transaction(actor.tenant(),false,tx->{var c=tx.enterprise().reconciliation(Ids.valid(id));if(c==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Reconciliation not found");var i=tx.enterprise().invocation(c.invocationId());scope(tx,actor.userId(),i,"read",clock.millis());return reply(c,c.revision());});}
    public Store.Reply reconcile(DirectoryService.Actor actor,String id,String document){
        if(actor.userId()==null)throw ManagementAccess.denied();
        var request=codec.model(document,EnterpriseReconciliationDecision.class);
        return store.transaction(actor.tenant(),true,tx->{
            long time=now(tx);var c=tx.enterprise().reconciliation(Ids.valid(id));
            if(c==null)throw new Failure(ErrorCode.NOT_FOUND,404,"Reconciliation not found");
            var i=invocation(tx,c.invocationId());
            scope(tx,actor.userId(),i,"operation.approve",time);scope(tx,c.requesterUserId(),i,"operation.approve",time);
            independent(tx,actor.userId(),i);independent(tx,c.requesterUserId(),i);
            boolean attemptScope=c.request().attemptSettlement()!=null;
            if(attemptScope){
                independentAttemptReviewer(tx,actor.userId(),i);independentAttemptReviewer(tx,c.requesterUserId(),i);
                if(actor.userId().equals(c.requesterUserId()))throw ManagementAccess.denied();
                // Exact duplicate decisions are historical reads, never another proof or execution.
                if(c.state()!=EnterpriseReconciliationState.PENDING&&Objects.equals(c.revision(),request.expectedRevision()+1)
                        &&actor.userId().equals(c.reviewerUserId())
                        &&c.state()==(request.approve()?EnterpriseReconciliationState.APPLIED:EnterpriseReconciliationState.DENIED))
                    return reply(c,c.revision());
            }else if(actor.userId().equals(c.requesterUserId())&&!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))
                throw ManagementAccess.denied();
            if(c.state()!=EnterpriseReconciliationState.PENDING||!Objects.equals(c.revision(),request.expectedRevision()))throw Failure.conflict();
            if(attemptScope){
                if(request.approve())attemptReviewSource(tx,i,c.request(),time);
            }else if(i.state()!=EnterpriseInvocationState.OUTCOME_UNKNOWN||!Objects.equals(i.revision(),c.request().expectedRevision()))
                throw Failure.conflict();
            var next=new EnterpriseReconciliation(c.id(),c.invocationId(),c.requesterUserId(),c.request(),c.requestDigest(),c.revision()+1,
                    actor.userId(),request.approve()?EnterpriseReconciliationState.APPLIED:EnterpriseReconciliationState.DENIED);
            tx.enterprise().saveReconciliation(next,c.revision());
            if(request.approve()){
                if(attemptScope){
                    for(String attempt:c.request().attemptSettlement().attemptIds())tx.enterprise().recordAttemptReview(i.id(),attempt,c.id(),time);
                    var outcome=new OperationFinalizer(codec).refresh(tx,i.id(),time);
                    if(!outcome.state().name().equals(c.request().state().name()))throw Failure.conflict();
                }else{
                    var resolved=changed(i,EnterpriseInvocationState.valueOf(c.request().state().name()),i.reservedNonce(),
                            c.request().completedResources(),c.request().resultDigest());
                    tx.enterprise().saveInvocation(resolved,time,i.revision());
                    new OperationFinalizer(codec).refresh(tx,i.id(),time);
                }
            }
            tx.audit(actor.id(),"RECONCILIATION_"+next.state()+(new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId())?"_SUPER_ADMIN":""),
                    i.id(),next.revision(),i.id(),c.request().evidenceDigest());return reply(next,next.revision());
        });
    }

}
