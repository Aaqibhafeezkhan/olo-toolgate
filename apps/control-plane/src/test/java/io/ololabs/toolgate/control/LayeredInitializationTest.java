// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class LayeredInitializationTest {
    @TempDir Path temp;
    private final ContractCodec codec=new ContractCodec();
    private record Review(String document,List<FleetTrustKey> trust,EnterpriseReviewedRecovery packet){}
    private Review review(Ids.TenantId tenant) throws Exception {
        String template;try(var stream=getClass().getResourceAsStream("/installation-review.json")){template=new String(Objects.requireNonNull(stream).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var envelope=(ObjectNode)codec.configuration(template,false);var auth=(ObjectNode)envelope.get("authorization");
        auth.put("tenantId",tenant.value());((ObjectNode)auth.get("snapshot")).put("tenantId",tenant.value());
        auth.put("issuedAtUnixMs",System.currentTimeMillis()-1000);auth.put("expiresAtUnixMs",System.currentTimeMillis()+60000);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(("OLO ToolGate recovery v1\n"+codec.json(auth)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var proof=envelope.putArray("proofs").addObject();proof.put("keyId","reviewer");proof.put("signature",Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        var publicKey=(RSAPublicKey)key.getPublic();
        var trust=List.of(new FleetTrustKey("reviewer",number(publicKey.getModulus()),number(publicKey.getPublicExponent())));
        var document=codec.json(envelope);return new Review(document,trust,new ReviewedRecovery(null,codec,java.time.Clock.systemUTC()).validate(document,trust));
    }
    private String number(java.math.BigInteger value){var raw=value.toByteArray();if(raw[0]==0)raw=Arrays.copyOfRange(raw,1,raw.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
    @Test void settingsObjectsInheritButArraysAndExplicitNullReplace() {
        var target=(ObjectNode)codec.configuration("{\"group\":{\"keep\":true,\"owner\":\"admin\",\"ids\":[\"old\"]}}",false);
        TenantInitialization.merge(target,codec.configuration("{\"group\":{\"owner\":null,\"ids\":[]}}",false));
        assertTrue(target.path("group").path("keep").asBoolean());assertTrue(target.path("group").get("owner").isNull());
        assertTrue(target.path("group").path("ids").isEmpty());
    }
    @Test void commonAndTenantLayersCopyIntoIndependentOneTimeConfigurations() throws Exception {
        var data=SqliteState.open(temp.resolve("layers.sqlite"));var registry=new TenantRegistry(data,codec);var store=new PostgresStore(data,codec);
        var initializer=new TenantInitialization(codec,ClientCheckInPolicy.QUICKSTART);
        var common=new TenantInitialization.Layer("common.json",codec.configuration("{\"formatVersion\":2,\"toolGroups\":[{\"id\":\"shared-group\",\"name\":\"Common\",\"enabled\":true,\"revision\":1,\"toolIds\":[]}],\"settings\":{\"gateway\":{\"gatewayName\":\"Common\"},\"deviceApproval\":{\"durationMs\":604800000}}}",false),true);
        var ids=new ArrayList<Ids.TenantId>();
        for(String alias:List.of("tenant-a","tenant-b")){
            var tenant=registry.allocate(alias,alias);ids.add(tenant.id());var reviewed=review(tenant.id());
            var own=(ObjectNode)codec.configuration("{\"formatVersion\":2,\"toolGroups\":[{\"id\":\"shared-group\",\"name\":\""+alias+"\",\"enabled\":true,\"revision\":1,\"toolIds\":[]}],\"settings\":{\"gateway\":{\"gatewayName\":\""+alias+"\"}}}",false);own.put("tenantId",tenant.id().value());
            var layers=List.of(common,new TenantInitialization.Layer(alias+".json",own,false));
            var environment=codec.configuration("{\"configuration\":{\"requiredReviews\":0}}",false);
            String preview=initializer.preview(tenant.id(),reviewed.packet(),layers,environment),digest=DirectoryService.digest(preview);
            assertTrue(registry.initialize(tenant.id(),digest,"[]",List.of(alias+".example.com"),unit->initializer.apply(unit,tenant.id(),reviewed.document(),reviewed.trust(),layers,environment)));
            store.transaction(tenant.id(),false,tx->{
                assertTrue(tx.load().entries().get(Ids.Kind.TOOL_GROUP.id("shared-group")).document().contains(alias));
                var settings=new ServerSettingsService(store,codec,ClientCheckInPolicy.QUICKSTART).current(tx);
                assertEquals(alias,settings.gateway().gatewayName());assertEquals(604800000L,settings.deviceApproval().durationMs());assertEquals(0L,settings.configuration().requiredReviews());
                return null;
            });
            assertFalse(registry.initialize(tenant.id(),"f".repeat(64),"changed",List.of("other.example.com"),unit->{throw new AssertionError("Never replay initialization");}));
        }
        assertNotEquals(ids.get(0),ids.get(1));
        assertEquals("Common",common.snapshot().path("toolGroups").get(0).path("name").asText(),"Templates are not live inheritance or mutable shared state");
        try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM control_tenant_initializations")){assertTrue(r.next());assertEquals(2,r.getInt(1));}
    }
    @Test void wrongTenantDuplicateRecordsAndTenantSpecificCommonInputsAreRejected() throws Exception {
        var tenant=Ids.TenantId.create();var initializer=new TenantInitialization(codec,ClientCheckInPolicy.QUICKSTART);
        for(var layer:List.of(
            new TenantInitialization.Layer("wrong.json",codec.configuration("{\"tenantId\":\""+Ids.TenantId.create().value()+"\"}",false),false),
            new TenantInitialization.Layer("common.json",codec.configuration("{\"users\":[{\"id\":\"admin\",\"name\":\"Admin\",\"enabled\":true,\"revision\":1}]}",false),true),
            new TenantInitialization.Layer("common.json",codec.configuration("{\"settings\":{\"deviceApproval\":{\"ownerUserId\":\"admin\"}}}",false),true),
            new TenantInitialization.Layer("duplicate.json",codec.configuration("{\"toolGroups\":[{\"id\":\"same\",\"name\":\"One\",\"enabled\":true,\"revision\":1,\"toolIds\":[]},{\"id\":\"same\",\"name\":\"Two\",\"enabled\":true,\"revision\":1,\"toolIds\":[]}]}",false),true)))
            assertThrows(Failure.class,()->initializer.validateLayer(layer,tenant));
        var reviewed=review(tenant);var bad=new TenantInitialization.Layer("invalid-owner",codec.configuration("{\"tenantId\":\""+tenant.value()+"\",\"settings\":{\"deviceApproval\":{\"autoApprove\":true,\"ownerUserId\":\"missing\"}}}",false),false);
        assertThrows(Failure.class,()->initializer.preview(tenant,reviewed.packet(),List.of(bad),codec.configuration("{}",false)));
    }
}
