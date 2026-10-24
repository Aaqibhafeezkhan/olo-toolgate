// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import org.junit.jupiter.api.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServicePermitVerifierTest {
    static KeyPair key;
    static final String TENANT="10000000-0000-4000-8000-000000000001",EXECUTOR="e".repeat(64);
    static final byte[] BODY="{\"conversationId\":\"c1\",\"message\":\"private\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final List<ResourceDescriptor> RESOURCES=List.of(new ResourceDescriptor(ResourceKind.CUSTOM,"olo://"+TENANT+"/conversation/c1"));
    AtomicLong time,epoch;
    AtomicInteger commits;
    ServicePermitVerifier verifier;
    @BeforeAll static void keys()throws Exception{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();}
    @BeforeEach void setup(){
        time=new AtomicLong(100000);epoch=new AtomicLong(7);commits=new AtomicInteger();
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
        verifier=new ServicePermitVerifier("control",ServiceAudience.olo_memory,Map.of("effect-key",key.getPublic()),clock,
            tenant->new ServicePermitVerifier.Epoch(epoch.get(),100000),(audience,request)->commits.incrementAndGet()==1);
    }
    ServicePermit permit(String operation)throws Exception{
        var claims=new ServicePermitClaims("control",ServiceAudience.olo_memory,TENANT,"alice",null,RequestMode.HUMAN,operation,RESOURCES,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY)),EXECUTOR,"inv-1","nonce-1",7L,100000L,115000L,"execution-1");
        return signed("olo-service-permit+jws","effect-key",claims);
    }
    ServicePermit signed(String type,String kid,ServicePermitClaims claims)throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var b64=Base64.getUrlEncoder().withoutPadding();
        String message=b64.encodeToString(json.writeValueAsBytes(Map.of("alg","RS256","typ",type,"kid",kid)))+"."+b64.encodeToString(json.writeValueAsBytes(claims));
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update(message.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new ServicePermit(message+"."+b64.encodeToString(signature.sign()));
    }
    ServicePermitVerifier.Request request(String op){return new ServicePermitVerifier.Request(TENANT,op,RESOURCES,BODY);}
    @Test void directUserReadNeedsNoAgentAndNeverConsumesAWriteNonce()throws Exception{
        var auth=verifier.authorizeKey(permit("memory.read"),request("memory.read"),EXECUTOR);
        assertEquals("alice",auth.claims().sub());assertNull(auth.claims().act());assertEquals(0,commits.get());
    }
    @Test void writesConsumeOnlineAndAReplayedNonceCannotCommitTwice()throws Exception{
        var p=permit("memory.append");verifier.authorizeKey(p,request("memory.append"),EXECUTOR).checkCommitDeadline();
        assertThrows(SecurityException.class,()->verifier.authorizeKey(p,request("memory.append"),EXECUTOR));assertEquals(2,commits.get());
    }
    @Test void revocationOrStaleFeedStopsReadRelease()throws Exception{
        var p=permit("memory.read");epoch.set(8);assertThrows(SecurityException.class,()->verifier.authorizeKey(p,request("memory.read"),EXECUTOR));
        epoch.set(7);time.set(110001);assertThrows(SecurityException.class,()->verifier.authorizeKey(p,request("memory.read"),EXECUTOR));
    }
    @Test void delayedCommitMustRollBackAfterPermitDeadline()throws Exception{
        var authorization=verifier.authorizeKey(permit("memory.append"),request("memory.append"),EXECUTOR);
        time.set(115000);assertThrows(SecurityException.class,authorization::checkCommitDeadline);
    }
    @Test void substitutedBodyResourceTenantAndExecutorAreRefused()throws Exception{
        var p=permit("memory.read");
        for(var request:List.of(new ServicePermitVerifier.Request(TENANT,"memory.read",RESOURCES,"{}".getBytes()),
            new ServicePermitVerifier.Request("other","memory.read",RESOURCES,BODY),
            new ServicePermitVerifier.Request(TENANT,"memory.delete",RESOURCES,BODY),
            new ServicePermitVerifier.Request(TENANT,"memory.read",List.of(new ResourceDescriptor(ResourceKind.CUSTOM,"olo://"+TENANT+"/conversation/c2")),BODY)))
            assertThrows(SecurityException.class,()->verifier.authorizeKey(p,request,EXECUTOR));
        assertThrows(SecurityException.class,()->verifier.authorizeKey(p,request("memory.read"),"f".repeat(64)));
    }
    @Test void unauthenticatedCallsAndWrongPurposeKeysAreRefused()throws Exception{
        var p=permit("memory.read");assertThrows(SecurityException.class,()->verifier.authorize(p,request("memory.read"),null));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var c=json.readValue(Base64.getUrlDecoder().decode(p.jws().split("\\.")[1]),ServicePermitClaims.class);
        for(var bad:List.of(signed("toolgate-effect-permit+jws","effect-key",c),signed("olo-service-permit+jws","unknown-key",c)))
            assertThrows(SecurityException.class,()->verifier.authorizeKey(bad,request("memory.read"),EXECUTOR));
    }
}
