// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Downstream authority derived exclusively from an admitted executor invocation. */
public final class ServicePermits {
    public static final long MAX_LIFETIME_MS = 15000;
    private final Store store;
    private final Codec codec;
    private final EnterpriseOperations operations;
    public ServicePermits(Store store, Codec codec, EnterpriseOperations operations) {
        this.store=store;this.codec=codec;this.operations=operations;
    }
    /** Legacy readers remain compatible; writes require the immutable admitted HTTP request and owner protocol. */
    static void requireBoundWrite(String operation,String digest){
        if(!Set.of("memory.read","rag.query").contains(operation)&&digest==null)
            throw Failure.detail(409,ControlErrorDetailCode.BOUND_SERVICE_EFFECT_REQUIRED,List.of());
    }
    public Store.Reply issue(Ids.TenantId tenant, java.util.function.Function<Store.Session,String> certificate,
            String executorKeySha256, String document) {
        var request=codec.model(document,ServicePermitIssue.class);
        return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);
            var i=operations.invocationIn(tx,request.invocationId());
            var c=i.evaluation().context();
            if(i.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(c.deviceId())
                ||!Objects.equals(i.reservedNonce(),request.fencingToken()))throw ManagementAccess.denied();
            var admission=tx.enterprise().nonce(i.reservedNonce());
            if(admission==null||admission.consumedAt()==null)throw ManagementAccess.denied();
            operations.requireFresh(tenant,tx,i);
            requireBoundWrite(i.evaluation().action(),request.httpRequestDigest());
            ServiceRequestValidation.validate(tx,codec,operations.signer(),operations.issuer(),i,device,request,operations.clock(tx));
            String op=i.evaluation().action();
            if(c.mode()==RequestMode.DELEGATED || (c.mode()==RequestMode.SERVICE&&!op.equals("rag.update")))throw ManagementAccess.denied();
            if(!audience(op).equals(request.audience())||i.evaluation().resources().isEmpty()
                ||i.evaluation().resources().stream().anyMatch(r->r.kind()!=ResourceKind.CUSTOM||!r.locator().startsWith("olo://"+tenant.value()+"/")))throw ManagementAccess.denied();
            String subject=c.mode()==RequestMode.SERVICE?c.agentId():c.userId();
            if(subject==null)throw ManagementAccess.denied();
            long now=operations.clock(tx);
            Long credentialDeadline=tx.enterprise().userCredentialDeadline(i.id());
            if(c.mode()!=RequestMode.SERVICE&&credentialDeadline==null)throw ManagementAccess.denied();
            boolean read=Set.of("memory.read","rag.query").contains(op);
            boolean recovered=!read&&tx.enterprise().hasRecovery(i.id());
            String nonceId=read?UUID.randomUUID().toString():"service-"+DirectoryService.digest(i.id()+"\n"+request.audience()
                    +(recovered?"\n"+i.reservedNonce():"")).substring(0,48);
            var existing=tx.enterprise().nonce(nonceId);
            // Preserve existing capability IDs until a proof-bound whole-operation recovery.
            // A replacement gets its own nonce only after every predecessor was proven unable to write.
            if(existing!=null&&(existing.consumedAt()!=null||existing.expiresAt()<=now))throw ManagementAccess.denied();
            long expiry=existing==null?Math.min(now+MAX_LIFETIME_MS,Math.min(i.expiresAtUnixMs(),credentialDeadline==null?Long.MAX_VALUE:credentialDeadline)):existing.expiresAt();
            long issuedAt=existing==null?now:existing.issuedAt();
            if(expiry>i.expiresAtUnixMs())throw ManagementAccess.denied();
            String route=request.audience().name()+"/"+op;
            String effectId=read?null:"eff-"+DirectoryService.digest(codec.json(List.of("toolgate-effect-v1",tenant.value(),i.id(),route,"service-write")));
            var previous=read?null:tx.effects().current(effectId);
            Long generation=read?null:previous==null?1L:previous.generation();
            if(previous!=null&&!previous.binding().authNonce().equals(nonceId)){
                if(!recovered||!previous.state().equals("RELEASED")
                        ||!tx.enterprise().recoveryCoversAttempt(i.id(),previous.binding().attemptId()))throw Failure.conflict();
                generation=Math.addExact(previous.generation(),1);
            }
            var claims=new ServicePermitClaims(operations.issuer(),request.audience(),tenant.value(),subject,
                c.mode()==RequestMode.DELEGATED?c.agentId():null,c.mode(),op,i.evaluation().resources(),
                i.evaluation().argumentsDigest(),executorKeySha256,i.id(),nonceId,i.authorizationEpoch(),issuedAt,expiry,i.reservedNonce(),request.httpRequestDigest(),request.httpRequestDigest()==null?null:effectId,request.httpRequestDigest()==null?null:generation);
            String digest=DirectoryService.digest(codec.json(claims));
            if(existing!=null&&!existing.evaluationDigest().equals(digest))throw ManagementAccess.denied();
            var permit=operations.signer().signService(claims);
            if(existing==null)tx.enterprise().reserveNonce(new EnterpriseStore.Nonce(claims.nonce(),i.id(),digest,i.authorizationEpoch(),issuedAt,expiry,null));
            if(!read){
                var authorization=tx.effects().authorize(new EffectStore.Binding(effectId,i.id(),i.reservedNonce(),route,binding(claims),device,
                    request.httpRequestDigest()==null?i.reservedNonce():request.brokerInstanceId(),
                    request.httpRequestDigest()==null?nonceId:request.handlerId(),nonceId,expiry,expiry+60000),now);
                if(request.httpRequestDigest()!=null&&!authorization.generation().state().equals("AUTHORIZED"))throw Failure.conflict();
            }
            tx.audit(DirectoryService.digest(device),"SERVICE_PERMIT_ISSUE","invocation:"+i.id(),i.revision(),c.requestId(),i.requestDigest());
            return new Store.Reply(200,codec.json(permit),i.revision());
        });
    }
    /** One owner-bound CAS before the downstream wire send. A lost response never permits a replay. */
    public Store.Reply sending(Ids.TenantId tenant,java.util.function.Function<Store.Session,String> certificate,
            String executorKeySha256,String document){
        var request=codec.model(document,ServicePermitDispatch.class);
        var claims=operations.signer().verifyService(request.permit());
        return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);long now=operations.clock(tx);
            var i=operations.invocationIn(tx,claims.invocationId());var nonce=tx.enterprise().nonce(claims.nonce());
            if(!claims.iss().equals(operations.issuer())||!claims.tenantId().equals(tenant.value())
                ||!claims.executorKeySha256().equals(executorKeySha256)||claims.httpRequestDigest()==null
                ||Set.of("memory.read","rag.query").contains(claims.operation())||claims.iat()>now||claims.exp()<=now
                ||i.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(i.evaluation().context().deviceId())
                ||!Objects.equals(i.reservedNonce(),claims.executionNonce())||nonce==null||nonce.consumedAt()!=null
                ||!nonce.invocationId().equals(i.id())||!nonce.evaluationDigest().equals(DirectoryService.digest(codec.json(claims))))
                throw ManagementAccess.denied();
            operations.requireFresh(tenant,tx,i);
            var effect=tx.effects().byNonce(claims.nonce());
            if(effect==null||!effect.binding().operationId().equals(i.id())||!effect.binding().attemptId().equals(i.reservedNonce())
                ||!effect.binding().requestDigest().equals(binding(claims)))throw ManagementAccess.denied();
            tx.effects().sending(claims.nonce(),device,request.brokerInstanceId(),request.handlerId(),now);
            tx.audit(DirectoryService.digest(device),"SERVICE_PERMIT_SENDING","invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest());
            return new Store.Reply(200,codec.json(new ServicePermitDispatchAllowed(true)),i.revision());
        });
    }
    public Store.Reply consume(DirectoryService.Actor service, ServiceAudience audience, String document) {
        var request=codec.model(document,ServicePermitConsumption.class);
        var claims=operations.signer().verifyService(request.permit());
        requireBoundWrite(claims.operation(),claims.httpRequestDigest());
        return store.transaction(service.tenant(),true,tx->{
            long now=operations.clock(tx);
            var i=operations.invocationIn(tx,claims.invocationId());
            var nonce=tx.enterprise().nonce(claims.nonce());
            if(!claims.iss().equals(operations.issuer())||claims.aud()!=audience
                ||!claims.tenantId().equals(service.tenant().value())||claims.iat()>now||claims.exp()<=now
                ||claims.exp()<=claims.iat()||claims.exp()-claims.iat()>MAX_LIFETIME_MS
                ||!request.operation().equals(claims.operation())||!request.resources().equals(claims.resources())
                ||!Objects.equals(request.httpRequestDigest(),claims.httpRequestDigest())
                ||!request.requestFingerprint().equals(claims.requestFingerprint())||!request.executorKeySha256().equals(claims.executorKeySha256())
                ||i.state()!=EnterpriseInvocationState.EXECUTING||!Objects.equals(i.reservedNonce(),claims.executionNonce())
                ||nonce==null||nonce.consumedAt()!=null||!nonce.invocationId().equals(i.id())
                ||!nonce.evaluationDigest().equals(DirectoryService.digest(codec.json(claims)))
                ||nonce.authorizationEpoch()!=claims.authorizationEpoch())throw ManagementAccess.denied();
            operations.requireFresh(service.tenant(),tx,i);
            if(!Set.of("memory.read","rag.query").contains(claims.operation())){
                var effect=tx.effects().byNonce(claims.nonce());
                if(effect==null||!effect.binding().operationId().equals(i.id())||!effect.binding().attemptId().equals(i.reservedNonce())
                    ||claims.httpRequestDigest()!=null&&!effect.state().equals("SENDING"))throw ManagementAccess.denied();
                var consumed=tx.effects().consume(claims.nonce(),binding(claims),audience.name(),"legacy:"+claims.executorKeySha256(),now);
                if(!consumed.newAuthority())throw ManagementAccess.denied();
            }
            tx.enterprise().consumeNonce(claims.nonce(),now);
            tx.audit(service.id(),"SERVICE_PERMIT_CONSUME","invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest());
            return new Store.Reply(200,codec.json(new ServicePermitConsumed(true)),i.revision());
        });
    }
    /** Claim replay is status only: execution still requires the target's durable local ownership CAS. */
    public Store.Reply consumeClaim(DirectoryService.Actor service,ServiceAudience audience,String document){
        var submitted=codec.model(document,ServicePermitClaimConsumption.class);var request=submitted.request();
        var claims=operations.signer().verifyService(request.permit());
        return store.transaction(service.tenant(),true,tx->{
            long now=operations.clock(tx);var nonce=tx.enterprise().nonce(claims.nonce());
            var effect=tx.effects().byNonce(claims.nonce());
            if(!claims.iss().equals(operations.issuer())||claims.aud()!=audience
                ||!claims.tenantId().equals(service.tenant().value())||claims.httpRequestDigest()==null
                ||claims.effectId()==null||claims.authorizationGeneration()==null
                ||Set.of("memory.read","rag.query").contains(claims.operation())
                ||!request.operation().equals(claims.operation())||!request.resources().equals(claims.resources())
                ||!Objects.equals(request.httpRequestDigest(),claims.httpRequestDigest())
                ||!request.requestFingerprint().equals(claims.requestFingerprint())
                ||!request.executorKeySha256().equals(claims.executorKeySha256())
                ||nonce==null||!nonce.invocationId().equals(claims.invocationId())
                ||nonce.authorizationEpoch()!=claims.authorizationEpoch()
                ||!nonce.evaluationDigest().equals(DirectoryService.digest(codec.json(claims)))
                ||effect==null||!effect.binding().effectId().equals(claims.effectId())
                ||effect.generation()!=claims.authorizationGeneration()
                ||!effect.binding().operationId().equals(claims.invocationId())
                ||!effect.binding().attemptId().equals(claims.executionNonce())
                ||!effect.binding().requestDigest().equals(binding(claims)))throw ManagementAccess.denied();
            if(effect.consumer()!=null){
                if(nonce.consumedAt()==null)throw ManagementAccess.denied();
                return claimReply(effect,audience,submitted.claimToken());
            }
            var i=operations.invocationIn(tx,claims.invocationId());
            if(claims.iat()>now||claims.exp()<=now||claims.exp()<=claims.iat()||claims.exp()-claims.iat()>MAX_LIFETIME_MS
                ||nonce.consumedAt()!=null||i.state()!=EnterpriseInvocationState.EXECUTING
                ||!Objects.equals(i.reservedNonce(),claims.executionNonce())||!effect.state().equals("SENDING"))
                throw ManagementAccess.denied();
            operations.requireFresh(service.tenant(),tx,i);
            var consumed=tx.effects().consume(claims.nonce(),binding(claims),audience.name(),submitted.claimToken(),now);
            if(!consumed.newAuthority())throw ManagementAccess.denied();
            tx.enterprise().consumeNonce(claims.nonce(),now);
            tx.audit(service.id(),"SERVICE_CLAIM_CONSUME","invocation:"+i.id(),i.revision(),i.evaluation().context().requestId(),i.requestDigest());
            return claimReply(consumed.generation(),audience,submitted.claimToken());
        });
    }
    /** Authenticated status stays available after expiry/termination and never reopens dispatch. */
    public Store.Reply claimStatus(DirectoryService.Actor service,ServiceAudience audience,String document){
        var reference=codec.model(document,ServicePermitClaimReference.class);
        return store.transaction(service.tenant(),false,tx->claimReply(tx.effects().byNonce(reference.authNonce()),audience,reference.claimToken()));
    }
    /** Late evidence is bound to the recorded service/claim; it does not depend on an expired signing key. */
    public Store.Reply targetOutcome(DirectoryService.Actor service,ServiceAudience audience,String document){
        var request=codec.model(document,ServicePermitTargetOutcome.class);var reference=request.claim();
        return store.transaction(service.tenant(),true,tx->{
            long now=operations.clock(tx);var effect=tx.effects().byNonce(reference.authNonce());
            if(effect==null||!audience.name().equals(effect.consumer())||!reference.claimToken().equals(effect.claimToken()))
                throw ManagementAccess.denied();
            var settled=tx.effects().targetOutcome(reference.authNonce(),audience.name(),reference.claimToken(),
                request.outcome().name(),request.resultDigest(),now);
            tx.audit(service.id(),"SERVICE_TARGET_OUTCOME","effect:"+effect.binding().effectId(),settled.revision(),"target-"+DirectoryService.digest(reference.authNonce()).substring(0,32),DirectoryService.digest(codec.json(request)));
            new OperationFinalizer(codec).refresh(tx,effect.binding().operationId(),now);
            return claimReply(settled,audience,reference.claimToken());
        });
    }
    private Store.Reply claimReply(EffectStore.Generation effect,ServiceAudience audience,String claimToken){
        if(effect==null||!audience.name().equals(effect.consumer()))throw ManagementAccess.denied();
        var state=claimToken.equals(effect.claimToken())?ServicePermitClaimState.valueOf(effect.state()):ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM;
        return new Store.Reply(200,codec.json(new ServicePermitClaimResult(effect.binding().effectId(),effect.generation(),state)),effect.revision());
    }
    public Store.Reply epoch(DirectoryService.Actor service) {
        // Reading the epoch also persists the anti-rollback clock. Reserve a
        // writer transaction before borrowing a connection (including SQLite).
        return store.transaction(service.tenant(),true,tx->new Store.Reply(200,codec.json(new ServiceAuthorizationEpoch(
            service.tenant().value(),tx.enterprise().authorizationEpoch(),operations.clock(tx))),tx.load().revision()));
    }
    public Map<String,Object> keys(){return Map.of("keys",List.of(operations.signer().servicePublicKey()));}
    private String binding(ServicePermitClaims claims){
        var values=new TreeMap<String,Object>();values.put("audience",claims.aud());values.put("operation",claims.operation());
        values.put("resources",claims.resources());values.put("requestFingerprint",claims.requestFingerprint());
        if(claims.httpRequestDigest()!=null)values.put("httpRequestDigest",claims.httpRequestDigest());
        values.put("subject",claims.sub());values.put("mode",claims.mode());
        return "sha256:"+DirectoryService.digest(codec.json(values));
    }
    private static ServiceAudience audience(String operation) {
        return switch(operation){
            case "memory.read","memory.append","memory.delete","memory.share","memory.export" -> ServiceAudience.olo_memory;
            case "rag.query","rag.ingest","rag.update","rag.delete","rag.share" -> ServiceAudience.olo_rag;
            default -> throw ManagementAccess.denied();
        };
    }
}
