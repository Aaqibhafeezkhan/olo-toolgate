// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.security.*;
import java.security.interfaces.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OperatorApprovalsTest {
    static final String TENANT="10000000-0000-4000-8000-000000000001";
    static final long NOW=1700000000000L;
    static final List<KeyPair> KEYS=new ArrayList<>();
    static final ContractCodec CODEC=new ContractCodec();
    static final List<KillTrustKey> PUBLIC=new ArrayList<>();
    static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static byte[] unsigned(java.math.BigInteger value){var bytes=value.toByteArray();return bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes;}
    @BeforeAll static void keys()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        for(int i=0;i<3;i++){var key=generator.generateKeyPair();KEYS.add(key);var rsa=(RSAPublicKey)key.getPublic();
            PUBLIC.add(new KillTrustKey("operator-"+i,encode(unsigned(rsa.getModulus())),"AQAB"));}
    }
    ObjectNode claims(){return (ObjectNode)CODEC.value(CODEC.json(new BreakGlassApprovalClaims(TENANT,"incident-1",
        new KillScope(KillScopeKind.TENANT,null),KillMode.TERMINATE,"Incident exact e\u0301",NOW,NOW+600000)));}
    String sign(int key,ObjectNode payload,String type)throws Exception{
        var header=(ObjectNode)CODEC.value("{\"alg\":\"RS256\",\"typ\":\""+type+"\",\"kid\":\"operator-"+key+"\"}");
        return sign(key,header,Canonical.jcs(payload));
    }
    String sign(int key,ObjectNode header,byte[] payload)throws Exception{
        String message=encode(Canonical.jcs(header))+"."+encode(payload);
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(KEYS.get(key).getPrivate());
        signature.update(message.getBytes(StandardCharsets.US_ASCII));return message+"."+encode(signature.sign());
    }
    BreakGlassRequest request(List<String> signatures){var c=CODEC.model(CODEC.json(claims()),BreakGlassApprovalClaims.class);
        return new BreakGlassRequest(c.scope(),c.mode(),c.reason(),signatures);}
    RsaOperatorApprovals verifier(){return new RsaOperatorApprovals(CODEC,PUBLIC,List.of());}
    void refuse(List<String> signatures,long now){assertThrows(Failure.class,()->verifier().verify(new Ids.TenantId(TENANT),"incident-1",request(signatures),now));}
    @Test void onlyTwoOrThreeDistinctCurrentOperatorsCanApproveIdenticalBoundKills()throws Exception{
        String type="toolgate-breakglass-approval+jws";
        var first=sign(0,claims(),type);var second=sign(1,claims(),type);var third=sign(2,claims(),type);
        var approved=verifier().verify(new Ids.TenantId(TENANT),"incident-1",request(List.of(second,first)),NOW);
        assertEquals(List.of("operator-0","operator-1"),approved.operators());
        assertEquals(3,verifier().verify(new Ids.TenantId(TENANT),"incident-1",request(List.of(third,first,second)),NOW).operators().size());
        refuse(List.of(first),NOW);refuse(List.of(first,first),NOW);refuse(List.of(first,second,second),NOW);
        refuse(List.of(first,second),NOW-1);refuse(List.of(first,second),NOW+600000);
        assertThrows(Failure.class,()->verifier().verify(new Ids.TenantId(TENANT),"other-request",request(List.of(first,second)),NOW));
        assertThrows(Failure.class,()->verifier().verify(new Ids.TenantId("20000000-0000-4000-8000-000000000001"),"incident-1",request(List.of(first,second)),NOW));
        for(String field:List.of("tenantId","requestId","reason","mode","notAfterUnixMs","issuedAtUnixMs")){
            var changed=claims();
            switch(field){
                case "notAfterUnixMs"->changed.put(field,NOW+600001);
                case "issuedAtUnixMs"->changed.put(field,NOW+1);
                case "mode"->changed.put(field,"STOP_NEW");
                default->changed.put(field,"changed");
            }
            refuse(List.of(sign(0,changed,type),sign(1,changed,type)),NOW);
        }
        var changed=claims();changed.put("reason","different");
        refuse(List.of(first,sign(1,changed,type)),NOW);
        refuse(List.of(first,sign(1,claims(),"toolgate-kill-event+jws")),NOW);
        var header=(ObjectNode)CODEC.value("{\"alg\":\"RS256\",\"typ\":\"toolgate-breakglass-approval+jws\",\"kid\":\"operator-1\"}");
        for(String field:List.of("alg","kid","jku")){
            var mutated=header.deepCopy();mutated.put(field,field.equals("alg")?"none":"foreign");
            refuse(List.of(first,sign(1,mutated,Canonical.jcs(claims()))),NOW);
        }
        refuse(List.of(first,sign(1,header,(" "+new String(Canonical.jcs(claims()),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))),NOW);
        var parts=second.split("\\.",-1);
        byte[] signature=Base64.getUrlDecoder().decode(parts[2]);signature[0]^=1;
        refuse(List.of(first,parts[0]+"."+parts[1]+"."+encode(signature)),NOW);
        var unknown=claims();unknown.put("recovery",true);
        refuse(List.of(sign(0,unknown,type),sign(1,unknown,type)),NOW);
    }
    @Test void startupRefusesMissingDuplicateWeakOrReusedPublicKeys(){
        assertThrows(IllegalArgumentException.class,()->new RsaOperatorApprovals(CODEC,PUBLIC.subList(0,2),List.of()));
        assertThrows(IllegalArgumentException.class,()->new RsaOperatorApprovals(CODEC,List.of(PUBLIC.get(0),PUBLIC.get(0),PUBLIC.get(2)),List.of()));
        var original=(RSAPublicKey)KEYS.get(1).getPublic();
        assertThrows(IllegalArgumentException.class,()->new RsaOperatorApprovals(CODEC,PUBLIC,List.of(original.getModulus())));
        // Public-only negative material, never a weak generated private key.
        var weak=new KillTrustKey("weak",encode(unsigned(original.getModulus().shiftRight(1024))),"AQAB");
        assertThrows(IllegalArgumentException.class,()->new RsaOperatorApprovals(CODEC,List.of(PUBLIC.get(0),weak,PUBLIC.get(2)),List.of()));
        var alias=new KillTrustKey("different-id",PUBLIC.get(0).n(),"AQAB");
        assertThrows(IllegalArgumentException.class,()->new RsaOperatorApprovals(CODEC,List.of(PUBLIC.get(0),alias,PUBLIC.get(2)),List.of()));
    }
}
