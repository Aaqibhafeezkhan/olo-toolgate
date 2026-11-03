// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.util.*;

/** Exact consent coordination. Policy ASK obligations remain separate and must also be satisfied. */
public final class EffectConsents {
    private EffectConsents(){}
    private static JsonNode tree(Codec codec,Object value){return (JsonNode)codec.value(codec.json(value));}
    public record Plan(ServiceProfile profile,McpProxyDocument nativeDocument,Map<String,Long> counts){
        public Plan {counts=Map.copyOf(counts);}
        public void save(Store.Session tx,Codec codec,EnterpriseInvocation i,JsonNode arguments,Long credentialDeadline,long now){
            var e=i.evaluation();
            if(!DirectoryService.digest(codec.json(arguments)).equals(e.argumentsDigest()))throw ManagementAccess.denied();
            // Caller-declared agentRunId is telemetry only. Use the authenticated logical operation as execution scope.
            var scope=new EffectConsentPreviews.Scope(e.context().tenantId(),i.id(),i.id(),e.toolId(),e.action(),
                Canonical.arguments(arguments),e.resources(),i.expiresAtUnixMs(),credentialDeadline);
            var snapshot=nativeDocument==null?EffectConsentPreviews.generic(codec,scope,profile,arguments,counts,now):
                EffectConsentPreviews.nativeMcp(codec,scope,profile,nativeDocument,arguments,1,now);
            tx.effectConsents().save(snapshot);
        }
    }
    /** Only installed v2 package routes can offer mapped consent. Legacy/unsupported intents retain their existing refusal. */
    public static Plan plan(Store.Session tx,Codec codec,EnterpriseEvaluation e){
        int slash=e.toolId().indexOf('/');if(slash<0)return null;
        var raw=tx.packages().deployment(e.toolId().substring(0,slash));if(raw==null)return null;
        var deployment=(JsonNode)codec.value(raw);
        if(!deployment.path("packageDigest").asText().equals("sha256:"+e.packageDigest()))throw ManagementAccess.denied();
        String profileDigest=deployment.path("serviceProfileDigest").asText();
        var document=tx.packages().document(profileDigest);if(document==null)throw ManagementAccess.denied();
        var profile=codec.model(document,ServiceProfile.class);
        if(!Canonical.digest(tree(codec,profile)).equals(profileDigest))throw ManagementAccess.denied();
        var version=tx.packages().version(deployment.path("packageDigest").asText());if(version==null)throw ManagementAccess.denied();
        var manifest=((JsonNode)codec.value(version.document())).path("manifest");
        var descriptors=new ArrayList<JsonNode>();
        for(var t:manifest.path("tools"))if(t.path("descriptor").path("toolId").asText().equals(e.toolId()))descriptors.add(t.path("descriptor"));
        if(descriptors.size()!=1)throw ManagementAccess.denied();
        var counts=new TreeMap<String,Long>();
        if(PackageCompatibility.proxy(manifest)){
            var proxy=codec.model(codec.json(ServerPackages.proxyDocument(tx,version,codec)),McpProxyDocument.class);
            var tools=proxy.tools().stream().filter(t->t.toolId().equals(e.toolId())).toList();
            if(tools.size()!=1||tools.getFirst().effectPolicy()==null)return null;
            var policy=tools.getFirst().effectPolicy();
            return EffectIntentValidation.requiresConsent(policy.classification(),policy.consentRequired())?
                new Plan(profile,proxy,Map.of(McpEffectValidation.route(e.toolId()),1L)):null;
        }
        var routes=new HashSet<String>();
        for(var route:descriptors.getFirst().path("routes"))routes.add(route.path("id").asText());
        if(profile.effectReviews()!=null)for(var review:profile.effectReviews()){
            var p=review.policy();
            if(routes.contains(p.routeId())&&EffectIntentValidation.requiresConsent(p.classification(),p.consentRequired()))counts.put(p.routeId(),1L);
        }
        return counts.isEmpty()?null:new Plan(profile,null,counts);
    }
    static void reviewer(Store.Session tx,Codec codec,String user,EnterpriseInvocation i,long now){
        if(user==null)throw ManagementAccess.denied();
        var directory=tx.load();var access=new ManagementAccess(codec);
        if(access.superAdministrator(directory,user))return;
        var context=i.evaluation().context();
        if(user.equals(context.userId()))throw ManagementAccess.denied();
        var agents=new ArrayList<String>();
        if(context.agentId()!=null)agents.add(context.agentId());
        context.chain().forEach(hop->agents.add(hop.agentId()));
        for(String agent:agents){
            var entry=directory.entries().get(Kind.AGENT.id(agent));
            if(entry==null||user.equals(codec.model(entry.document(),ControlAgent.class).ownerUserId()))throw ManagementAccess.denied();
        }
        var entry=tx.load().entries().get(Kind.BINDING.id(i.evaluation().context().bindingId()));
        if(entry==null)throw ManagementAccess.denied();
        var b=codec.model(entry.document(),ControlExecutionBinding.class);
        access.require(tx.load(),user,"operation.approve",Kind.TOOL_GROUP,b.toolGroupId(),now);
        access.require(tx.load(),user,"operation.approve",Kind.DEVICE_GROUP,b.deviceGroupId(),now);
    }
    static EffectConsentBinding require(Store.Session tx,Codec codec,EnterpriseInvocation i,long now){
        var pending=tx.effectConsents().forOperation(i.id());
        if(pending==null){
            if(plan(tx,codec,i.evaluation())!=null)throw required();
            return null;
        }
        var approval=tx.effectConsents().approval(pending.consentId());
        if(approval==null||approval.authorizationEpoch()!=tx.enterprise().authorizationEpoch()||now<pending.notBefore()
            ||now>=pending.expiresAt()||tx.effectConsents().revoked(pending.consentId()))throw required();
        reviewer(tx,codec,approval.reviewer(),i,now);
        var preview=codec.model(pending.document(),EffectConsentPreview.class);
        int slash=i.evaluation().toolId().indexOf('/');
        var raw=slash<0?null:tx.packages().deployment(i.evaluation().toolId().substring(0,slash));
        if(raw==null||!((JsonNode)codec.value(raw)).path("serviceProfileDigest").asText().equals(preview.profileDigest())
            ||!preview.operationId().equals(i.id())||!preview.runId().equals(i.id())
            ||!preview.toolId().equals(i.evaluation().toolId())||!preview.action().equals(i.evaluation().action())
            ||!preview.resources().equals(i.evaluation().resources()))throw required();
        return new EffectConsentBinding(pending.consentId(),pending.digest());
    }
    static boolean approved(Store.Session tx,Codec codec,EnterpriseInvocation i,long now){
        try{require(tx,codec,i,now);return true;}catch(Failure refused){return false;}
    }
    static void reserve(Store.Session tx,Codec codec,EnterpriseInvocation i,EffectConsentMappedRequest request,
            String effectId,String requestDigest,String argumentsDigest,String profileDigest,long now){
        var binding=require(tx,codec,i,now);if(binding==null)throw required();
        var pending=tx.effectConsents().byId(binding.consentId());
        var preview=codec.model(pending.document(),EffectConsentPreview.class);
        if(!preview.argumentsDigest().equals(argumentsDigest)||!preview.profileDigest().equals(profileDigest)
            ||!preview.toolId().equals(i.evaluation().toolId())||!preview.action().equals(i.evaluation().action())
            ||!preview.resources().equals(i.evaluation().resources()))throw Failure.detail(403,ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,List.of());
        String digest=Canonical.arguments(tree(codec,request));
        var slots=preview.slots().stream().filter(s->s.request().routeId().equals(request.routeId())
            &&Canonical.arguments(tree(codec,s.request())).equals(digest)).toList();
        if(slots.size()!=1)throw Failure.detail(403,ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,List.of());
        tx.effectConsents().reserve(binding.consentId(),slots.getFirst().slotIndex(),i.id(),request.routeId(),
            digest,effectId,requestDigest,i.authorizationEpoch(),now);
    }
    static boolean needsConsent(ServiceProfile profile,String tool,String route,boolean nativeMcp){
        if(nativeMcp){
            if(profile.nativeMcpEffects()==null)return false;
            var reviews=profile.nativeMcpEffects().stream().filter(r->r.toolId().equals(tool)).toList();
            return reviews.size()==1&&!reviews.getFirst().reviewedBy().isBlank()
                &&EffectIntentValidation.requiresConsent(reviews.getFirst().policy().classification(),reviews.getFirst().policy().consentRequired());
        }
        if(profile.effectReviews()==null)return false;
        var reviews=profile.effectReviews().stream().filter(r->r.policy().routeId().equals(route)).toList();
        return reviews.size()==1&&!reviews.getFirst().reviewedBy().isBlank()
            &&EffectIntentValidation.requiresConsent(reviews.getFirst().policy().classification(),reviews.getFirst().policy().consentRequired());
    }
    static Failure mismatch(){return Failure.detail(403,ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,List.of());}
    static Failure required(){return Failure.detail(403,ControlErrorDetailCode.EFFECT_CONSENT_REQUIRED,List.of());}
    static EffectConsentView view(Store.Session tx,Codec codec,EffectConsentStore.Pending pending){
        var approval=tx.effectConsents().approval(pending.consentId());boolean revoked=tx.effectConsents().revoked(pending.consentId());
        return new EffectConsentView(codec.model(pending.document(),EffectConsentPreview.class),pending.digest(),
            revoked?EffectConsentState.REVOKED:approval==null?EffectConsentState.PENDING:EffectConsentState.APPROVED,
            1L+(approval==null?0:1)+(revoked?1:0),approval==null?null:approval.reviewer(),approval==null?null:approval.approvedAt());
    }
}
