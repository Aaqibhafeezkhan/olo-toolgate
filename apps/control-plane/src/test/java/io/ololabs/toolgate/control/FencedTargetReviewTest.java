// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FencedTargetReviewTest {
    final ObjectMapper json=new ObjectMapper();
    RouteDeclaration route()throws Exception{return json.readValue("""
        {"id":"send","destination":"external","method":"POST","path":"/send","query":[],
         "headers":["toolgate-effect-permit"],"requestContentTypes":["application/json"],
         "maxRequestBytes":1024,"maxResponseBytes":1024,"streaming":"NONE","timeoutMs":1000}
        """,RouteDeclaration.class);}
    EffectIntentBlueprint intent()throws Exception{return json.readValue("""
        {"path":"/send","query":{},"headers":{},"contentType":"application/json","bodyMode":"CANONICAL_JSON","body":{"recipient":"alice"}}
        """,EffectIntentBlueprint.class);}
    @Test void bothExplicitTargetClassesRemainExactReviewedWritesWithMandatoryCriticalConsent()throws Exception{
        for(var type:FencedTargetClass.values()){
            var policy=new RouteEffectPolicy("send",EffectClassification.FINANCIAL,false,false,intent(),null,new FencedTarget("billing",type),null);
            assertDoesNotThrow(()->EffectIntentValidation.review(policy,route()));
            assertTrue(EffectIntentValidation.requiresConsent(policy.classification(),policy.consentRequired()));
        }
    }
    @Test void serverRefusesFencedReadsContradictoryRiskMalformedIdentityAndWorkerPermitHeaders()throws Exception{
        var route=route();var intent=intent();var target=new FencedTarget("billing",FencedTargetClass.SHARED_SQL);
        for(var policy:List.of(new RouteEffectPolicy("send",EffectClassification.READ,false,false,intent,null,target,null),
                new RouteEffectPolicy("send",EffectClassification.WRITE,false,true,intent,null,target,null),
                new RouteEffectPolicy("send",EffectClassification.WRITE,false,false,intent,null,new FencedTarget("../billing",FencedTargetClass.SHARED_SQL),null),
                new RouteEffectPolicy("send",EffectClassification.FINANCIAL,false,false,intent,null,null,null)))
            assertThrows(Failure.class,()->EffectIntentValidation.review(policy,route));
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(intent);
        node.withObject("/headers").put("toolgate-effect-permit","worker-selected");
        var forged=new RouteEffectPolicy("send",EffectClassification.WRITE,false,false,json.treeToValue(node,EffectIntentBlueprint.class),null,target,null);
        assertThrows(Failure.class,()->EffectIntentValidation.review(forged,route));
    }
}
