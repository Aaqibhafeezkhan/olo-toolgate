// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseRecoveryTest {
    @TempDir java.nio.file.Path temp;
    EnterpriseConformanceTest graph;PostgresStore store;EnterpriseRecoveryAuthorization authorization;List<KeyPair> keys;List<FleetTrustKey> trust;
    @BeforeEach void setup()throws Exception{
        graph=new EnterpriseConformanceTest();graph.graph();store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("recovery.sqlite"))),graph.codec);
        store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=List.of(generator.generateKeyPair(),generator.generateKeyPair());
        trust=new ArrayList<>();for(int index=0;index<keys.size();index++){var n=((RSAPublicKey)keys.get(index).getPublic()).getModulus().toByteArray();if(n[0]==0)n=Arrays.copyOfRange(n,1,n.length);trust.add(new FleetTrustKey("reviewer-"+index,Base64.getUrlEncoder().withoutPadding().encodeToString(n),"AQAB"));}
        var conditions=new EnterpriseConditions(EnterpriseConformanceTest.NOW,EnterpriseConformanceTest.NOW+60000,List.of(),List.of(),List.of(),List.of(),true,false,null,null);
        var rules=Arrays.stream(EnterpriseGroupType.values()).map(kind->new EnterpriseManagementRule(List.of("administration.recover","user.read"),kind,new GroupSelection(List.of(),true),List.of(),conditions,null)).toList();
        graph.add(Ids.Kind.ROLE,new ControlRole("reviewed-recovery","Emergency recovery",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,rules));
        graph.add(Ids.Kind.TEAM,new ControlTeam("reviewed-recovery-team","Reviewed recovery",true,1L,List.of("admin"),List.of("reviewed-recovery")));
        authorization=new EnterpriseRecoveryAuthorization(1L,EnterpriseConformanceTest.TENANT.value(),7L,graph.codec.model(graph.codec.snapshot(EnterpriseConformanceTest.TENANT,graph.directory(),false),ControlSnapshot.class),"b".repeat(64),EnterpriseConformanceTest.NOW,EnterpriseConformanceTest.NOW+60000);
    }
    EnterpriseReviewedRecovery signed(EnterpriseRecoveryAuthorization value)throws Exception{
        var proofs=new ArrayList<EnterpriseRecoveryProof>();for(int index=0;index<keys.size();index++){var signature=Signature.getInstance("SHA256withRSA");signature.initSign(keys.get(index).getPrivate());signature.update(("OLO ToolGate recovery v1\n"+graph.codec.json(value)).getBytes(java.nio.charset.StandardCharsets.UTF_8));proofs.add(new EnterpriseRecoveryProof(trust.get(index).kid(),Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())));}return new EnterpriseReviewedRecovery(value,proofs);
    }
    ReviewedRecovery service(long now){return new ReviewedRecovery(store,graph.codec,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));}
    @Test void independentlySignedReviewAppliesOnceAndNeverResurrectsExpiredAuthority()throws Exception{
        var packet=graph.codec.json(signed(authorization));service(EnterpriseConformanceTest.NOW+1).apply(packet,trust);
        assertEquals(8L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision()).longValue());
        service(EnterpriseConformanceTest.NOW+60001).apply(packet,trust);
        assertEquals(8L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision()).longValue());
        store.transaction(EnterpriseConformanceTest.TENANT,false,tx->{var role=graph.codec.model(tx.load().entries().get(Ids.Kind.ROLE.id("reviewed-recovery")).document(),ControlRole.class);assertFalse(ManagementAccess.current(role.managementRules().getFirst().conditions(),EnterpriseConformanceTest.NOW+60001));assertTrue(tx.auditPage(0,20).contains("RECOVERY_APPLY"));return null;});
    }
    @Test void tamperedExpiredOrAliasedReviewerKeysCannotBootstrap()throws Exception{
        var packet=signed(authorization);var duplicate=new EnterpriseReviewedRecovery(authorization,List.of(packet.proofs().getFirst(),packet.proofs().getFirst()));
        assertThrows(Failure.class,()->service(EnterpriseConformanceTest.NOW).apply(graph.codec.json(duplicate),trust));
        var tampered=new EnterpriseRecoveryAuthorization(authorization.formatVersion(),authorization.tenantId(),authorization.expectedRevision(),authorization.snapshot(),"c".repeat(64),authorization.issuedAtUnixMs(),authorization.expiresAtUnixMs());
        assertThrows(Failure.class,()->service(EnterpriseConformanceTest.NOW).apply(graph.codec.json(new EnterpriseReviewedRecovery(tampered,packet.proofs())),trust));
        assertThrows(Failure.class,()->service(authorization.expiresAtUnixMs()).apply(graph.codec.json(packet),trust));
        assertEquals(7L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision()).longValue());
    }
    @Test void recoveryCannotAddRuntimePermissionsEvenWithGenuineReviewSignatures()throws Exception{
        graph.grant("unrelated-runtime-grant","team-b",EnterpriseSourceType.TEAM,EnterpriseGrantPurpose.HUMAN,graph.scope("tools-a","devices-a"));
        var expanded=new EnterpriseRecoveryAuthorization(1L,authorization.tenantId(),7L,graph.codec.model(graph.codec.snapshot(EnterpriseConformanceTest.TENANT,graph.directory(),false),ControlSnapshot.class),authorization.reasonDigest(),authorization.issuedAtUnixMs(),authorization.expiresAtUnixMs());
        var packet=graph.codec.json(signed(expanded));assertThrows(Failure.class,()->service(EnterpriseConformanceTest.NOW).apply(packet,trust));
        assertEquals(7L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision()).longValue());
    }
    @Test void pythonInstallationPacketInitializesOnlyAnEmptyTenant()throws Exception{
        String packet=new String(getClass().getResourceAsStream("/installation-review.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var value=graph.codec.model(packet,EnterpriseReviewedRecovery.class);
        var node=(com.fasterxml.jackson.databind.JsonNode)graph.codec.value(new String(getClass().getResourceAsStream("/installation-trust.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        var pins=new ArrayList<FleetTrustKey>();for(var key:node.path("keys"))pins.add(graph.codec.model(graph.codec.json(key),FleetTrustKey.class));
        var fresh=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("fresh.sqlite"))),graph.codec);
        new ReviewedRecovery(fresh,graph.codec,Clock.fixed(Instant.ofEpochMilli(value.authorization().issuedAtUnixMs()+1),ZoneOffset.UTC)).apply(packet,pins);
        fresh.transaction(new Ids.TenantId(value.authorization().tenantId()),false,tx->{assertEquals("admin",tx.bootstrapAdminUserId());assertTrue(new ManagementAccess(graph.codec).superAdministrator(tx.load(),tx.bootstrapAdminUserId()));return null;});
        fresh.transaction(new Ids.TenantId(value.authorization().tenantId()),false,tx->{assertEquals(1L,tx.load().revision());assertTrue(tx.load().entries().values().stream().filter(e->e.id().kind()==Ids.Kind.GRANT).allMatch(e->e.id().value().startsWith("standard-")));for(var e:tx.load().entries().values())if(GroupGraph.group(e.id().kind())&&e.id().value().matches("(ReadOnly|ReadAndWrite|Admin).*"))assertTrue(GroupGraph.members(e,graph.codec).isEmpty());return null;});
    }
    @Test void consumedHistoricalInstallationPacketIsAnExactNoOpWithoutLegacyRuleDecoding(){
        var oldPacket="{\"authorization\":{\"tenantId\":\""+EnterpriseConformanceTest.TENANT.value()+"\",\"snapshot\":{\"roles\":[{\"managementRules\":[{\"actions\":[\"read\"]}]}]}},\"proofs\":[]}";
        assertThrows(Failure.class,()->service(EnterpriseConformanceTest.NOW).apply(oldPacket,List.of()));
        var digest=DirectoryService.digest(graph.codec.json(graph.codec.value(oldPacket)));
        store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.enterprise().rememberRecovery(digest);return null;});
        service(EnterpriseConformanceTest.NOW).apply(oldPacket,List.of());
        assertEquals(7L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.load().revision()));
        assertThrows(Failure.class,()->service(EnterpriseConformanceTest.NOW).apply(oldPacket.replace("read","update"),List.of()));
    }

}
