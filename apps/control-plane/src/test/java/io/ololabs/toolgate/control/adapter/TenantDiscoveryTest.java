// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TenantDiscoveryTest {
    private final ContractCodec codec=new ContractCodec();
    private com.fasterxml.jackson.databind.JsonNode fixture()throws Exception {
        var root=Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().getParent();
        return codec.configuration(Files.readString(root.resolve("tenant-discovery/v2.json")),false);
    }
    private SignedTenantDiscovery vector(com.fasterxml.jackson.databind.JsonNode fixture,String name){return codec.model(codec.json(fixture.path("cases").get(name)),SignedTenantDiscovery.class);}
    private RsaTenantDiscovery.Verified verify(com.fasterxml.jackson.databind.JsonNode fixture,String name,RsaTenantDiscovery.Trust pin){
        return RsaTenantDiscovery.verify(codec,vector(fixture,name),fixture.path("origin").asText(),new TenantId(fixture.path("tenantId").asText()),pin,fixture.path("now").asLong());
    }
    @Test void verifiesPublicInteroperabilityVectorsAndRequiresPreannouncedKeyRotation()throws Exception {
        var f=fixture();var initial=verify(f,"initial",null);assertEquals(2L,initial.manifest().protocolVersion());
        var announced=verify(f,"announced",initial.trust());assertEquals(2,announced.manifest().discoveryKeys().keys().size());
        verify(f,"reordered",announced.trust());var rotated=verify(f,"rotated",announced.trust());
        var retired=verify(f,"retired",rotated.trust());assertEquals(1,retired.manifest().discoveryKeys().keys().size());
        for(var name:List.of("unannounced","unversioned"))assertThrows(Failure.class,()->verify(f,name,initial.trust()),name);
        assertThrows(Failure.class,()->verify(f,"initial",retired.trust()));
        assertThrows(Failure.class,()->verify(f,"revokedSigner",announced.trust()));
        assertThrows(Failure.class,()->verify(f,"rotated",retired.trust()));
        RsaTenantDiscovery.verify(codec,vector(f,"initial"),"https://TENANT.example.test:443",initial.trust().tenant(),null,f.path("now").asLong());
        assertEquals("https://control.tenant.example.test:8443",verify(f,"splitOrigins",null).manifest().controlUrl());
    }
    @Test void rejectsWrongTenantOriginsPurposesAlgorithmsKeysAndExpiredMetadata()throws Exception {
        var f=fixture();var initial=verify(f,"initial",null);long now=f.path("now").asLong();
        for(var name:List.of("otherTenant","wrongPurpose","wrongAlgorithm","remoteKeyHeader","unknownKey","sharedCaKey","wrongVersion","aliasTenant"))assertThrows(Failure.class,()->verify(f,name,initial.trust()),name);
        assertEquals(ErrorCode.TENANT_MISMATCH,assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,vector(f,"initial"),f.path("origin").asText(),new TenantId(f.path("otherTenantId").asText()),null,now)).code());
        assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,vector(f,"initial"),"https://wrong.example.test",initial.trust().tenant(),null,now));
        assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,vector(f,"initial"),f.path("origin").asText(),initial.trust().tenant(),null,now+300000));
        assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,vector(f,"initial"),f.path("origin").asText(),initial.trust().tenant(),null,now-11));
        var parts=vector(f,"initial").jws().split("\\.");byte[] signature=Base64.getUrlDecoder().decode(parts[2]);signature[0]^=1;
        var corrupt=new SignedTenantDiscovery(parts[0]+"."+parts[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
        assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,corrupt,f.path("origin").asText(),initial.trust().tenant(),null,now));
        assertThrows(Failure.class,()->RsaTenantDiscovery.verify(codec,new SignedTenantDiscovery(vector(f,"initial").jws()+"="),f.path("origin").asText(),initial.trust().tenant(),null,now));
    }
    @Test void signingRequiresTheTenantKeyAndRejectsDuplicateOrSharedKeyDomains()throws Exception {
        var f=fixture();var initial=verify(f,"initial",null);var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();
        var pub=RsaTenantDiscovery.publicKey("java-signer",(RSAPublicKey)pair.getPublic());var ring=new DiscoveryKeySet(List.of(pub));
        var body=(ObjectNode)codec.configuration(codec.json(initial.manifest()),false);body.set("discoveryKeys",codec.configuration(codec.json(ring),false));
        var value=codec.model(codec.json(body),TenantDiscovery.class);
        var profile=new io.ololabs.toolgate.control.application.Store.RuntimeProfile(value.serverId(),value.organization(),value.controlUrl(),value.gatewayUrl());
        var signer=new RsaTenantDiscovery(codec,initial.trust().tenant(),profile,(RSAPrivateCrtKey)pair.getPrivate(),pub.kid(),List.of());
        var signed=signer.sign(value,f.path("now").asLong());
        assertEquals(value,RsaTenantDiscovery.verify(codec,signed,f.path("origin").asText(),initial.trust().tenant(),null,f.path("now").asLong()).manifest());
        assertThrows(IllegalArgumentException.class,()->new RsaTenantDiscovery(codec,initial.trust().tenant(),profile,(RSAPrivateCrtKey)pair.getPrivate(),pub.kid(),List.of(((RSAPublicKey)pair.getPublic()).getModulus())));
        var foreign=new RsaTenantDiscovery(codec,new TenantId(f.path("otherTenantId").asText()),profile,(RSAPrivateCrtKey)pair.getPrivate(),pub.kid(),List.of());
        assertThrows(Failure.class,()->foreign.sign(value,f.path("now").asLong()));
        body.withArray("audiences").add("https://unregistered.example.test");
        assertThrows(Failure.class,()->signer.sign(codec.model(codec.json(body),TenantDiscovery.class),f.path("now").asLong()));
        body.set("audiences",codec.configuration(codec.json(value.audiences()),false));
        body.set("discoveryKeys",codec.configuration(codec.json(new DiscoveryKeySet(List.of(pub,pub))),false));
        assertThrows(Failure.class,()->signer.sign(codec.model(codec.json(body),TenantDiscovery.class),f.path("now").asLong()));
    }
}
