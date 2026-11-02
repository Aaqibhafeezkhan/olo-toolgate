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
    public Store.Reply issue(Ids.TenantId tenant,Function<Store.Session,String> certificate,String document){
        var request=codec.model(document,EffectGrantIssue.class);
        return store.transaction(tenant,true,tx->{
            String device=certificate.apply(tx);long now=operations.clock(tx);
            var invocation=operations.invocationIn(tx,request.invocationId());
            live(tenant,tx,invocation,device,request.fencingToken(),now);
            var manifest=request.httpRequestManifest();
            var profile=ServiceRequestValidation.admission(tx,codec,operations.signer(),operations.issuer(),invocation,
                device,request.invocationPermit(),manifest,now);
            var claims=operations.signer().verifyInvocation(request.invocationPermit());
            var arguments=tree(request.arguments());
            if(!Canonical.arguments(arguments).equals(claims.argumentsDigest())
                ||!Canonical.arguments(tree(manifest)).equals(request.httpRequestDigest()))throw ManagementAccess.denied();
            var policy=EffectIntentValidation.validate(profile,manifest,arguments);
            // Reconfiguration cannot leave old profile authority live.
            currentProfile(tx,profile.packageId(),manifest.profileDigest(),claims.target().packageDigest());
            var version=tx.packages().version(claims.target().packageDigest());
            if(version==null)throw ManagementAccess.denied();
            var packageDoc=(JsonNode)codec.value(version.document());
            var descriptors=new ArrayList<JsonNode>();
            for(var tool:packageDoc.path("manifest").path("tools"))
                if(tool.path("descriptor").path("toolId").asText().equals(invocation.evaluation().toolId()))descriptors.add(tool.path("descriptor"));
            if(descriptors.size()!=1)throw ManagementAccess.denied();
            var descriptor=descriptors.getFirst();
            if(policy.classification()!=EffectClassification.READ&&descriptor.path("effect").path("safety").asText().equals("READ_ONLY"))
                throw ManagementAccess.denied();
            credentialHeaders(descriptor,profile,manifest);
            long expiry=Math.min(now+MAX_LIFETIME_MS,Math.min(invocation.expiresAtUnixMs(),tx.enterprise().executionLease(invocation.id()).expiresAt()));
            var credentialDeadline=tx.enterprise().userCredentialDeadline(invocation.id());
            if(invocation.evaluation().context().mode()!=RequestMode.SERVICE&&credentialDeadline==null)throw ManagementAccess.denied();
            if(credentialDeadline!=null)expiry=Math.min(expiry,credentialDeadline);
            if(expiry<=now)throw ManagementAccess.denied();
            // S19 adds intentional effect slots. S20 adds qualified safe resend; neither is inferred here.
            String effectId="eff-"+DirectoryService.digest(codec.json(List.of("toolgate-generic-effect-v1",tenant.value(),invocation.id(),manifest.routeId())));
            if(tx.effects().current(effectId)!=null)throw Failure.conflict();
            String nonce="auth-"+UUID.randomUUID();
            var authorized=tx.effects().authorize(new EffectStore.Binding(effectId,invocation.id(),request.fencingToken(),manifest.routeId(),
                request.httpRequestDigest(),device,request.brokerInstanceId(),request.handlerId(),nonce,expiry,expiry+60000),now);
            if(!authorized.newAuthority())throw Failure.conflict();
            tx.effects().recordIntent(new EffectStore.Intent(effectId,request.httpRequestDigest(),claims.argumentsDigest(),manifest.profileDigest(),
                policy.classification().name(),codec.json(manifest),now));
            var grant=operations.signer().signEffect(new EffectGrantClaims(operations.issuer(),device,now,now,expiry,tenant.value(),
                invocation.id(),request.fencingToken(),invocation.authorizationEpoch(),effectId,authorized.generation().generation(),nonce,
                request.brokerInstanceId(),request.handlerId(),request.httpRequestDigest(),manifest.profileDigest(),manifest.routeId(),policy.classification()));
            tx.audit(DirectoryService.digest(device),"EFFECT_GRANT_ISSUE","effect:"+effectId,authorized.generation().revision(),
                invocation.evaluation().context().requestId(),request.httpRequestDigest().substring(7));
            return new Store.Reply(200,codec.json(new EffectGrant(grant,expiry-now)),invocation.revision());
        });
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
            tx.effects().sending(claims.authNonce(),device,request.brokerInstanceId(),request.handlerId(),now);
            tx.audit(DirectoryService.digest(device),"EFFECT_GRANT_SENDING","effect:"+claims.effectId(),effect.revision()+1,
                invocation.evaluation().context().requestId(),claims.httpRequestDigest().substring(7));
            return new Store.Reply(200,codec.json(new EffectGrantDispatchAllowed(true)),invocation.revision());
        });
    }
}
