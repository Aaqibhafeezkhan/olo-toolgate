// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.util.*;

/** Stable supervisor-only target keys. First-send key preparation is not approval to resend. */
public final class TargetEffectKeys {
    private TargetEffectKeys(){}
    static long deadline(Store.Session tx,String effect,TargetDedupeGuarantee guarantee,long now,long originalDeadline){
        if(guarantee==null)return originalDeadline;
        var old=tx.effects().targetBinding(effect);
        return old==null?TargetDedupePolicy.deadline(now,guarantee.retentionMs(),guarantee.reviewExpiresAtUnixMs(),originalDeadline)
            .orElseThrow(ManagementAccess::denied):Math.min(old.validUntil(),originalDeadline);
    }
    static long credentialVersion(Store.Session tx,Codec codec,EnterpriseInvocation invocation,ServiceProfile profile,String routeId,
                                  TargetDedupeGuarantee guarantee){
        if(guarantee.scope()==TargetDedupeScope.GLOBAL)return 0;
        var route=profile.routes().stream().filter(r->r.id().equals(routeId)).findFirst().orElseThrow(ManagementAccess::denied);
        var version=tx.packages().version("sha256:"+invocation.evaluation().packageDigest());
        if(route.auth()==null||version==null)throw ManagementAccess.denied();
        var packageDoc=(JsonNode)codec.value(version.document());var names=new ArrayList<String>();
        for(var tool:packageDoc.path("manifest").path("tools"))if(tool.path("descriptor").path("toolId").asText().equals(invocation.evaluation().toolId()))
            for(var auth:tool.path("descriptor").path("httpAuth"))if(auth.path("id").asText().equals(route.auth())&&auth.path("secret").isTextual()
                &&(guarantee.scope()!=TargetDedupeScope.API_KEY||auth.path("type").asText().equals("API_KEY")))
                names.add(auth.path("secret").asText());
        if(names.size()!=1)throw ManagementAccess.denied();
        var secret=tx.enterprise().secret(names.getFirst());
        if(secret==null||guarantee.credentialVersion()==null||secret.version()!=guarantee.credentialVersion())throw ManagementAccess.denied();
        var bindingEntry=tx.load().entries().get(io.ololabs.toolgate.control.domain.Ids.Kind.BINDING.id(invocation.evaluation().context().bindingId()));
        if(bindingEntry==null)throw ManagementAccess.denied();
        var binding=codec.model(bindingEntry.document(),ControlExecutionBinding.class);
        if(!secret.toolGroupId().equals(binding.toolGroupId())||!secret.deviceGroupId().equals(binding.deviceGroupId()))throw ManagementAccess.denied();
        return secret.version();
    }
    static DownstreamEffectKey bind(Store.Session tx,Codec codec,EffectSigner signer,String tenant,EnterpriseInvocation invocation,
        ServiceProfile profile,TargetDedupeGuarantee guarantee,PreparedHttpRequestManifest manifest,String effect,long now){
        var old=tx.effects().targetBinding(effect);
        if(guarantee==null){if(old!=null)throw Failure.conflict();return null;}
        TargetDedupeGuarantees.validate(guarantee);
        if(manifest.headers().containsKey(guarantee.header())||manifest.credentialHeaders().contains(guarantee.header()))throw ManagementAccess.denied();
        long credential=credentialVersion(tx,codec,invocation,profile,manifest.routeId(),guarantee);
        String document=new String(Canonical.jcs((JsonNode)codec.value(codec.json(guarantee))),java.nio.charset.StandardCharsets.UTF_8);
        String digest=Canonical.arguments((JsonNode)codec.value(document));
        if(old==null){
            long until=deadline(tx,effect,guarantee,now,invocation.expiresAtUnixMs());
            old=new EffectStore.TargetBinding(effect,invocation.id(),manifest.routeId(),manifest.profileDigest(),digest,
                guarantee.header(),signer.downstreamKey(tenant,effect),credential,now,until,document);
            tx.effects().recordTargetBinding(old);
        }
        if(!old.operationId().equals(invocation.id())||!old.routeId().equals(manifest.routeId())
            ||!old.profileDigest().equals(manifest.profileDigest())||!old.guaranteeDigest().equals(digest)
            ||old.credentialVersion()!=credential||old.validUntil()<=now)throw Failure.conflict();
        return new DownstreamEffectKey(old.header(),old.targetKey());
    }
    static void sending(Store.Session tx,EffectGrantClaims claims,long now){
        var binding=tx.effects().targetBinding(claims.effectId());
        if(binding==null){if(claims.downstreamKey()!=null)throw ManagementAccess.denied();return;}
        if(claims.downstreamKey()==null||!claims.downstreamKey().equals(new DownstreamEffectKey(binding.header(),binding.targetKey()))
            ||!claims.profileDigest().equals(binding.profileDigest())||claims.exp()>binding.validUntil()||binding.validUntil()<=now)
            throw ManagementAccess.denied();
    }
}
