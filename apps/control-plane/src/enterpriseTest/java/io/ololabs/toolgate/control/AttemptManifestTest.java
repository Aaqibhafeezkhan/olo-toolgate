// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual journal/SQL authority: closing a manifest never grants dispatch or replacement authority. */
final class AttemptManifestTest {
    @TempDir Path temp;
    EffectLedgerTest ledger;
    @BeforeAll static void key() throws Exception {EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup(){ledger=new EffectLedgerTest();ledger.temp=temp;ledger.setup();}
    private EnterpriseOperationTest rig(){return ledger.rig;}
    private EnterpriseInvocation stopped(){
        var rig=rig();var i=ledger.invocation;
        return rig.graph.codec.model(rig.operations.report(rig.gateway.tenant(),"device",rig.graph.codec.json(
                new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.FAILED,List.of(),null))).body(),EnterpriseInvocation.class);
    }
    private EnterpriseStore.ManifestSeal seal(){
        var rig=rig();return rig.store.transaction(rig.gateway.tenant(),true,
                tx->tx.enterprise().sealManifest(ledger.invocation.id(),ledger.invocation.reservedNonce(),ledger.now()));
    }
    @Test void authorizationMembershipAndClosureAreAtomicAndImmutable() throws Exception {
        var rig=rig();var i=ledger.invocation;
        ledger.authorize("manifest-first");
        assertEquals(List.of("effect-1"),rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().manifestEffects(i.id(),i.reservedNonce())));
        assertThrows(Failure.class,this::seal);
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().manifestSeal(i.id(),i.reservedNonce())));
        var failed=new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),EnterpriseInvocationState.FAILED,
                i.revision()+1,i.authorizationEpoch(),i.reservedNonce(),i.expiresAtUnixMs(),List.of(),
                i.downstreamIdempotencyKey(),null,i.diagnosticId(),i.reconciliation());
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.enterprise().saveInvocation(failed,ledger.now(),i.revision());
            assertNotNull(tx.enterprise().manifestSeal(i.id(),i.reservedNonce()));
            throw Failure.conflict();}));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().manifestSeal(i.id(),i.reservedNonce())));
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(i.id())).state());
        stopped();
        var record=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().manifestSeal(i.id(),i.reservedNonce()));
        assertNotNull(record);assertEquals(1,record.effectCount());assertEquals(record,seal());
        assertEquals(DirectoryService.digest(record.document()),record.digest());
        var original=ledger.binding("after-closure");
        var another=new EffectStore.Binding("effect-after-closure",original.operationId(),original.attemptId(),
                original.routeId(),original.requestDigest(),original.deviceId(),original.brokerId(),original.handlerId(),
                original.authNonce(),original.expiresAt(),original.outcomeDeadline());
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().authorize(another,ledger.now())));
        assertNull(ledger.get("after-closure"));
        assertEquals(List.of("effect-1"),rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().manifestEffects(i.id(),i.reservedNonce())));
        for(String table:List.of("control_attempt_effects","control_attempt_manifest_seals")){
            try(var c=rig.keyPolicySource.getConnection()){
                c.setAutoCommit(false);tenant(c);
                for(String operation:List.of("UPDATE "+table+" SET attempt_id=attempt_id WHERE tenant_id=?",
                        "DELETE FROM "+table+" WHERE tenant_id=?")){
                    try(var change=c.prepareStatement(operation)){
                        change.setString(1,rig.gateway.tenant().value());
                        assertThrows(java.sql.SQLException.class,change::executeUpdate);
                    }c.rollback();tenant(c);
                }
            }
        }
    }
    private void tenant(java.sql.Connection c)throws Exception{
        if(c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
            try(var s=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                s.setString(1,rig().gateway.tenant().value());s.execute();
            }
    }
    @Test void absentManifestProofCannotUseSqlNullAsPositiveEvidence() throws Exception {
        var rig=rig();var i=stopped();
        try(var c=rig.keyPolicySource.getConnection()){
            c.setAutoCommit(false);tenant(c);
            try(var check=c.prepareStatement("SELECT state FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id=?")){
                check.setString(1,rig.gateway.tenant().value());check.setString(2,i.id());
                try(var rows=check.executeQuery()){assertTrue(rows.next());assertEquals("FAILED",rows.getString(1));}
            }
            try(var change=c.prepareStatement("INSERT INTO control_attempt_manifest_seals(tenant_id,operation_id,attempt_id,invocation_revision,created_at,effect_count,manifest_digest,document) VALUES(?,?,?,?,?,0,?,'{}')")){
                change.setString(1,rig.gateway.tenant().value());change.setString(2,i.id());change.setString(3,i.reservedNonce());
                change.setLong(4,i.revision());change.setLong(5,ledger.now());change.setString(6,"a".repeat(64));
                var failure=assertThrows(java.sql.SQLException.class,change::executeUpdate);
                assertTrue(failure.getMessage().contains("manifest sealing requires closed authority"),failure.getMessage());
            }c.rollback();
        }
        assertEquals(0,seal().effectCount());
    }
    @Test void neverConsumedSettlementRequiresThePositiveExpiredPermitJournal(){
        var rig=rig();var reserved=rig.reserve(rig.submit("never-consumed"));var i=reserved.invocation();
        var failed=new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),EnterpriseInvocationState.FAILED,
                i.revision()+1,i.authorizationEpoch(),i.reservedNonce(),i.expiresAtUnixMs(),List.of(),
                i.downstreamIdempotencyKey(),null,i.diagnosticId(),i.reconciliation());
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveInvocation(failed,ledger.now(),i.revision());return null;});
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().settleAttempt(
                i.id(),i.reservedNonce(),EnterpriseStore.SettlementProof.NEVER_CONSUMED,ledger.now())));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptSettlement(i.id(),i.reservedNonce())));
        rig.clock=java.time.Clock.offset(rig.clock,java.time.Duration.ofMillis(10001));
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().settleAttempt(
                i.id(),i.reservedNonce(),EnterpriseStore.SettlementProof.QUALIFIED_BROKER_ONLY,ledger.now())));
        var settled=rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().settleAttempt(
                i.id(),i.reservedNonce(),EnterpriseStore.SettlementProof.NEVER_CONSUMED,ledger.now()));
        assertEquals(EnterpriseStore.SettlementProof.NEVER_CONSUMED,settled.proof());
        assertEquals(DirectoryService.digest(settled.document()),settled.digest());
    }
    @Test void membershipAndClosureHoldOnThePostgresRuntimeRole() throws Exception {
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL fixture");
        for(int scenario:List.of(0,1,2)){
            String name="manifests_"+UUID.randomUUID().toString().replace("-","");
            try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){
                s.execute("CREATE DATABASE "+name);
            }
            String url=base.replace("/control?","/"+name+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            var rig=rig();rig.keyPolicySource=source;
            rig.store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
            EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
            rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
            ledger.start();
            if(scenario==1)absentManifestProofCannotUseSqlNullAsPositiveEvidence();
            else if(scenario==2)neverConsumedSettlementRequiresThePositiveExpiredPermitJournal();
            else authorizationMembershipAndClosureAreAtomicAndImmutable();
        }
    }
}
