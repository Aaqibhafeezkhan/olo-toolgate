// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.FleetTrustKey;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TenantOperatorTest {
    @TempDir Path directory;
    @Test void previewDoesNotRegisterAndOperatorProvisionUsesTheStartupCoordinatorOnce() throws Exception {
        var codec=new ContractCodec();var registry=new TenantRegistry(SqliteState.open(directory.resolve("state.sqlite")),codec);
        var id=Ids.TenantId.create();String template;
        try(var input=getClass().getResourceAsStream("/installation-review.json")){template=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var packet=(ObjectNode)codec.configuration(template,false);var auth=(ObjectNode)packet.get("authorization");
        auth.put("tenantId",id.value());((ObjectNode)auth.get("snapshot")).put("tenantId",id.value());
        auth.put("issuedAtUnixMs",System.currentTimeMillis()-1000);auth.put("expiresAtUnixMs",System.currentTimeMillis()+60000);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update(("OLO ToolGate recovery v1\n"+codec.json(auth)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        packet.putArray("proofs").addObject().put("keyId","reviewer").put("signature",Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
        var publicKey=(RSAPublicKey)key.getPublic();var trust=codec.json(Map.of("keys",List.of(new FleetTrustKey("reviewer",number(publicKey.getModulus()),number(publicKey.getPublicExponent())))));
        var manifest=new InitializationManifest("one",List.of(new InitializationManifest.Spec("one","One",List.of("one.example.test"),codec.json(packet),trust,List.of(),null)));
        var config=new io.smallrye.config.SmallRyeConfigBuilder().withDefaultValue("toolgate.tenancy.mode","subdomain").build();
        var tenancy=new TenancyConfiguration();tenancy.config=config;tenancy.registry=registry;
        var inputs=new ConfigurationImportBootstrap();inputs.config=config;inputs.codec=codec;
        var coordinator=new TenancyBootstrap();coordinator.config=config;coordinator.registry=registry;coordinator.codec=codec;coordinator.tenancy=tenancy;coordinator.inputs=inputs;
        assertEquals("INITIALIZE",coordinator.apply(manifest,true).getFirst().action());assertNull(registry.find("one"));
        coordinator.apply(manifest,false);assertEquals("ACTIVE",registry.find("one").status());
        assertEquals("ALREADY_INITIALIZED",coordinator.apply(manifest,false).getFirst().action());
        registry.suspended(id,true);assertNull(registry.activeHost("one.example.test"));
        registry.suspended(id,false);assertEquals(id,registry.activeHost("one.example.test"));
    }
    @Test void operatorTransportCannotDowngradeOrEmbedCredentials() {
        TenantOperator.databaseTransport("jdbc:postgresql://db.internal/control?sslmode=verify-full",false);
        TenantOperator.databaseTransport("jdbc:postgresql://127.0.0.1/control?sslmode=disable",true);
        for(var url:List.of("jdbc:postgresql://db.internal/control?sslmode=disable","jdbc:postgresql://db.internal/control?sslmode=verify-full&sslmode=disable","jdbc:postgresql://user:secret@db.internal/control?sslmode=verify-full","jdbc:postgresql://db.internal/control","jdbc:postgresql://db.internal/control?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"))assertThrows(RuntimeException.class,()->TenantOperator.databaseTransport(url,true));
        assertThrows(RuntimeException.class,()->TenantOperator.databaseTransport("jdbc:postgresql://127.0.0.1/control?sslmode=disable",false));
    }
    private static String number(java.math.BigInteger value){var raw=value.toByteArray();if(raw[0]==0)raw=Arrays.copyOfRange(raw,1,raw.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
}
