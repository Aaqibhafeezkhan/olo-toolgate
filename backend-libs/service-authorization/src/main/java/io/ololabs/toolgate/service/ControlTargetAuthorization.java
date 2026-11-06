// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.databind.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Generic fenced-target adapter. Retries evidence/status only, never a business effect. */
public final class ControlTargetAuthorization {
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient http;private final URI base;private final String issuer,tenant;
    private final FencedTarget target;private final Supplier<String> credential;private final Clock clock;
    private Map<String,PublicKey> keys=Map.of();private long refreshedAt=-1;
    public ControlTargetAuthorization(HttpClient http,URI origin,String issuer,String tenant,FencedTarget target,
            Supplier<String> credential,Clock clock){
        if(!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null
            ||origin.getQuery()!=null||origin.getFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))
            ||http.followRedirects()!=HttpClient.Redirect.NEVER||!target.targetId().matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Current HTTPS target configuration required");
        this.http=Objects.requireNonNull(http);this.issuer=Objects.requireNonNull(issuer);this.tenant=Objects.requireNonNull(tenant);
        this.target=Objects.requireNonNull(target);this.credential=Objects.requireNonNull(credential);this.clock=Objects.requireNonNull(clock);
        base=origin.resolve("/api/control/v1/access/target-permits/"+target.targetId()+"/");
    }
    public synchronized FencedTargetVerifier.VerifiedWrite verifyWrite(SignedEffectGrant grant,
            ServicePermitVerifier.Transport request,byte[] body,SSLSession peer){
        refresh();return new FencedTargetVerifier(issuer,tenant,target,keys,clock).verifyWrite(grant,request,body,peer);
    }
    /** Only successful online consumption produces this deadline handle. A local beginApply CAS is still mandatory. */
    public final class ConsumedWrite {
        private final FencedTargetVerifier.VerifiedWrite verified;
        private ConsumedWrite(FencedTargetVerifier.VerifiedWrite verified){this.verified=verified;}
        public void checkCommitDeadline(){verified.checkDeadline();}
    }
    public ConsumedWrite consumeClaim(FencedTargetVerifier.VerifiedWrite verified,JdbcEffectClaims.Owner owner){
        if(!target.equals(verified.target())||!tenant.equals(verified.binding().tenant())
            ||!owner.binding().equals(verified.binding()))throw denied();
        var result=reply(exchange("claims/consume",verified.consumption(owner.claimToken())),verified.binding());
        if(result.status()!=ServicePermitClaimState.CONSUMED)throw denied();
        verified.checkDeadline();return new ConsumedWrite(verified);
    }
    public ServicePermitClaimResult claimStatus(JdbcEffectClaims.Binding binding,String token){
        if(!tenant.equals(binding.tenant()))throw denied();
        return reply(exchange("claims/status",new ServicePermitClaimReference(binding.authNonce(),token)),binding);
    }
    public ServicePermitClaimResult reportOutcome(JdbcEffectClaims.PendingOutcome outcome){
        var b=outcome.binding();if(!tenant.equals(b.tenant()))throw denied();
        var result=reply(exchange("claims/outcome",new ServicePermitTargetOutcome(
            new ServicePermitClaimReference(b.authNonce(),outcome.claimToken()),outcome.outcome(),outcome.resultDigest())),b);
        if(result.status()!=ServicePermitClaimState.CONFLICT&&!result.status().name().equals(outcome.outcome().name()))throw denied();
        return result;
    }
    private ServicePermitClaimResult reply(JsonNode value,JdbcEffectClaims.Binding binding){
        if(!tenant.equals(binding.tenant()))throw denied();
        try{
            var result=JSON.treeToValue(value,ServicePermitClaimResult.class);
            if(!binding.effectId().equals(result.effectId())||binding.authorizationGeneration()!=result.authorizationGeneration())throw denied();
            return result;
        }catch(java.io.IOException|IllegalArgumentException invalid){throw denied();}
    }
    private void refresh(){
        long now=clock.millis();if(refreshedAt>=0&&now>=refreshedAt&&now-refreshedAt<5000)return;
        keys=Map.of();refreshedAt=-1;
        try{
            var material=exchange("jwks",null).path("keys");if(!material.isArray()||material.isEmpty()||material.size()>16)throw denied();
            var result=new HashMap<String,PublicKey>();
            for(var key:material){
                if(!"RSA".equals(key.path("kty").asText())||!"RS256".equals(key.path("alg").asText())
                    ||!"sig".equals(key.path("use").asText())||key.path("kid").asText().isBlank())throw denied();
                var rsa=(java.security.interfaces.RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.path("n").asText())),
                    new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.path("e").asText()))));
                if(rsa.getModulus().bitLength()<2048||!rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))
                    ||result.put(key.path("kid").asText(),rsa)!=null)throw denied();
            }
            keys=Map.copyOf(result);refreshedAt=now;
        }catch(GeneralSecurityException|IllegalArgumentException invalid){throw denied();}
    }
    private JsonNode exchange(String path,Object body){
        try{
            String token=credential.get();if(token==null||token.isBlank())throw denied();
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+token).header("Toolgate-Tenant-ID",tenant).header("Accept","application/json");
            if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            var response=http.send(request.build(),ignored->new BoundedBody());
            if(response.statusCode()!=200)throw denied();
            return JSON.readTree(response.body());
        }catch(java.io.IOException|IllegalArgumentException invalid){throw denied();}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw denied();}
    }
    /** Bound the stream before allocation; cancellation never grants a usable partial response. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>65536-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new java.io.IOException("Control response exceeds limit"));return;
                }
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private static SecurityException denied(){return new SecurityException("Control target authorization unavailable");}
}
