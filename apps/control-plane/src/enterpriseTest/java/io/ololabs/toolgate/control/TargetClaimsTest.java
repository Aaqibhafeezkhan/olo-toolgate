// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetClaimsTest {
    @TempDir Path temp;
    EffectGrantTest f;TargetClaims targets;EffectGrant grant;EffectGrantClaims claims;
    static final String OWNER="c".repeat(64),OTHER="d".repeat(64),RESULT="sha256:"+"e".repeat(64);
    @BeforeAll static void key()throws Exception{EffectGrantTest.key();}
    @BeforeEach void setup()throws Exception{setupTarget(FencedTargetClass.SHARED_SQL);}
    void setupTarget(FencedTargetClass type)throws Exception{
        f=new EffectGrantTest();f.temp=temp;f.fencedTarget=new FencedTarget("billing",type);f.setup();
        targets=new TargetClaims(f.base.rig.store,f.base.rig.graph.codec,f.base.rig.operations);
        grant=f.issue();claims=f.base.rig.signer.verifyEffect(grant.grant());f.send(grant,"handler");
    }
    void advance(long millis){
        f.base.rig.advanceKeyClock(millis);
        targets=new TargetClaims(f.base.rig.store,f.base.rig.graph.codec,f.base.rig.operations);
    }
    String document(Object value){return f.base.rig.graph.codec.json(value);}
    ServicePermitClaimResult consume(String target,String token){
        return f.base.rig.graph.codec.model(targets.consume(f.base.rig.gateway,target,document(new TargetClaimConsumption(
            grant.grant(),token,claims.httpRequestDigest(),claims.executorKeySha256()))).body(),ServicePermitClaimResult.class);
    }
    ServicePermitClaimReference ref(String token){return new ServicePermitClaimReference(claims.authNonce(),token);}
    @Test void onlyFirstOwnerConsumesReplayIsStatusAndLateOutcomeIsDurable(){
        assertEquals(ServicePermitClaimState.CONSUMED,consume("billing",OWNER).status());
        assertEquals(ServicePermitClaimState.CONSUMED,consume("billing",OWNER).status());
        assertEquals(ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM,consume("billing",OTHER).status());
        assertThrows(Failure.class,()->consume("other",OWNER));
        advance(3000);
        assertEquals(ServicePermitClaimState.CONSUMED,consume("billing",OWNER).status());
        var evidence=new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.COMPLETED,RESULT);
        var first=targets.outcome(f.base.rig.gateway,"billing",document(evidence));
        assertEquals("COMPLETED",f.base.rig.graph.codec.model(first.body(),ServicePermitClaimResult.class).status().name());
        assertEquals(first.body(),targets.outcome(f.base.rig.gateway,"billing",document(evidence)).body());
        assertThrows(Failure.class,()->targets.outcome(f.base.rig.gateway,"other",document(evidence)));
        assertThrows(Failure.class,()->targets.outcome(f.base.rig.gateway,"billing",document(new ServicePermitTargetOutcome(
            ref(OTHER),ServicePermitTargetOutcomeKind.COMPLETED,RESULT))));
        assertEquals(ServicePermitClaimState.COMPLETED,consume("billing",OWNER).status());
    }
    @Test void revocationCommittedBeforeConsumptionPreventsAnyTargetAuthority(){
        f.base.rig.graph.remove(Ids.Kind.GRANT,"human");f.base.rig.saveGraph();
        assertThrows(Failure.class,()->consume("billing",OWNER));
        var retained=f.base.rig.store.transaction(f.base.rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.authNonce()));
        assertNull(retained.consumer());assertNotEquals("CONSUMED",retained.state());
    }
    @Test void expiredUnconsumedGrantAndWrongAcceptedPeerOrDigestCannotConsume(){
        for(var request:List.of(new TargetClaimConsumption(grant.grant(),OWNER,"sha256:"+"a".repeat(64),claims.executorKeySha256()),
                new TargetClaimConsumption(grant.grant(),OWNER,claims.httpRequestDigest(),"b".repeat(64)))){
            assertThrows(Failure.class,()->targets.consume(f.base.rig.gateway,"billing",document(request)));
        }
        advance(2001);assertThrows(Failure.class,()->consume("billing",OWNER));
        assertThrows(Failure.class,()->targets.inspect(f.base.rig.gateway,"billing",document(ref(OWNER))));
    }
    @Test void staleCurrentProfileCannotConsumeAnAlreadySentGrant(){
        f.base.rig.store.transaction(f.base.rig.gateway.tenant(),true,tx->{
            tx.packages().saveDeployment(f.profile.path("packageId").asText(),"{\"packageDigest\":\""+f.packageDigest+"\",\"serviceProfileDigest\":\"sha256:"+"f".repeat(64)+"\"}");return null;});
        assertThrows(Failure.class,()->consume("billing",OWNER));
    }
    @Test void conflictingTargetEvidenceFencesInsteadOfOverwritingCompletion(){
        consume("billing",OWNER);
        targets.outcome(f.base.rig.gateway,"billing",document(new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.COMPLETED,RESULT)));
        var reply=targets.outcome(f.base.rig.gateway,"billing",document(new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.FAILED,"sha256:"+"f".repeat(64))));
        assertEquals(ServicePermitClaimState.CONFLICT,f.base.rig.graph.codec.model(reply.body(),ServicePermitClaimResult.class).status());
    }    @Test void claimCrashBeforeConsumptionRequiresExactNotStartedWitnessAndNeverGrantsAuthority(){
        assertThrows(Failure.class,()->targets.outcome(f.base.rig.gateway,"billing",document(
            new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.COMPLETED,RESULT))));
        assertThrows(Failure.class,()->targets.outcome(f.base.rig.gateway,"billing",document(
            new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.FAILED,RESULT))));
        String notStarted="sha256:"+DirectoryService.digest("toolgate-target-not-started-v1");
        advance(3000);
        var evidence=new ServicePermitTargetOutcome(ref(OWNER),ServicePermitTargetOutcomeKind.FAILED,notStarted);
        assertThrows(Failure.class,()->targets.outcome(f.base.rig.gateway,"other",document(evidence)));
        var result=targets.outcome(f.base.rig.gateway,"billing",document(evidence));
        assertEquals(ServicePermitClaimState.FAILED,f.base.rig.graph.codec.model(result.body(),ServicePermitClaimResult.class).status());
        assertEquals(ServicePermitClaimState.FAILED,consume("billing",OWNER).status());
        assertEquals(ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM,consume("billing",OTHER).status());
    }
    @Test void concurrentReplicasSharingOneIdentityHaveOnlyOneConsumedClaim()throws Exception{
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var first=pool.submit(()->{start.await();return consume("billing",OWNER).status();});
            var second=pool.submit(()->{start.await();return consume("billing",OTHER).status();});
            start.countDown();
            var statuses=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(statuses,ServicePermitClaimState.CONSUMED));
            assertEquals(1,Collections.frequency(statuses,ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM));
        }finally{pool.shutdownNow();}
    }
    @Test void changedDeclaredTargetClassIsNotAuthorityEvenWithAValidSignature(){
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)f.base.rig.graph.codec.value(document(claims));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("fencedTarget")).put("targetClass","SQL_WITH_EXTERNAL_ACTION");
        var substituted=f.base.rig.signer.signEffect(f.base.rig.graph.codec.model(node.toString(),EffectGrantClaims.class));
        assertThrows(Failure.class,()->targets.consume(f.base.rig.gateway,"billing",document(new TargetClaimConsumption(
            substituted,OWNER,claims.httpRequestDigest(),claims.executorKeySha256()))));
    }
    @Test void disabledReviewerCannotQualifyAnAlreadySentFencedGrant(){
        var id=Ids.Kind.USER.id("admin");
        var entry=f.base.rig.graph.entries.get(id);
        var doc=(com.fasterxml.jackson.databind.node.ObjectNode)f.base.rig.graph.codec.value(entry.document());
        doc.put("enabled",false);
        f.base.rig.graph.entries.put(id,f.base.rig.graph.codec.revision(f.base.rig.graph.codec.entry(id.kind(),doc.toString()),entry.revision()+1));
        f.base.rig.saveGraph();
        assertThrows(Failure.class,()->consume("billing",OWNER));
    }
    @Test void externalActionClassHasTheSameConsumptionFenceWithoutInventingBusinessFinality()throws Exception{
        temp=java.nio.file.Files.createDirectory(temp.resolve("external-target"));
        setupTarget(FencedTargetClass.SQL_WITH_EXTERNAL_ACTION);
        assertEquals(FencedTargetClass.SQL_WITH_EXTERNAL_ACTION,claims.fencedTarget().targetClass());
        assertEquals(ServicePermitClaimState.CONSUMED,consume("billing",OWNER).status());
        advance(3000);
        assertEquals(ServicePermitClaimState.CONSUMED,consume("billing",OWNER).status());
        assertEquals(ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM,consume("billing",OTHER).status());
    }
    @FunctionalInterface interface Scenario {void run()throws Exception;}
    void postgresScenario(FencedTargetClass type,Scenario scenario)throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL");
        // Each case gets a sibling directory; nesting would exhaust SQLite's path budget.
        Path suite=temp;temp=java.nio.file.Files.createDirectory(suite.resolve("case-"+UUID.randomUUID()));
        try{postgresCase(base,password,type,scenario);}finally{temp=suite;}
    }
    void postgresCase(String base,String password,FencedTargetClass type,Scenario scenario)throws Exception{
        setupTarget(type);
        String database="target_fencing_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("CREATE DATABASE "+database);}
        try{
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            var rig=f.base.rig;rig.keyPolicySource=source;
            rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
            EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
            rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
            f.base.permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
            f.prepare();targets=new TargetClaims(rig.store,rig.graph.codec,rig.operations);
            grant=f.issue();claims=rig.signer.verifyEffect(grant.grant());f.send(grant,"handler");
            scenario.run();
        }finally{
            try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }
    @Test void currentPostgresRuntimeRolesRunTargetOwnershipRevocationRecoveryAndConflictCases()throws Exception{
        for(var type:FencedTargetClass.values())postgresScenario(type,this::onlyFirstOwnerConsumesReplayIsStatusAndLateOutcomeIsDurable);
        for(Scenario scenario:List.<Scenario>of(this::revocationCommittedBeforeConsumptionPreventsAnyTargetAuthority,
                this::expiredUnconsumedGrantAndWrongAcceptedPeerOrDigestCannotConsume,
                this::staleCurrentProfileCannotConsumeAnAlreadySentGrant,
                this::conflictingTargetEvidenceFencesInsteadOfOverwritingCompletion,
                this::claimCrashBeforeConsumptionRequiresExactNotStartedWitnessAndNeverGrantsAuthority,
                this::concurrentReplicasSharingOneIdentityHaveOnlyOneConsumedClaim,
                this::disabledReviewerCannotQualifyAnAlreadySentFencedGrant))
            postgresScenario(FencedTargetClass.SHARED_SQL,scenario);
    }

}
