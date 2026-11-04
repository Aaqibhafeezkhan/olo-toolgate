// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.util.*;

/** Complete-history target-deduplicated recovery. Missing/unsupported facts never become retry permission. */
public final class TargetResends {
    private TargetResends(){}
    public static List<Map<String,Object>> plan(Store.Session tx,Codec codec,EnterpriseInvocation invocation,long now){
        if(invocation.reconciliation()!=ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY
            ||!OperationRebindingPolicy.targetCandidate(tx.enterprise().recoveryFacts(invocation.id()),now))return null;
        var version=tx.packages().version("sha256:"+invocation.evaluation().packageDigest());if(version==null)return null;
        var document=(JsonNode)codec.value(version.document());
        if(PackageCompatibility.proxy(document.path("manifest")))return null;
        JsonNode descriptor=null;
        for(var tool:document.path("manifest").path("tools"))
            if(tool.path("descriptor").path("toolId").asText().equals(invocation.evaluation().toolId()))descriptor=tool.path("descriptor");
        if(descriptor==null||!"DOWNSTREAM_IDEMPOTENCY".equals(descriptor.path("reconciliation").asText()))return null;
        var effects=tx.effects().currentForOperation(invocation.id());if(effects.isEmpty())return null;
        String deployment=tx.packages().deployment(version.packageId());if(deployment==null)return null;
        var deployed=(JsonNode)codec.value(deployment);
        if(!deployed.path("packageDigest").asText().equals(version.digest()))return null;
        String profileDigest=deployed.path("serviceProfileDigest").asText();
        var profileDoc=tx.packages().document(profileDigest);if(profileDoc==null)return null;
        var profile=codec.model(profileDoc,ServiceProfile.class);
        if(profile.effectReviews()==null)return null;
        for(var attempt:tx.enterprise().attempts(invocation.id(),now))if(attempt.consumedAt()!=null){
            var scope=tx.enterprise().recoveryScope(attempt.id());
            if(scope==null||!scope.profileDigest().equals(profileDigest)||scope.profileRevision()!=profile.revision()
                ||!scope.descriptorDigest().equals("sha256:"+invocation.evaluation().toolDigest()))return null;
        }
        var plan=new ArrayList<Map<String,Object>>();
        for(var effect:effects){
            var binding=tx.effects().targetBinding(effect.binding().effectId());
            if(binding==null||tx.effects().hasTerminalHistory(effect.binding().effectId())||binding.validUntil()<=now||!binding.profileDigest().equals(profileDigest)
                ||!Set.of("AMBIGUOUS","RELEASED").contains(effect.state()))return null;
            var reviews=profile.effectReviews().stream().filter(r->r.policy().routeId().equals(effect.binding().routeId())).toList();
            if(reviews.size()!=1||reviews.getFirst().policy().targetDedupeGuarantee()==null)return null;
            var guarantee=reviews.getFirst().policy().targetDedupeGuarantee();
            if(!binding.guaranteeDigest().equals(Canonical.arguments((JsonNode)codec.value(codec.json(guarantee))))
                ||TargetEffectKeys.credentialVersion(tx,codec,invocation,profile,effect.binding().routeId(),guarantee)!=binding.credentialVersion())return null;
            String review=TargetDedupeGuarantees.approved(tx,codec,invocation.evaluation().toolId(),profileDigest,reviews.getFirst(),now);
            if(review==null)return null;
            plan.add(Map.of("effectId",binding.effectId(),"generation",effect.generation(),"requestDigest",effect.binding().requestDigest(),
                "profileDigest",binding.profileDigest(),"guaranteeDigest",binding.guaranteeDigest(),"reviewId",review));
        }
        return List.copyOf(plan);
    }
    private static JsonNode latest(Store.Session tx,Codec codec,String operation){
        return tx.enterprise().recoveries(operation,64).stream().filter(r->r.proof().equals("TARGET_DEDUPLICATION"))
            .max(Comparator.comparingLong(EnterpriseStore.Recovery::toRevision))
            .map(r->((JsonNode)codec.value(r.document())).path("targetPlan")).orElse(null);
    }
    static boolean bound(Store.Session tx,Codec codec,EnterpriseInvocation invocation,String effect,String requestDigest,String profile){
        var plan=latest(tx,codec,invocation.id());if(plan==null)return false;
        for(var item:plan)if(item.path("effectId").asText().equals(effect)){
            if(!item.path("requestDigest").asText().equals(requestDigest)||!item.path("profileDigest").asText().equals(profile))
                throw Failure.conflict();
            return true;
        }
        throw Failure.conflict();
    }
    static void qualified(Store.Session tx,EnterpriseInvocation invocation,String profile,String runtime){
        var scope=tx.enterprise().recoveryScope(invocation.reservedNonce());
        if(scope==null||!scope.deviceId().equals(invocation.evaluation().context().deviceId())
            ||!scope.profileDigest().equals(profile)||!scope.descriptorDigest().equals("sha256:"+invocation.evaluation().toolDigest())
            ||runtime!=null&&!scope.runtimeDigest().equals(runtime))throw ManagementAccess.denied();
    }
    static void currentReview(Store.Session tx,Codec codec,EnterpriseInvocation invocation,ServiceProfile profile,String profileDigest,String routeId,long now){
        var reviews=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
            .filter(r->r.policy().routeId().equals(routeId)).toList();
        if(reviews.size()!=1||TargetDedupeGuarantees.approved(tx,codec,invocation.evaluation().toolId(),
            profileDigest,reviews.getFirst(),now)==null)
            throw ManagementAccess.denied();
    }
}
