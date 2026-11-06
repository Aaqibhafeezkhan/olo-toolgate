// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.PreparedHttpRequestManifest;
import io.ololabs.toolgate.contracts.v2.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Independent request reconstruction: a self-consistent attacker-selected digest grants no authority. */
class EffectIntentValidationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode node(String text)throws Exception{return JSON.readTree(text);}
    record Fixture(ObjectNode profile,ObjectNode manifest,JsonNode args){}
    Fixture fixture()throws Exception{
        var path=Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().resolve("v2/valid.json");
        var profile=(ObjectNode)JSON.readTree(Files.readString(path)).path("ServiceProfile").deepCopy();
        var route=(ObjectNode)profile.path("routes").get(0);
        route.put("id","send").put("destination","external").put("method","POST").put("path","/send")
            .put("streaming","NONE").put("maxRequestBytes",2097152);route.remove("auth");
        route.putArray("headers").add("x-recipient");route.putArray("query").add("account");
        route.putArray("requestContentTypes").add("application/json");
        var destination=(ObjectNode)profile.path("destinations").get(0);
        destination.put("id","external").put("scheme","https").put("mode","BROKERED").put("host","api.example.test").put("port",443);
        var policy=(ObjectNode)node("""
            {"routeId":"send","classification":"WRITE","consentRequired":false,"acknowledgedUnfencedRisk":true,
             "intent":{"path":"/send","query":{"account":{"$arg":"/account"}},"headers":{"x-recipient":{"$arg":"/recipient"}},
              "contentType":"application/json","bodyMode":"CANONICAL_JSON",
              "body":{"recipient":{"$arg":"/recipient"},"amount":{"$arg":"/amount"},"note":{"$arg":"/note"}}}}
            """);
        var review=profile.putArray("effectReviews").addObject().put("reviewedBy","admin").put("reviewedAtUnixMs",1000);
        review.set("policy",policy);
        var args=node("""
            {"account":"a b/&","recipient":"alice","amount":1234,"note":"original"}
            """);
        var manifest=(ObjectNode)node("""
            {"version":"toolgate-http-request-v1","profileId":"unused","profileDigest":"unused","routeId":"send",
            "scheme":"https","host":"api.example.test","port":443,"method":"POST","path":"/send",
            "query":"account=a%20b%2F%26","headers":{"accept":["*/*"],"x-recipient":["alice"]},
            "contentType":"application/json","bodyDigest":"unused","authDeclarationId":null,"credentialHeaders":[]}
            """);
        manifest.put("profileId",profile.path("profileId").asText()).put("profileDigest",Canonical.digest(profile))
            .put("bodyDigest",Canonical.arguments(node("""
                {"recipient":"alice","amount":1234,"note":"original"}
                """)));
        return new Fixture(profile,manifest,args);
    }
    RouteEffectPolicy validate(Fixture f)throws Exception{
        return EffectIntentValidation.validate(JSON.treeToValue(f.profile,ServiceProfile.class),
            JSON.treeToValue(f.manifest,PreparedHttpRequestManifest.class),f.args);
    }
    ObjectNode policy(Fixture f){return (ObjectNode)f.profile.path("effectReviews").get(0).path("policy");}
    void repin(Fixture f){f.manifest.put("profileDigest",Canonical.digest(f.profile));}
    void refused(Consumer<ObjectNode> mutate)throws Exception{
        var f=fixture();mutate.accept(f.manifest);assertThrows(Failure.class,()->validate(f));
    }
    @Test void reconstructsApprovedRecipientPayloadAndCanonicalQuery()throws Exception{
        assertEquals(EffectClassification.WRITE,validate(fixture()).classification());
    }
    @Test void criticalClassesCannotOptOutOfConsentWithAnUnfencedRiskAcknowledgement()throws Exception{
        for(String classification:List.of("FINANCIAL","DESTRUCTIVE")){
            var f=fixture();policy(f).put("classification",classification).put("consentRequired",false)
                .put("acknowledgedUnfencedRisk",true);repin(f);
            assertThrows(Failure.class,()->validate(f),classification);
        }
    }
    @Test void independentlyRejectsEveryAttackerChosenRequestField()throws Exception{
        refused(m->m.put("host","other.example.test"));refused(m->m.put("port",8443));
        refused(m->m.put("scheme","http"));refused(m->m.put("method","GET"));refused(m->m.put("path","/other"));
        refused(m->m.put("query","account=other"));refused(m->m.put("query","account=a+b%2F%26"));
        refused(m->((ObjectNode)m.path("headers")).putArray("x-recipient").add("mallory"));
        refused(m->((ObjectNode)m.path("headers")).putArray("x-extra").add("new"));
        refused(m->m.put("contentType","text/plain"));refused(m->m.put("bodyDigest","sha256:"+"a".repeat(64)));
        refused(m->m.put("profileDigest","sha256:"+"b".repeat(64)));
        refused(m->m.putArray("credentialHeaders").add("authorization"));
        refused(m->m.put("authDeclarationId","new-auth"));
    }
    @Test void consentMissingReviewDuplicateReviewAndReservedTargetFailClosed()throws Exception{
        var consent=fixture();policy(consent).put("consentRequired",true);repin(consent);
        assertThrows(Failure.class,()->validate(consent));
        var missing=fixture();missing.profile.remove("effectReviews");repin(missing);
        assertThrows(Failure.class,()->validate(missing));
        var duplicate=fixture();((ArrayNode)duplicate.profile.path("effectReviews")).add(duplicate.profile.path("effectReviews").get(0).deepCopy());repin(duplicate);
        assertThrows(Failure.class,()->validate(duplicate));
        var reserved=fixture();((ObjectNode)reserved.profile.path("routes").get(0)).put("destination","olo-memory");repin(reserved);
        assertThrows(Failure.class,()->validate(reserved));
        var risk=fixture();policy(risk).put("classification","FINANCIAL").put("acknowledgedUnfencedRisk",false);repin(risk);
        assertThrows(Failure.class,()->validate(risk));
    }
    @Test void argumentValuesRemainDataAndDoNotNormalizeOrReinterpretReferences()throws Exception{
        var f=fixture();
        ((ObjectNode)f.args).set("note",node("""
            {"$arg":"/recipient","raw":"e\u0301"}
            """));
        var expected=JSON.createObjectNode().put("recipient","alice").put("amount",1234);
        expected.set("note",f.args.path("note").deepCopy());
        f.manifest.put("bodyDigest",Canonical.arguments(expected));
        assertNotNull(validate(f));
        ((ObjectNode)expected.path("note")).put("raw","\u00e9");
        f.manifest.put("bodyDigest",Canonical.arguments(expected));
        assertThrows(Failure.class,()->validate(f));
    }
    @Test void missingArgumentsAndInvalidOrNonNfcReferencesAreRefused()throws Exception{
        for(String pointer:new String[]{"/absent","/~2bad","/e\u0301"}){
            var f=fixture();((ObjectNode)f.args).put("e\u0301","alice");
            ((ObjectNode)policy(f).path("intent").path("body")).set("recipient",JSON.createObjectNode().put("$arg",pointer));repin(f);
            assertThrows(Failure.class,()->validate(f));
        }
        var f=fixture();((ObjectNode)policy(f).path("intent").path("body")).put("note","e\u0301");repin(f);
        assertThrows(Failure.class,()->validate(f));
    }
    @Test void oversizedAndDeepArgumentPreimagesCannotExhaustReconstruction()throws Exception{
        var big=fixture();((ObjectNode)big.args).put("note","x".repeat(2097153));
        assertThrows(Failure.class,()->validate(big));
        var deep=fixture();ObjectNode current=JSON.createObjectNode();((ObjectNode)deep.args).set("note",current);
        for(int i=0;i<40;i++)current=current.putObject("nested");
        assertThrows(Failure.class,()->validate(deep));
    }
    @Test void readWithoutABodyStillBindsEmptyBytesAndExplicitReviewedClassification()throws Exception{
        var f=fixture();var intent=(ObjectNode)policy(f).path("intent");
        policy(f).put("classification","READ").put("acknowledgedUnfencedRisk",false);
        intent.put("bodyMode","NONE").put("contentType","").putNull("body");
        ((ObjectNode)f.profile.path("routes").get(0)).put("method","GET").putArray("requestContentTypes");
        f.manifest.put("method","GET").put("contentType","").put("bodyDigest",Canonical.sha256(new byte[0]));
        repin(f);assertEquals(EffectClassification.READ,validate(f).classification());
        f.manifest.put("bodyDigest",Canonical.arguments(JSON.createObjectNode()));
        assertThrows(Failure.class,()->validate(f));
    }

    @Test void highRiskPreviewShowsResolvedOutboundValuesWithoutAuthorizingTheEffect()throws Exception{
        var f=fixture();policy(f).put("classification","FINANCIAL").put("consentRequired",true);repin(f);
        var profile=JSON.treeToValue(f.profile,ServiceProfile.class);
        var preview=EffectIntentValidation.preview(profile,"send",f.args);
        assertEquals(EffectClassification.FINANCIAL,preview.classification());
        assertEquals("account=a%20b%2F%26",preview.query());
        assertEquals("alice",preview.headers().get("x-recipient").get(0).asText());
        assertEquals("alice",preview.body().path("recipient").asText());
        assertEquals(1234,preview.body().path("amount").asInt());
        assertThrows(Failure.class,()->validate(f));
        ((ObjectNode)f.args).put("recipient","mallory");
        assertEquals("alice",preview.body().path("recipient").asText());
        assertNotEquals(preview,EffectIntentValidation.preview(profile,"send",f.args));
    }
    @Test void previewRefusesMissingMappingsAndInvalidTargetsBeforeItCanBeApproved()throws Exception{
        var missing=fixture();((ObjectNode)missing.args).remove("amount");
        assertThrows(Failure.class,()->EffectIntentValidation.preview(JSON.treeToValue(missing.profile,ServiceProfile.class),"send",missing.args));
        for(String scheme:List.of("tcp","https")){
            var f=fixture();var target=(ObjectNode)f.profile.path("destinations").get(0);
            target.put("scheme",scheme);if(scheme.equals("https"))target.put("mode","TUNNEL");
            assertThrows(Failure.class,()->EffectIntentValidation.preview(JSON.treeToValue(f.profile,ServiceProfile.class),"send",f.args));
        }
    }

    @Test void previewEnforcesActualProtocolHeaderAndEncodedQueryLimits()throws Exception{
        var headers=fixture();((ObjectNode)headers.args).put("recipient","x".repeat(8192));
        assertEquals(8192,EffectIntentValidation.preview(JSON.treeToValue(headers.profile,ServiceProfile.class),"send",headers.args)
            .headers().get("x-recipient").get(0).asText().length());
        ((ObjectNode)headers.args).put("recipient","x".repeat(8193));
        assertThrows(Failure.class,()->EffectIntentValidation.preview(JSON.treeToValue(headers.profile,ServiceProfile.class),"send",headers.args));
        var query=fixture();((ObjectNode)query.args).put("account","=".repeat(2728));
        assertEquals(8192,EffectIntentValidation.preview(JSON.treeToValue(query.profile,ServiceProfile.class),"send",query.args).query().length());
        ((ObjectNode)query.args).put("account","=".repeat(2729));
        assertThrows(Failure.class,()->EffectIntentValidation.preview(JSON.treeToValue(query.profile,ServiceProfile.class),"send",query.args));
    }
    @Test void repeatedMappedValuesCannotExpandBeyondTheBodyAllowance()throws Exception{
        var f=fixture();((ObjectNode)f.args).put("note","x".repeat(160000));
        var body=JSON.createObjectNode();var copies=body.putArray("copies");
        for(int i=0;i<16;i++)copies.addObject().put("$arg","/note");
        ((ObjectNode)policy(f).path("intent")).set("body",body);
        assertThrows(Failure.class,()->EffectIntentValidation.preview(JSON.treeToValue(f.profile,ServiceProfile.class),"send",f.args));
        copies.removeAll();for(int i=0;i<8;i++)copies.addObject().put("$arg","/note");
        assertEquals(8,EffectIntentValidation.preview(JSON.treeToValue(f.profile,ServiceProfile.class),"send",f.args).body().path("copies").size());
    }

}
