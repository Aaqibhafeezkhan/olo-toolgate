// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.ServiceProfile;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectConsentPreviewTest {
    final ContractCodec codec=new ContractCodec();
    EffectConsentPreviews.Scope scope(EffectIntentValidationTest.Fixture f,long deadline,Long credential){
        return new EffectConsentPreviews.Scope("tenant","operation","run","example.payments/pay","pay",
            Canonical.arguments(f.args()),List.of(),deadline,credential);
    }
    EffectConsentPreviews.Snapshot create(EffectIntentValidationTest.Fixture f,EffectConsentPreviews.Scope scope,Map<String,Long> counts,long now)throws Exception{
        return EffectConsentPreviews.generic(codec,scope,EffectIntentValidationTest.JSON.treeToValue(f.profile(),ServiceProfile.class),f.args(),counts,now);
    }
    @Test void sealedPreviewRetainsExactValuesAndBothOriginalDeadlines()throws Exception{
        var f=new EffectIntentValidationTest().fixture();
        ((ObjectNode)f.args()).put("note","e"+(char)0x301);
        var snapshot=create(f,scope(f,5000,2000L),Map.of("send",1L),1000);
        var preview=snapshot.preview(codec);
        assertEquals(2000,preview.expiresAtUnixMs());assertEquals(1000,preview.notBeforeUnixMs());
        assertEquals("operation",preview.operationId());assertEquals("run",preview.runId());
        assertEquals(Canonical.arguments(f.args()),preview.argumentsDigest());
        assertEquals(Canonical.digest(f.profile()),preview.profileDigest());
        assertEquals(1,preview.slots().getFirst().permittedCount());
        assertEquals(Canonical.arguments((com.fasterxml.jackson.databind.JsonNode)codec.value(snapshot.document())),snapshot.digest());
        ((ObjectNode)f.args()).put("note","mallory");
        ((ObjectNode)preview.slots().getFirst().request().body()).put("note","changed");
        assertEquals("e"+(char)0x301,snapshot.preview(codec).slots().getFirst().request().body().path("note").asText());
        assertNotEquals(Canonical.digest((com.fasterxml.jackson.databind.JsonNode)codec.value(snapshot.document())),snapshot.digest());
        assertEquals(1500,create(f,scope(f,1500,2000L),Map.of("send",1L),1000).expiresAtUnixMs());
    }
    @Test void rejectsChangedArgumentPreimagesExpiredWindowsAndUnboundedAllowances()throws Exception{
        var f=new EffectIntentValidationTest().fixture();var original=scope(f,5000,null);
        ((ObjectNode)f.args()).put("unmappedArgument","still bound");
        assertThrows(Failure.class,()->create(f,original,Map.of("send",1L),1000));
        var bound=scope(f,5000,2000L);
        assertThrows(Failure.class,()->create(f,bound,Map.of("send",1L),2000));
        assertThrows(Failure.class,()->create(f,scope(f,1000,null),Map.of("send",1L),1000));
        for(long count:List.of(0L,65L))assertThrows(Failure.class,()->create(f,bound,Map.of("send",count),1000));
        assertThrows(Failure.class,()->create(f,bound,Map.of(),1000));
        assertEquals(3,create(f,bound,Map.of("send",3L),1000).preview(codec).slots().getFirst().permittedCount());
    }
    @Test void rejectsOversizedAndDeepArgumentsBeforePreviewSerialization()throws Exception{
        var f=new EffectIntentValidationTest().fixture();((ObjectNode)f.args()).put("extra","x".repeat(2*1024*1024));
        assertThrows(Failure.class,()->create(f,scope(f,5000,null),Map.of("send",1L),1000));
        var envelope=new EffectIntentValidationTest().fixture();((ObjectNode)envelope.args()).put("note","x".repeat(1024*1024));
        assertThrows(Failure.class,()->create(envelope,scope(envelope,5000,null),Map.of("send",1L),1000));
        var deep=new EffectIntentValidationTest().fixture();ObjectNode node=(ObjectNode)deep.args();
        for(int i=0;i<34;i++)node=node.putObject("nested");
        assertThrows(Failure.class,()->create(deep,scope(deep,5000,null),Map.of("send",1L),1000));
    }
    @Test void refusesOutboundNumbersThatBrowserReviewWouldRound()throws Exception{
        for(String number:List.of("9007199254740992","9007199254740993","-9007199254740993")){
            var f=new EffectIntentValidationTest().fixture();
            ((ObjectNode)f.args()).put("note",new java.math.BigInteger(number));
            assertThrows(Failure.class,()->create(f,scope(f,5000,null),Map.of("send",1L),1000));
        }
        var safe=new EffectIntentValidationTest().fixture();
        ((ObjectNode)safe.args()).put("note",9007199254740991L);
        assertEquals(9007199254740991L,create(safe,scope(safe,5000,null),Map.of("send",1L),1000)
            .preview(codec).slots().getFirst().request().body().path("note").longValue());
    }

    @Test void aggregateRoutePreviewsCannotMultiplyTheReviewPayloadBeyondItsLimit()throws Exception{
        var f=new EffectIntentValidationTest().fixture();
        ((ObjectNode)f.args()).put("note","x".repeat(24000));
        var route=f.profile().path("routes").get(0).deepCopy();
        var review=f.profile().path("effectReviews").get(0).deepCopy();
        var routes=f.profile().putArray("routes");var reviews=f.profile().putArray("effectReviews");
        var counts=new TreeMap<String,Long>();
        for(int i=0;i<64;i++){
            String id="send-"+i;
            routes.add(((ObjectNode)route.deepCopy()).put("id",id));
            var next=(ObjectNode)review.deepCopy();((ObjectNode)next.path("policy")).put("routeId",id);reviews.add(next);
            counts.put(id,1L);
        }
        assertEquals(400,assertThrows(Failure.class,()->create(f,scope(f,5000,null),counts,1000)).status());
    }

}
