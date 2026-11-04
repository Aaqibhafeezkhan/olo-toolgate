// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TargetDedupeReviewTest {
    @TempDir java.nio.file.Path temp;
    EnterpriseConfigurationTest rig;
    String tool,profile="sha256:"+"a".repeat(64);
    RouteEffectReview route;
    ObjectNode proposed;
    @BeforeEach void setup()throws Exception{
        rig=new EnterpriseConfigurationTest();rig.temp=temp;rig.setup();
        var codec=rig.fixture.graph.codec;var json=new ObjectMapper();
        proposed=rig.fixture.store.transaction(rig.requester.tenant(),false,tx->{
            var entry=tx.load().entries().values().stream().filter(e->e.id().kind()==Ids.Kind.TOOL).findFirst().orElseThrow();
            tool=entry.id().value();return (ObjectNode)codec.value(entry.document());
        });
        var guarantee=new TargetDedupeGuarantee("https://target.example.test/contracts/idempotency",86_400_000L,
            TargetDedupeScope.GLOBAL,"sha256:"+"b".repeat(64),TargetDedupeDifferentBody.REJECT,
            System.currentTimeMillis()+86_400_000,"idempotency-key",null,null);
        proposed.withObject("/definition").putArray("targetDedupeGuarantees")
            .add(json.valueToTree(new RouteDedupeConfiguration(profile,"send",guarantee)));
        var intent=new EffectIntentBlueprint(TextNode.valueOf("/send"),Map.of(),Map.of(),"",EffectBodyMode.NONE,NullNode.instance);
        route=new RouteEffectReview(new RouteEffectPolicy("send",EffectClassification.WRITE,false,false,intent,guarantee),
            "admin",System.currentTimeMillis());
    }
    EnterpriseConfigurationChange propose(String key){
        var codec=rig.fixture.graph.codec;
        var command=new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.UPDATE,ControlEntityKind.TOOL,tool,
            proposed.toString(),proposed.path("revision").asLong());
        return codec.model(rig.changes.mutate(rig.requester,command,key,"request").body(),EnterpriseConfigurationChange.class);
    }
    String approved(){return rig.fixture.store.transaction(rig.requester.tenant(),false,tx->
        TargetDedupeGuarantees.approved(tx,rig.fixture.graph.codec,tool,profile,route,System.currentTimeMillis()));}
    @Test void zeroReviewsAndSuperAdminCannotBypassIndependentGuaranteeReview(){
        rig.reviews(0);rig.superAdministrator("admin");
        var change=propose("guarantee-proposal");
        assertEquals(1,change.requiredReviews());assertNull(approved());
        change=rig.transition(rig.requester,change,"SUBMIT");var pending=change;
        assertThrows(Failure.class,()->rig.transition(rig.requester,pending,"APPROVE"));
        change=rig.transition(rig.checker,change,"APPROVE");
        change=rig.transition(rig.requester,change,"APPLY");
        assertEquals(change.id(),approved());
        var codec=rig.fixture.graph.codec;
        rig.fixture.store.transaction(rig.requester.tenant(),true,tx->{
            var before=tx.load();var entries=new HashMap<>(before.entries());var entry=entries.get(Ids.Kind.TOOL.id(tool));
            var modified=(ObjectNode)codec.value(entry.document());modified.put("name","Changed after review");
            entries.put(entry.id(),codec.revision(codec.entry(Ids.Kind.TOOL,modified.toString()),entry.revision()+1));
            tx.save(before,new Directory(before.revision()+1,entries));return null;
        });
        assertNull(approved());
    }
    @Test void routeMakerIsNotAnIndependentCheckerEvenIfConfigurationWasApplied(){
        rig.reviews(1);var change=propose("maker-proposal");
        change=rig.transition(rig.requester,change,"SUBMIT");
        change=rig.transition(rig.checker,change,"APPROVE");rig.transition(rig.requester,change,"APPLY");
        assertNotNull(approved());
        route=new RouteEffectReview(route.policy(),"alice",route.reviewedAtUnixMs());
        assertNull(approved());
    }
    @Test void reservedTargetKeyHeadersAndMissingScopedCredentialVersionAreRefused(){
        var g=route.policy().targetDedupeGuarantee();
        for(String header:List.of("authorization","content-encoding","if-match","trailer","proxy-connection")){
            var forbidden=new TargetDedupeGuarantee(g.documentationUrl(),g.retentionMs(),g.scope(),g.scopeBindingDigest(),g.differentBody(),
                g.reviewExpiresAtUnixMs(),header,null,null);
            assertThrows(Failure.class,()->TargetDedupeGuarantees.validate(forbidden));
        }
        var scoped=new TargetDedupeGuarantee(g.documentationUrl(),g.retentionMs(),TargetDedupeScope.API_KEY,g.scopeBindingDigest(),g.differentBody(),
            g.reviewExpiresAtUnixMs(),g.header(),null,null);
        assertThrows(Failure.class,()->TargetDedupeGuarantees.validate(scoped));
    }
}
