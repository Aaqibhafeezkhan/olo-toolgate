// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Target-specific online consumption is the fenced ordering point; status never grants execution. */
public final class TargetClaims {
    private final Store store;private final Codec codec;private final EnterpriseOperations operations;
    public TargetClaims(Store store,Codec codec,EnterpriseOperations operations){this.store=store;this.codec=codec;this.operations=operations;}
    private static String consumer(String target){
        if(target==null||!target.matches("[a-z][a-z0-9-]{0,63}"))throw ManagementAccess.denied();
        return "TARGET:"+target;
    }
    private Store.Reply status(EffectStore.Generation effect,String target,String token){
        if(effect==null||!consumer(target).equals(effect.consumer()))throw ManagementAccess.denied();
        var state=token.equals(effect.claimToken())?ServicePermitClaimState.valueOf(effect.state()):ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM;
        return new Store.Reply(200,codec.json(new ServicePermitClaimResult(effect.binding().effectId(),effect.generation(),state)),effect.revision());
    }
    public Store.Reply consume(DirectoryService.Actor service,String target,String document){
        String identity=consumer(target);var request=codec.model(document,TargetClaimConsumption.class);
        var claims=operations.signer().verifyEffect(request.grant());
        return store.transaction(service.tenant(),true,tx->{
            var effect=tx.effects().byNonce(claims.authNonce());var intent=tx.effects().intent(claims.effectId());
            if(!operations.issuer().equals(claims.iss())||!service.tenant().value().equals(claims.tenantId())
                ||claims.fencedTarget()==null||!target.equals(claims.fencedTarget().targetId())
                ||claims.classification()==EffectClassification.READ
                ||!claims.executorKeySha256().equals(request.executorKeySha256())
                ||!claims.httpRequestDigest().equals(request.httpRequestDigest())
                ||effect==null||intent==null||!effect.binding().effectId().equals(claims.effectId())
                ||effect.generation()!=claims.generation()||!effect.binding().operationId().equals(claims.invocationId())
                ||!effect.binding().attemptId().equals(claims.fencingToken())||!effect.binding().deviceId().equals(claims.aud())
                ||!effect.binding().brokerId().equals(claims.brokerInstanceId())||!effect.binding().handlerId().equals(claims.handlerId())
                ||!effect.binding().requestDigest().equals(claims.httpRequestDigest())
                ||!effect.binding().routeId().equals(claims.routeId())||effect.binding().expiresAt()!=claims.exp()
                ||!intent.requestDigest().equals(claims.httpRequestDigest())||!intent.profileDigest().equals(claims.profileDigest())
                ||!intent.classification().equals(claims.classification().name()))throw ManagementAccess.denied();
            if(effect.consumer()!=null)return status(effect,target,request.claimToken());
            long now=operations.clock(tx);var i=operations.invocationIn(tx,claims.invocationId());
            var lease=tx.enterprise().executionLease(i.id());var admission=tx.enterprise().nonce(claims.fencingToken());
            if(claims.iat()>now||claims.nbf()>now||claims.exp()<=now||claims.exp()<=claims.iat()
                ||claims.exp()-claims.iat()>EffectGrants.MAX_LIFETIME_MS||!effect.state().equals("SENDING")
                ||i.state()!=EnterpriseInvocationState.EXECUTING||!Objects.equals(i.reservedNonce(),claims.fencingToken())
                ||!Objects.equals(i.authorizationEpoch(),claims.authorizationEpoch())
                ||lease==null||lease.expiresAt()<=now||!claims.aud().equals(lease.deviceId())
                ||!claims.fencingToken().equals(lease.fencingToken())||admission==null||admission.consumedAt()==null)
                throw ManagementAccess.denied();
            operations.requireFresh(service.tenant(),tx,i);
            var raw=tx.packages().document(claims.profileDigest());if(raw==null)throw ManagementAccess.denied();
            var profile=codec.model(raw,ServiceProfile.class);
            var current=tx.packages().deployment(profile.packageId());if(current==null)throw ManagementAccess.denied();
            var deployment=(JsonNode)codec.value(current);
            if(!claims.profileDigest().equals(deployment.path("serviceProfileDigest").asText())
                ||!("sha256:"+i.evaluation().packageDigest()).equals(deployment.path("packageDigest").asText()))
                throw ManagementAccess.denied();
            var reviews=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
                .filter(v->v.policy().routeId().equals(claims.routeId())).toList();
            if(reviews.size()!=1||!claims.fencedTarget().equals(reviews.getFirst().policy().fencedTarget()))throw ManagementAccess.denied();
            TargetFencing.currentReview(tx,codec,profile,claims.routeId(),claims.fencedTarget(),now);
            var consumed=tx.effects().consume(claims.authNonce(),claims.httpRequestDigest(),identity,request.claimToken(),now);
            if(!consumed.newAuthority())throw ManagementAccess.denied();
            tx.audit(service.id(),"TARGET_CLAIM_CONSUME","effect:"+claims.effectId(),consumed.generation().revision(),
                i.evaluation().context().requestId(),claims.httpRequestDigest().substring(7));
            return status(consumed.generation(),target,request.claimToken());
        });
    }
    public Store.Reply inspect(DirectoryService.Actor service,String target,String document){
        consumer(target);var ref=codec.model(document,ServicePermitClaimReference.class);
        return store.transaction(service.tenant(),false,tx->status(tx.effects().byNonce(ref.authNonce()),target,ref.claimToken()));
    }
    public Store.Reply outcome(DirectoryService.Actor service,String target,String document){
        String identity=consumer(target);var request=codec.model(document,ServicePermitTargetOutcome.class);var ref=request.claim();
        return store.transaction(service.tenant(),true,tx->{
            var effect=tx.effects().byNonce(ref.authNonce());
            if(effect==null)throw ManagementAccess.denied();
            if(effect.consumer()==null){
                var intent=tx.effects().intent(effect.binding().effectId());
                if(intent==null||request.outcome()!=ServicePermitTargetOutcomeKind.FAILED
                    ||!request.resultDigest().equals("sha256:b775353541fcdd3e2ef3d940fbae7961712380d2390c74852a52e12487d8e7f2"))throw ManagementAccess.denied();
                var raw=tx.packages().document(intent.profileDigest());if(raw==null)throw ManagementAccess.denied();
                var profile=codec.model(raw,ServiceProfile.class);
                var policies=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
                    .filter(v->v.policy().routeId().equals(effect.binding().routeId())).toList();
                if(policies.size()!=1||policies.getFirst().policy().fencedTarget()==null
                    ||!target.equals(policies.getFirst().policy().fencedTarget().targetId()))throw ManagementAccess.denied();
                var failed=tx.effects().targetNotStarted(ref.authNonce(),identity,ref.claimToken(),request.resultDigest(),operations.clock(tx));
                tx.audit(service.id(),"TARGET_CLAIM_NOT_STARTED","effect:"+effect.binding().effectId(),failed.revision(),
                    "target-"+DirectoryService.digest(ref.authNonce()).substring(0,32),DirectoryService.digest(codec.json(request)));
                new OperationFinalizer(codec).refresh(tx,effect.binding().operationId(),operations.clock(tx));
                return status(failed,target,ref.claimToken());
            }
            if(!identity.equals(effect.consumer())||!ref.claimToken().equals(effect.claimToken()))throw ManagementAccess.denied();
            long now=operations.clock(tx);
            var settled=tx.effects().targetOutcome(ref.authNonce(),identity,ref.claimToken(),request.outcome().name(),request.resultDigest(),now);
            tx.audit(service.id(),"TARGET_CLAIM_OUTCOME","effect:"+effect.binding().effectId(),settled.revision(),
                "target-"+DirectoryService.digest(ref.authNonce()).substring(0,32),DirectoryService.digest(codec.json(request)));
            new OperationFinalizer(codec).refresh(tx,effect.binding().operationId(),now);
            return status(settled,target,ref.claimToken());
        });
    }
    public Map<String,Object> keys(){return Map.of("keys",List.of(operations.signer().servicePublicKey()));}
}
