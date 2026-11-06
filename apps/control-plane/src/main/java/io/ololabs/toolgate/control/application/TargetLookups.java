// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Current scoped two-phase reconciliation. No writer transaction spans network I/O. */
public final class TargetLookups {
    private final Store store;private final Codec codec;private final EnterpriseOperations operations;
    private final java.util.function.Supplier<VaultService> vault;private final TargetStatusClient client;
    public TargetLookups(Store store,Codec codec,EnterpriseOperations operations,java.util.function.Supplier<VaultService> vault,TargetStatusClient client){
        this.store=Objects.requireNonNull(store);this.codec=Objects.requireNonNull(codec);this.operations=Objects.requireNonNull(operations);
        this.vault=vault;this.client=Objects.requireNonNull(client);
    }
    private record Snapshot(EnterpriseInvocation invocation,EffectStore.TargetBinding binding,EffectStore.Intent intent,
        String review,long epoch,long credentialVersion,String bodyDigest,List<EffectStore.Generation> generations,TargetStatusClient.Request request){
        @Override public String toString(){return "TargetLookupSnapshot[private bindings redacted]";}
    }
    private Snapshot snapshot(Store.Session tx,DirectoryService.Actor actor,String operation,String effect,boolean decrypt){
        long now=operations.clock(tx);var i=tx.enterprise().invocation(operation);
        if(i==null)throw ManagementAccess.denied();operations.lookupScope(tx,actor,i,now);
        if(i.state()!=EnterpriseInvocationState.OUTCOME_UNKNOWN)throw Failure.conflict();
        var binding=tx.effects().targetBinding(effect);var intent=tx.effects().intent(effect);
        if(binding==null||intent==null||!binding.operationId().equals(operation)||!intent.profileDigest().equals(binding.profileDigest()))
            throw Failure.conflict();
        var version=tx.packages().version("sha256:"+i.evaluation().packageDigest());if(version==null)throw Failure.conflict();
        var deployment=tx.packages().deployment(version.packageId());if(deployment==null)throw Failure.conflict();
        var deployed=(JsonNode)codec.value(deployment);
        if(!deployed.path("packageDigest").asText().equals(version.digest())
            ||!deployed.path("serviceProfileDigest").asText().equals(binding.profileDigest()))throw Failure.conflict();
        String document=tx.packages().document(binding.profileDigest());if(document==null)throw Failure.conflict();
        if(!Canonical.digest((JsonNode)codec.value(document)).equals(binding.profileDigest()))throw Failure.conflict();
        var profile=codec.model(document,ServiceProfile.class);
        var reviews=profile.effectReviews()==null?List.<RouteEffectReview>of():profile.effectReviews().stream()
            .filter(r->r.policy().routeId().equals(binding.routeId())).toList();
        if(reviews.size()!=1)throw Failure.conflict();
        var guarantee=reviews.getFirst().policy().targetDedupeGuarantee();
        if(guarantee==null||guarantee.statusRouteId()==null
            ||!Canonical.arguments((JsonNode)codec.value(codec.json(guarantee))).equals(binding.guaranteeDigest()))
            throw Failure.conflict();
        TargetLookupRoutes.require(profile,binding.routeId(),guarantee.statusRouteId());
        String review=TargetDedupeGuarantees.approved(tx,codec,i.evaluation().toolId(),binding.profileDigest(),reviews.getFirst(),now);
        if(review==null)throw ManagementAccess.denied();
        if(TargetEffectKeys.credentialVersion(tx,codec,i,profile,binding.routeId(),guarantee)!=binding.credentialVersion())
            throw Failure.conflict();
        var generations=tx.effects().generations(effect);
        if(generations.isEmpty()||!generations.getLast().state().equals("AMBIGUOUS")
            ||generations.stream().anyMatch(g->!Set.of("AMBIGUOUS","RELEASED").contains(g.state())
                ||!g.binding().operationId().equals(operation)||!g.binding().requestDigest().equals(intent.requestDigest())))
            throw Failure.conflict();
        var manifest=codec.model(intent.document(),PreparedHttpRequestManifest.class);
        if(!manifest.profileDigest().equals(binding.profileDigest())||!manifest.routeId().equals(binding.routeId()))throw Failure.conflict();
        var write=profile.routes().stream().filter(r->r.id().equals(binding.routeId())).findFirst().orElseThrow(Failure::conflict);
        long credentialVersion=0;Map<String,String> credentials=Map.of();
        if(write.auth()!=null){
            var auths=new ArrayList<JsonNode>();var pkg=(JsonNode)codec.value(version.document());
            for(var tool:pkg.path("manifest").path("tools"))if(tool.path("descriptor").path("toolId").asText().equals(i.evaluation().toolId()))
                for(var auth:tool.path("descriptor").path("httpAuth"))if(auth.path("id").asText().equals(write.auth()))auths.add(auth);
            if(auths.size()!=1)throw Failure.conflict();var auth=auths.getFirst();
            if(!auth.path("type").asText().equals("API_KEY")||!auth.path("secret").isTextual()||!auth.path("header").isTextual())
                throw Failure.conflict(); // Other auth adapters are explicitly unsupported, never downgraded.
            var secret=tx.enterprise().secret(auth.path("secret").asText());
            if(secret==null)throw ManagementAccess.denied();credentialVersion=secret.version();
            var entry=tx.load().entries().get(Ids.Kind.BINDING.id(i.evaluation().context().bindingId()));
            if(entry==null||!entry.enabled())throw ManagementAccess.denied();
            var b=codec.model(entry.document(),ControlExecutionBinding.class);
            if(!secret.toolGroupId().equals(b.toolGroupId())||!secret.deviceGroupId().equals(b.deviceGroupId()))throw ManagementAccess.denied();
            if(decrypt){
                if(vault==null)throw Failure.unavailable();
                credentials=Map.of(auth.path("header").asText().toLowerCase(Locale.ROOT),
                    vault.get().targetLookupSecret(actor.tenant(),tx,i,secret.name(),secret.version()));
            }
        }
        return new Snapshot(i,binding,intent,review,tx.enterprise().authorizationEpoch(),credentialVersion,manifest.bodyDigest(),
            generations,new TargetStatusClient.Request(profile,guarantee.statusRouteId(),binding.targetKey(),credentials));
    }
    public Store.Reply lookup(DirectoryService.Actor actor,String operation,String effect){
        Ids.valid(operation);Ids.valid(effect);
        var before=store.transaction(actor.tenant(),true,tx->snapshot(tx,actor,operation,effect,true));
        // The target key and any credential remain inside the trusted Control adapter.
        var response=codec.model(codec.json(client.lookup(before.request())),TargetEffectStatusReply.class);
        boolean terminal=response.state()==TargetEffectStatus.COMPLETED||response.state()==TargetEffectStatus.FAILED;
        if(!response.key().equals(before.binding().targetKey())
            ||response.bodyDigest()!=null&&!response.bodyDigest().equals(before.bodyDigest())
            ||terminal&&(response.bodyDigest()==null||response.businessReceipt()==null||response.businessReceipt().isBlank()
                ||response.resultDigest()==null)
            ||!terminal&&(response.businessReceipt()!=null||response.resultDigest()!=null))throw Failure.conflict();
        String receipt=terminal?Canonical.arguments((JsonNode)codec.value(codec.json(List.of("toolgate-target-lookup-receipt-v1",
            before.binding().profileDigest(),before.binding().targetKey(),before.bodyDigest(),
            ((JsonNode)codec.value(before.binding().document())).path("scopeBindingDigest").asText(),response.businessReceipt())))):null;
        return store.transaction(actor.tenant(),true,tx->{
            var after=snapshot(tx,actor,operation,effect,false);
            if(!Objects.equals(before.invocation().revision(),after.invocation().revision())||before.epoch()!=after.epoch()
                ||before.credentialVersion()!=after.credentialVersion()||!before.binding().equals(after.binding())
                ||!before.intent().equals(after.intent())||!before.review().equals(after.review())
                ||!before.generations().equals(after.generations()))throw Failure.conflict();
            long now=operations.clock(tx);
            if(terminal)for(var generation:after.generations())if(generation.state().equals("AMBIGUOUS"))
                tx.effects().recordLookup(generation.binding().authNonce(),receipt,response.state().name(),response.resultDigest(),now);
            var outcome=new OperationFinalizer(codec).refresh(tx,operation,now);
            String digest=DirectoryService.digest(codec.json(Map.of("effectId",effect,"state",response.state(),
                "reviewId",after.review(),"receiptDigest",receipt==null?"":receipt)));
            tx.audit(actor.id(),"TARGET_STATUS_LOOKUP","effect:"+effect,after.invocation().revision(),operation,digest);
            return new Store.Reply(200,codec.json(new TargetEffectLookupResult(operation,effect,response.state(),outcome.revision(),receipt)),outcome.revision());
        });
    }
}
