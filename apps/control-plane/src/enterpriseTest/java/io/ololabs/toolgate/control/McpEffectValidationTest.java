// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.PreparedHttpRequestManifest;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpEffectValidationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    record Fixture(ObjectNode profile,ObjectNode document,ObjectNode manifest,JsonNode arguments,String tool){}
    Fixture fixture()throws Exception{
        var source=Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().resolve("v2/valid.json");
        var corpus=JSON.readTree(Files.readString(source));
        var profile=(ObjectNode)corpus.path("ServiceProfile").deepCopy();
        profile.putArray("routes");profile.putArray("destinations");profile.remove("effectReviews");
        var doc=(ObjectNode)corpus.path("McpProxyDocument").deepCopy();
        ((ObjectNode)doc.path("server")).put("url","https://mcp.example.test/rpc?z=3&a=1&a=2");
        profile.put("packageId","mcp."+doc.path("server").path("slug").asText());
        var tool=(ObjectNode)doc.path("tools").get(0);
        String id=tool.path("toolId").asText();
        var policy=JSON.createObjectNode().put("classification","WRITE").put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
        tool.set("effectPolicy",policy.deepCopy());
        profile.putArray("nativeMcpEffects").addObject().put("toolId",id).put("reviewedBy","admin").put("reviewedAtUnixMs",1000).set("policy",policy);
        var args=JSON.createObjectNode().put("recipient","alice").put("amount",1234);
        var body=JSON.createObjectNode().put("jsonrpc","2.0").put("id","handler-id").put("method","tools/call");
        body.putObject("params").put("name",tool.path("upstreamName").asText()).set("arguments",args);
        var manifest=JSON.createObjectNode().put("version","toolgate-http-request-v1").put("profileId",profile.path("profileId").asText())
            .put("profileDigest",Canonical.digest(profile)).put("routeId",McpEffectValidation.route(id)).put("scheme","https")
            .put("host","mcp.example.test").put("port",443).put("method","POST").put("path","/rpc").put("query","a=1&a=2&z=3")
            .put("contentType","application/json").put("bodyDigest",Canonical.arguments(body))
            .put("authDeclarationId","toolgate-mcp-upstream:"+doc.path("server").path("slug").asText());
        String credential=doc.path("server").path("auth").path("scheme").asText().equals("HEADER")?
            doc.path("server").path("auth").path("header").asText().toLowerCase(java.util.Locale.ROOT):"authorization";
        manifest.putArray("credentialHeaders").add(credential);
        var headers=manifest.putObject("headers");
        headers.putArray("accept").add("application/json, text/event-stream");
        headers.putArray("mcp-protocol-version").add("2025-11-25");
        headers.putArray("mcp-session-id").add("opaque-session");
        return new Fixture(profile,doc,manifest,args,id);
    }
    McpEffectPolicy validate(Fixture f)throws Exception{
        return McpEffectValidation.validate(JSON.treeToValue(f.profile,ServiceProfile.class),
            JSON.treeToValue(f.document,McpProxyDocument.class),f.tool,JSON.treeToValue(f.manifest,PreparedHttpRequestManifest.class),f.arguments,"handler-id");
    }
    void refused(Consumer<ObjectNode> mutate)throws Exception{
        var f=fixture();mutate.accept(f.manifest);assertThrows(Failure.class,()->validate(f));
    }
    @Test void reconstructsExactApprovedBusinessRequestAndCanonicalFixedUrl()throws Exception{
        assertEquals(EffectClassification.WRITE,validate(fixture()).classification());
    }
    @Test void rejectsChangedRecipientToolRpcMethodOwnerAndTarget()throws Exception{
        for(String field:new String[]{"host","path","query","method","bodyDigest","routeId","authDeclarationId","profileDigest"})
            refused(m->m.put(field,"changed"));
        refused(m->m.put("port",8443));refused(m->m.put("scheme","http"));refused(m->m.put("contentType","text/plain"));
        var f=fixture();((ObjectNode)f.arguments).put("recipient","mallory");
        assertThrows(Failure.class,()->validate(f));
        var owner=fixture();assertThrows(Failure.class,()->McpEffectValidation.validate(JSON.treeToValue(owner.profile,ServiceProfile.class),
            JSON.treeToValue(owner.document,McpProxyDocument.class),owner.tool,
            JSON.treeToValue(owner.manifest,PreparedHttpRequestManifest.class),owner.arguments,"other-handler"));
    }
    @Test void protocolContextIsBoundedAndCannotSmuggleBusinessOrCredentialHeaders()throws Exception{
        refused(m->((ObjectNode)m.path("headers")).putArray("x-recipient").add("mallory"));
        refused(m->((ObjectNode)m.path("headers")).putArray("authorization").add("secret"));
        refused(m->((ObjectNode)m.path("headers")).putArray("mcp-session-id").add("x".repeat(1025)));
        refused(m->((ObjectNode)m.path("headers")).putArray("mcp-session-id").add("bad\nvalue"));
        refused(m->((ObjectNode)m.path("headers")).putArray("mcp-protocol-version").add("unknown"));
        refused(m->((ObjectNode)m.path("headers")).remove("mcp-protocol-version"));
        var f=fixture();String before=Canonical.arguments(f.manifest);
        ((ObjectNode)f.manifest.path("headers")).putArray("mcp-session-id").add("another-opaque-session");
        assertNotNull(validate(f));assertNotEquals(before,Canonical.arguments(f.manifest));
        ((ObjectNode)f.manifest.path("headers")).remove("mcp-session-id");assertNotNull(validate(f));
    }
    @Test void requiresExplicitMatchingReviewAndRefusesConsentUntilExactSlotsExist()throws Exception{
        var missing=fixture();missing.profile.remove("nativeMcpEffects");
        missing.manifest.put("profileDigest",Canonical.digest(missing.profile));assertThrows(Failure.class,()->validate(missing));
        var old=fixture();((ObjectNode)old.document.path("tools").get(0)).remove("effectPolicy");
        assertThrows(com.fasterxml.jackson.databind.exc.MismatchedInputException.class,()->validate(old));
        var consent=fixture();var p=(ObjectNode)consent.profile.path("nativeMcpEffects").get(0).path("policy");
        p.put("consentRequired",true);((ObjectNode)consent.document.path("tools").get(0)).set("effectPolicy",p.deepCopy());
        consent.manifest.put("profileDigest",Canonical.digest(consent.profile));assertThrows(Failure.class,()->validate(consent));
        var risk=fixture();var q=(ObjectNode)risk.profile.path("nativeMcpEffects").get(0).path("policy");
        q.put("classification","FINANCIAL");((ObjectNode)risk.document.path("tools").get(0)).set("effectPolicy",q.deepCopy());
        risk.manifest.put("profileDigest",Canonical.digest(risk.profile));assertThrows(Failure.class,()->validate(risk));
    }
    @Test void criticalMcpClassesStillRequireConsentWhenLegacyReviewOptedOut()throws Exception{
        for(String classification:List.of("FINANCIAL","DESTRUCTIVE")){
            var f=fixture();var p=(ObjectNode)f.profile.path("nativeMcpEffects").get(0).path("policy");
            p.put("classification",classification).put("consentRequired",false).put("acknowledgedUnfencedRisk",true);
            ((ObjectNode)f.document.path("tools").get(0)).set("effectPolicy",p.deepCopy());
            f.manifest.put("profileDigest",Canonical.digest(f.profile));
            assertThrows(Failure.class,()->validate(f),classification);
        }
    }

    @Test void nativeConsentPreviewBindsFixedTargetAndCompleteBusinessArguments()throws Exception{
        var f=fixture();var policy=(ObjectNode)f.profile.path("nativeMcpEffects").get(0).path("policy");
        policy.put("classification","FINANCIAL").put("consentRequired",true).put("acknowledgedUnfencedRisk",true);
        ((ObjectNode)f.document.path("tools").get(0)).set("effectPolicy",policy.deepCopy());
        f.manifest.put("profileDigest",Canonical.digest(f.profile));
        var preview=McpEffectValidation.preview(JSON.treeToValue(f.profile,ServiceProfile.class),
            JSON.treeToValue(f.document,McpProxyDocument.class),f.tool,f.arguments);
        assertEquals(EffectConsentMappingKind.NATIVE_MCP,preview.kind());
        assertEquals("mcp.example.test",preview.host());assertEquals("a=1&a=2&z=3",preview.query());
        assertEquals("tools/call",preview.body().path("method").asText());
        assertFalse(preview.body().has("id"));
        assertFalse(preview.headers().containsKey("mcp-session-id"));
        assertEquals(f.arguments,preview.body().path("params").path("arguments"));
        assertThrows(Failure.class,()->validate(f));
        ((ObjectNode)f.arguments).put("recipient","mallory");
        assertEquals("alice",preview.body().path("params").path("arguments").path("recipient").asText());
    }

}
