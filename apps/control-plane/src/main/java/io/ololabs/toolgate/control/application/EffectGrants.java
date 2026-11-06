// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;
import java.util.function.Function;

/** Current-authority generic effect issuance and one-owner SENDING. Neither endpoint retries a send. */
public final class EffectGrants {
    public static final long MAX_LIFETIME_MS=2000;
    private final Store store;private final Codec codec;private final EnterpriseOperations operations;
    public EffectGrants(Store store,Codec codec,EnterpriseOperations operations){this.store=store;this.codec=codec;this.operations=operations;}
    private JsonNode tree(Object value){return (JsonNode)codec.value(codec.json(value));}
    private void live(Ids.TenantId tenant,Store.Session tx,EnterpriseInvocation invocation,String device,String fence,long now){
        var admission=tx.enterprise().nonce(fence);var lease=tx.enterprise().executionLease(invocation.id());
        if(invocation.state()!=EnterpriseInvocationState.EXECUTING||!device.equals(invocation.evaluation().context().deviceId())
            ||!Objects.equals(invocation.reservedNonce(),fence)||admission==null||admission.consumedAt()==null
            ||!admission.invocationId().equals(invocation.id())||lease==null||!lease.deviceId().equals(device)
            ||!lease.fencingToken().equals(fence)||lease.expiresAt()<=now)throw ManagementAccess.denied();
        operations.requireFresh(tenant,tx,invocation);
    }
    private record Refused(EnterpriseInvocation invocation,String device){}
    public Store.Reply issue(Ids.TenantId tenant,Function<Store.Session,String> certificate,String executorKeySha256,String document){
        if(executorKeySha256==null||!executorKeySha256.matches("[a-f0-9]{64}"))throw ManagementAccess.denied();
        var request=codec.model(document,EffectGrantIssue.class);var refused=new Refused[1];
        try{return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);long now=operations.clock(tx);
            var invocation=operations.invocationIn(tx,request.invocationId());
            if(device.equals(invocation.evaluation().context().deviceId())&&Objects.equals(invocation.reservedNonce(),request.fencingToken()))
                refused[0]=new Refused(invocation,device);
            live(tenant,tx,invocation,device,request.fencingToken(),now);
            var manifest=request.httpRequestManifest();
            var claims=operations.signer().verifyInvocation(request.invocationPermit());
            var version=tx.packages().version(claims.target().packageDigest());
            if(version==null)throw ManagementAccess.denied();
            var packageDoc=(JsonNode)codec.value(version.document());
            boolean nativeMcp=PackageCompatibility.proxy(packageDoc.path("manifest"));
            var profile=nativeMcp?
                ServiceRequestValidation.admissionProfile(tx,codec,operations.signer(),operations.issuer(),invocation,device,request.invocationPermit(),manifest,now):
                ServiceRequestValidation.admission(tx,codec,operations.signer(),operations.issuer(),invocation,device,request.invocationPermit(),manifest,now);
            boolean consentBound=EffectConsents.needsConsent(profile,claims.target().toolId(),manifest.routeId(),nativeMcp);
            var arguments=tree(request.arguments());
            if(!Canonical.arguments(arguments).equals(claims.argumentsDigest())
                ||!Canonical.arguments(tree(manifest)).equals(request.httpRequestDigest()))throw consentBound?EffectConsents.mismatch():ManagementAccess.denied();
            currentProfile(tx,profile.packageId(),manifest.profileDigest(),claims.target().packageDigest());
            var descriptors=new ArrayList<JsonNode>();
            for(var tool:packageDoc.path("manifest").path("tools"))
                if(tool.path("descriptor").path("toolId").asText().equals(invocation.evaluation().toolId()))descriptors.add(tool.path("descriptor"));
            if(descriptors.size()!=1)throw ManagementAccess.denied();
            var descriptor=descriptors.getFirst();
            EffectClassification classification;boolean consentRequired;EffectConsentMappedRequest consentRequest;
            TargetDedupeGuarantee targetGuarantee=null;FencedTarget fencedTarget=null;
            if(nativeMcp){
                if(!claims.target().routes().isEmpty())throw ManagementAccess.denied();
                var doc=ServerPackages.proxyDocument(tx,version,codec);
                var artifacts=packageDoc.path("manifest").path("artifacts");
                boolean pinned=false;
                for(var artifact:artifacts)if(ServerPackages.MCP_PROXY_MEDIA_TYPE.equals(artifact.path("mediaType").asText())
                    &&claims.target().artifactDigest().equals(artifact.path("artifactDigest").asText()))pinned=true;
                if(!pinned)throw ManagementAccess.denied();
                var proxy=codec.model(codec.json(doc),McpProxyDocument.class);
                McpEffectPolicy policy;
                try{policy=McpEffectValidation.match(profile,proxy,claims.target().toolId(),manifest,arguments,request.handlerId());}
                catch(Failure invalid){if(consentBound&&invalid.status()==403)throw EffectConsents.mismatch();throw invalid;}
                classification=policy.classification();consentRequired=EffectIntentValidation.requiresConsent(classification,policy.consentRequired());
                consentRequest=McpEffectValidation.preview(profile,proxy,claims.target().toolId(),arguments);
            }else{
                RouteEffectPolicy policy;
                try{policy=EffectIntentValidation.match(profile,manifest,arguments);}
                catch(Failure invalid){if(consentBound&&invalid.status()==403)throw EffectConsents.mismatch();throw invalid;}
                classification=policy.classification();consentRequired=EffectIntentValidation.requiresConsent(classification,policy.consentRequired());
                targetGuarantee=policy.targetDedupeGuarantee();fencedTarget=policy.fencedTarget();
                if(fencedTarget!=null)TargetFencing.currentReview(tx,codec,profile,manifest.routeId(),fencedTarget,now);
                consentRequest=EffectIntentValidation.preview(profile,manifest.routeId(),arguments);
                credentialHeaders(descriptor,profile,manifest);
            }
            if(classification!=EffectClassification.READ&&descriptor.path("effect").path("safety").asText().equals("READ_ONLY"))
                throw ManagementAccess.denied();
            long expiry=Math.min(now+MAX_LIFETIME_MS,Math.min(invocation.expiresAtUnixMs(),tx.enterprise().executionLease(invocation.id()).expiresAt()));
            var credentialDeadline=tx.enterprise().userCredentialDeadline(invocation.id());
            if(invocation.evaluation().context().mode()!=RequestMode.SERVICE&&credentialDeadline==null)throw ManagementAccess.denied();
            if(credentialDeadline!=null)expiry=Math.min(expiry,credentialDeadline);
            if(expiry<=now)throw ManagementAccess.denied();
            // S19 reserves an exact consent use in this transaction. S20 retains qualified safe resend.
            String effectId=EffectIdentity.of(codec,tenant.value(),invocation.id(),manifest.routeId(),
                request.effectKey()==null&&classification==EffectClassification.READ
                    ?"read:"+DirectoryService.digest(request.handlerId()):EffectIdentity.key(request.effectKey()));
            expiry=Math.min(expiry,TargetEffectKeys.deadline(tx,effectId,targetGuarantee,now,invocation.expiresAtUnixMs()));
            if(expiry<=now)throw ManagementAccess.denied();
            boolean targetResend=TargetResends.bound(tx,codec,invocation,effectId,request.httpRequestDigest(),manifest.profileDigest());
            if(targetResend){
                TargetResends.qualified(tx,invocation,manifest.profileDigest(),claims.target().runtimeDigest());
                TargetResends.currentReview(tx,codec,invocation,profile,manifest.profileDigest(),manifest.routeId(),now);
                if(tx.effects().hasTerminalHistory(effectId))throw Failure.conflict();
            }
            var previous=tx.effects().current(effectId);
            var intent=tx.effects().intent(effectId);
            if(previous!=null&&(intent==null||!intent.requestDigest().equals(request.httpRequestDigest())
                ||!intent.argumentsDigest().equals(claims.argumentsDigest())||!intent.profileDigest().equals(manifest.profileDigest())
                ||!intent.classification().equals(classification.name())||!intent.document().equals(codec.json(manifest))))
                throw Failure.conflict();
            String nonce;EffectStore.Generation generation;boolean replay=false;
            if(previous!=null&&previous.state().equals("AUTHORIZED")){
                var binding=previous.binding();
                if(!binding.operationId().equals(invocation.id())||!binding.attemptId().equals(request.fencingToken())
                    ||!binding.deviceId().equals(device)||!binding.brokerId().equals(request.brokerInstanceId())
                    ||!binding.handlerId().equals(request.handlerId())||binding.expiresAt()<=now)throw Failure.conflict();
                // A lost-response replay observes the same generation and never extends its original send window.
                expiry=Math.min(expiry,binding.expiresAt());nonce=binding.authNonce();generation=previous;replay=true;
            }else{
                if(previous!=null&&!previous.state().equals("RELEASED")&&!(targetResend&&previous.state().equals("AMBIGUOUS")))throw Failure.conflict();
                nonce="auth-"+UUID.randomUUID();
                var binding=new EffectStore.Binding(effectId,invocation.id(),request.fencingToken(),manifest.routeId(),
                    request.httpRequestDigest(),device,request.brokerInstanceId(),request.handlerId(),nonce,expiry,expiry+60000);
                var authorized=targetResend&&previous!=null&&previous.state().equals("AMBIGUOUS")
                    ?tx.effects().authorizeTargetResend(binding,now):tx.effects().authorize(binding,now);
                if(!authorized.newAuthority())throw Failure.conflict();
                generation=authorized.generation();
            }
            if(consentRequired)EffectConsents.reserve(tx,codec,invocation,consentRequest,effectId,request.httpRequestDigest(),claims.argumentsDigest(),manifest.profileDigest(),now);
            if(intent==null)tx.effects().recordIntent(new EffectStore.Intent(effectId,request.httpRequestDigest(),claims.argumentsDigest(),manifest.profileDigest(),
                classification.name(),codec.json(manifest),now));
            var downstream=TargetEffectKeys.bind(tx,codec,operations.signer(),tenant.value(),invocation,profile,targetGuarantee,manifest,effectId,now);
            var grant=operations.signer().signEffect(new EffectGrantClaims(operations.issuer(),device,now,now,expiry,tenant.value(),
                invocation.id(),request.fencingToken(),invocation.authorizationEpoch(),effectId,generation.generation(),nonce,
                request.brokerInstanceId(),request.handlerId(),request.httpRequestDigest(),manifest.profileDigest(),manifest.routeId(),classification,downstream,fencedTarget,executorKeySha256));
            tx.audit(DirectoryService.digest(device),replay?"EFFECT_GRANT_REPLAY":"EFFECT_GRANT_ISSUE","effect:"+effectId,generation.revision(),
                invocation.evaluation().context().requestId(),request.httpRequestDigest().substring(7));
            return new Store.Reply(200,codec.json(new EffectGrant(grant,expiry-now,downstream,fencedTarget)),invocation.revision());
        });}catch(Failure failure){
            if(refused[0]!=null&&(failure.status()==403||failure.status()==409)){
                // The authority/slot transaction has rolled back. Retain only authenticated refusal facts in a new commit.
                var context=refused[0];var i=context.invocation();
                String operation=failure.detail()==null?"EFFECT_GRANT_REFUSED":failure.detail().name();
                store.transaction(tenant,true,tx->{tx.audit(DirectoryService.digest(context.device()),operation,"invocation:"+i.id(),
                    i.revision(),i.evaluation().context().requestId(),i.requestDigest());return null;});
            }
            throw failure;
        }
    }
    private void currentProfile(Store.Session tx,String packageId,String profileDigest,String packageDigest){
        var raw=tx.packages().deployment(packageId);
        if(raw==null)throw ManagementAccess.denied();var deployment=(JsonNode)codec.value(raw);
        if(!deployment.path("serviceProfileDigest").asText().equals(profileDigest)
            ||!deployment.path("packageDigest").asText().equals(packageDigest))throw ManagementAccess.denied();
    }
    private static void credentialHeaders(JsonNode descriptor,ServiceProfile profile,PreparedHttpRequestManifest manifest){
        var route=profile.routes().stream().filter(r->r.id().equals(manifest.routeId())).findFirst().orElseThrow(ManagementAccess::denied);
        if(route.auth()==null)return;
        var auths=new ArrayList<JsonNode>();
        for(var auth:descriptor.path("httpAuth"))if(auth.path("id").asText().equals(route.auth()))auths.add(auth);
        if(auths.size()!=1)throw ManagementAccess.denied();
        String name=auths.getFirst().path("header").asText("authorization").toLowerCase(Locale.ROOT);
        if(!manifest.credentialHeaders().equals(List.of(name))||manifest.headers().containsKey(name))throw ManagementAccess.denied();
    }
    public Store.Reply sending(Ids.TenantId tenant,Function<Store.Session,String> certificate,String document){
        var request=codec.model(document,EffectGrantDispatch.class);
        var claims=operations.signer().verifyEffect(request.grant());
        return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);long now=operations.clock(tx);
            if(!claims.iss().equals(operations.issuer())||!claims.aud().equals(device)||!claims.tenantId().equals(tenant.value())
                ||claims.iat()>now||claims.nbf()>now||claims.exp()<=now||claims.exp()<=claims.iat()
                ||claims.exp()-claims.iat()>MAX_LIFETIME_MS||!claims.brokerInstanceId().equals(request.brokerInstanceId())
                ||!claims.handlerId().equals(request.handlerId()))throw ManagementAccess.denied();
            var invocation=operations.invocationIn(tx,claims.invocationId());
            live(tenant,tx,invocation,device,claims.fencingToken(),now);
            if(!claims.authorizationEpoch().equals(invocation.authorizationEpoch()))throw ManagementAccess.denied();
            var effect=tx.effects().byNonce(claims.authNonce());var intent=tx.effects().intent(claims.effectId());
            if(effect==null||intent==null||!effect.binding().effectId().equals(claims.effectId())||effect.generation()!=claims.generation()
                ||!effect.binding().operationId().equals(invocation.id())||!effect.binding().attemptId().equals(claims.fencingToken())
                ||!effect.binding().requestDigest().equals(claims.httpRequestDigest())||!intent.requestDigest().equals(claims.httpRequestDigest())
                ||!intent.profileDigest().equals(claims.profileDigest())||!intent.classification().equals(claims.classification().name())
                ||effect.binding().expiresAt()!=claims.exp()||!effect.binding().routeId().equals(claims.routeId()))throw ManagementAccess.denied();
            var raw=tx.packages().document(claims.profileDigest());
            if(raw==null)throw ManagementAccess.denied();var profile=codec.model(raw,ServiceProfile.class);
            currentProfile(tx,profile.packageId(),claims.profileDigest(),"sha256:"+invocation.evaluation().packageDigest());
            if(TargetResends.bound(tx,codec,invocation,claims.effectId(),claims.httpRequestDigest(),claims.profileDigest())){
                TargetResends.qualified(tx,invocation,claims.profileDigest(),null);
                TargetResends.currentReview(tx,codec,invocation,profile,claims.profileDigest(),claims.routeId(),now);
                if(tx.effects().hasTerminalHistory(claims.effectId()))throw Failure.conflict();
            }
            TargetEffectKeys.sending(tx,claims,now);
            tx.effects().sending(claims.authNonce(),device,request.brokerInstanceId(),request.handlerId(),now);
            tx.audit(DirectoryService.digest(device),"EFFECT_GRANT_SENDING","effect:"+claims.effectId(),effect.revision()+1,
                invocation.evaluation().context().requestId(),claims.httpRequestDigest().substring(7));
            return new Store.Reply(200,codec.json(new EffectGrantDispatchAllowed(true)),invocation.revision());
        });
    }
}
