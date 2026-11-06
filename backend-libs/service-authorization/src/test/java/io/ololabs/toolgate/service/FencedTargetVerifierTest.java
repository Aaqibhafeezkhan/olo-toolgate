// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.*;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FencedTargetVerifierTest {
    static KeyPair key;static final ObjectMapper JSON=new ObjectMapper();
    static final String PEER="a".repeat(64),PROFILE="sha256:"+"b".repeat(64);
    static final byte[] BODY="{\"recipient\":\"alice\",\"amount\":10}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final AtomicLong now=new AtomicLong(10000);
    final FencedTarget target=new FencedTarget("billing",FencedTargetClass.SHARED_SQL);
    final HttpRequestFingerprint.Profile profile=new HttpRequestFingerprint.Profile("profile",PROFILE,"write",null,List.of());
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
    @BeforeAll static void keys()throws Exception{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=g.generateKeyPair();}
    FencedTargetVerifier verifier(FencedTarget t){return new FencedTargetVerifier("control","tenant",t,Map.of("effect-key",key.getPublic()),clock);}
    ServicePermitVerifier.Transport facts(SignedEffectGrant permit){
        var h=new HashMap<String,List<String>>();h.put("content-type",List.of("application/json"));h.put("accept",List.of("*/*"));
        if(permit!=null)h.put("toolgate-effect-permit",List.of(permit.jws()));
        return new ServicePermitVerifier.Transport(profile,URI.create("https://billing.test/send"),"POST",h);
    }
    ObjectNode claims(){
        var f=facts(null);String digest=HttpRequestFingerprint.digest(profile,f.uri(),f.method(),f.headers(),BODY);
        return JSON.valueToTree(new EffectGrantClaims("control","device",10000L,10000L,12000L,"tenant","inv-1","attempt-1",7L,
            "effect-1",1L,"auth-1","broker","handler",digest,PROFILE,"write",EffectClassification.WRITE,null,target,PEER));
    }
    SignedEffectGrant sign(ObjectNode claims,String type)throws Exception{
        var b64=Base64.getUrlEncoder().withoutPadding();
        String data=b64.encodeToString(JSON.writeValueAsBytes(Map.of("alg","RS256","typ",type,"kid","effect-key")))+"."
            +b64.encodeToString(JSON.writeValueAsBytes(claims));
        var sig=Signature.getInstance("SHA256withRSA");sig.initSign(key.getPrivate());sig.update(data.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new SignedEffectGrant(data+"."+b64.encodeToString(sig.sign()));
    }
    SignedEffectGrant sign(ObjectNode c)throws Exception{return sign(c,"toolgate-effect-grant-v1");}
    @Test void verifiedFactsBindAcceptedBytesPeerTargetAndOriginalDeadline()throws Exception{
        var p=sign(claims());var v=verifier(target).verifyKey(p,facts(p),BODY,PEER);
        assertEquals("effect-1",v.binding().effectId());assertEquals(12000,v.expiresAt());
        assertEquals(target,v.target());assertEquals(v.binding().requestDigest(),v.consumption("c".repeat(64)).httpRequestDigest());
        now.set(12000);assertThrows(SecurityException.class,v::checkDeadline);
        assertThrows(SecurityException.class,()->v.consumption("c".repeat(64)));
    }
    @Test void changedBodyRecipientPeerTargetClassOrListenerProfileCannotVerify()throws Exception{
        var p=sign(claims());var f=facts(p);
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,f,"different".getBytes(),PEER));
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,f,BODY,"d".repeat(64)));
        assertThrows(SecurityException.class,()->verifier(new FencedTarget("other",FencedTargetClass.SHARED_SQL)).verifyKey(p,f,BODY,PEER));
        assertThrows(SecurityException.class,()->verifier(new FencedTarget("billing",FencedTargetClass.SQL_WITH_EXTERNAL_ACTION)).verifyKey(p,f,BODY,PEER));
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,new ServicePermitVerifier.Transport(profile,
            URI.create("https://other.test/send"),"POST",f.headers()),BODY,PEER));
        assertThrows(SecurityException.class,()->verifier(target).verifyWrite(p,f,BODY,null));
    }
    @Test void absentRepeatedOrSubstitutedPermitHeadersAreRefused()throws Exception{
        var p=sign(claims());var f=facts(p);
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,facts(null),BODY,PEER));
        for(var values:List.of(List.of("substituted"),List.of(p.jws(),p.jws()))){
            var h=new HashMap<>(f.headers());h.put("toolgate-effect-permit",values);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,new ServicePermitVerifier.Transport(profile,f.uri(),f.method(),h),BODY,PEER));
        }
    }
    @Test void obsoleteMalformedReadOrUnfencedGrantsCannotBeConverted()throws Exception{
        for(String field:List.of("executorKeySha256","fencedTarget","tenantId","generation")){
            var c=claims();c.remove(field);var p=sign(c);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,facts(p),BODY,PEER));
        }
        for(String field:List.of("iss","tenantId","executorKeySha256","profileDigest","routeId","classification")){
            var c=claims();c.put(field,field.equals("classification")?"READ":"wrong");var p=sign(c);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,facts(p),BODY,PEER));
        }
        var c=claims();c.put("exp",12001);var p=sign(c);
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(p,facts(p),BODY,PEER));
        var wrong=sign(claims(),"olo-service-permit+jws");
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(wrong,facts(wrong),BODY,PEER));
    }    @Test void untrustedSignatureKeyTypeAndCurrentClaimBoundariesCannotProduceVerifiedFacts()throws Exception{
        var p=sign(claims());
        var pieces=p.jws().split("\\.");
        var corrupted=new SignedEffectGrant(pieces[0]+"."+pieces[1]+".AAAA");
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(corrupted,facts(corrupted),BODY,PEER));
        for(var field:List.of("aud","effectId","authNonce")){
            var c=claims();c.put(field,"");var bad=sign(c);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(bad,facts(bad),BODY,PEER));
        }
        for(var value:List.of(-1,0)){
            var c=claims();c.put("generation",value);var bad=sign(c);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(bad,facts(bad),BODY,PEER));
        }
        var epoch=claims();epoch.put("authorizationEpoch",-1);var wrongEpoch=sign(epoch);
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(wrongEpoch,facts(wrongEpoch),BODY,PEER));
        for(var field:List.of("iat","nbf")){
            var c=claims();c.put(field,10001);var bad=sign(c);
            assertThrows(SecurityException.class,()->verifier(target).verifyKey(bad,facts(bad),BODY,PEER));
        }
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(new SignedEffectGrant("x".repeat(32769)),facts(null),BODY,PEER));
        assertThrows(SecurityException.class,()->verifier(target).verifyKey(new SignedEffectGrant("not-a-jws"),facts(null),BODY,PEER));
        assertThrows(IllegalArgumentException.class,()->new FencedTargetVerifier("control","tenant",target,Map.of(),clock));
        assertThrows(IllegalArgumentException.class,()->new FencedTargetVerifier("control","tenant",
            new FencedTarget("../billing",FencedTargetClass.SHARED_SQL),Map.of("effect-key",key.getPublic()),clock));
        // Negative public material only: never generate or use a weak private signing key.
        var strong=(java.security.interfaces.RSAPublicKey)key.getPublic();
        var weak=KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(
            strong.getModulus().shiftRight(1024).setBit(0),strong.getPublicExponent()));
        assertThrows(IllegalArgumentException.class,()->new FencedTargetVerifier("control","tenant",target,
            Map.of("effect-key",weak),clock));
    }

}
