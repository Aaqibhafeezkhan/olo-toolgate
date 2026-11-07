// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.RsaFleetCrypto;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.security.*;
import java.security.interfaces.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real current authority, both signature domains, SQL attempt scope and fenced target ordering. */
class KillAuthorityTest {
    @TempDir Path temp;
    static KeyPair organization,release;
    EffectGrantTest f;
    @BeforeAll static void keys()throws Exception{
        EffectGrantTest.key();var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        organization=generator.generateKeyPair();release=generator.generateKeyPair();
    }
    static FleetTrustKey trust(String id,KeyPair pair){
        var bytes=((RSAPublicKey)pair.getPublic()).getModulus().toByteArray();
        if(bytes[0]==0)bytes=Arrays.copyOfRange(bytes,1,bytes.length);
        return new FleetTrustKey(id,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),"AQAB");
    }
    @BeforeEach void setup()throws Exception{
        f=new EffectGrantTest();f.temp=temp;f.fencedTarget=new FencedTarget("billing",FencedTargetClass.SHARED_SQL);f.setup();
    }
    KillStore.Event kill(KillScope scope,KillMode mode){
        var rig=f.base.rig;var codec=rig.graph.codec;
        KillCrypto signer=new RsaFleetCrypto(codec,(RSAPrivateCrtKey)organization.getPrivate(),"organization",
            List.of(trust("release",release)),List.of(trust("organization",organization)),List.of());
        return rig.store.transaction(rig.gateway.tenant(),true,tx->{
            KillPolicy.validate(scope);
            long counter=tx.kills().nextCounter("control");var directory=tx.load();
            tx.save(directory,new Directory(directory.revision()+1,directory.entries()));
            long epoch=tx.enterprise().authorizationEpoch();String id="control:"+counter;
            var model=new KillEvent(id,KillIssuer.control,scope,mode,"Emergency",EnterpriseConformanceTest.NOW,
                "e30.e30.c2ln",rig.gateway.tenant().value(),epoch);
            var unsigned=(ObjectNode)codec.value(codec.json(model));unsigned.remove("signature");
            String signature=signer.signEvent(unsigned);assertEquals(unsigned,signer.verifyEvent(signature));
            unsigned.put("signature",signature);
            var e=new KillStore.Event(id,"control",counter,"KILL",scope.kind().name(),scope.value(),mode.name(),
                epoch,EnterpriseConformanceTest.NOW,codec.json(unsigned));
            tx.kills().append(e);return e;
        });
    }
    ServicePermitClaimResult consume(EffectGrant grant){
        var rig=f.base.rig;var claims=rig.signer.verifyEffect(grant.grant());
        return rig.graph.codec.model(new TargetClaims(rig.store,rig.graph.codec,rig.operations).consume(rig.gateway,"billing",
            rig.graph.codec.json(new TargetClaimConsumption(grant.grant(),"c".repeat(64),claims.httpRequestDigest(),claims.executorKeySha256()))).body(),ServicePermitClaimResult.class);
    }
    @Test void terminateCommittedBeforeConsumeRefusesAnAlreadySentGrant(){
        var grant=f.issue();f.send(grant,"handler");
        kill(new KillScope(KillScopeKind.TOOL,f.invocation.evaluation().toolId()),KillMode.TERMINATE);
        assertThrows(Failure.class,()->consume(grant));
        assertThrows(Failure.class,f::issue);
        var rig=f.base.rig;var claims=rig.signer.verifyEffect(grant.grant());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.authNonce()).consumer()));
        var heartbeat=rig.graph.codec.model(rig.operations.heartbeat(rig.gateway.tenant(),tx->"device",f.invocation.id(),
            rig.graph.codec.json(new LeaseHeartbeat(f.invocation.id(),f.invocation.reservedNonce(),null,null))).body(),LeaseRenewal.class);
        assertTrue(heartbeat.cancelRequested());
    }
    @Test void stopNewDoesNotRevokeRunningEffectsButRefusesAnotherAdmission()throws Exception{
        kill(new KillScope(KillScopeKind.TENANT,null),KillMode.STOP_NEW);
        var grant=f.issue();f.send(grant,"handler");assertEquals(ServicePermitClaimState.CONSUMED,consume(grant).status());
        assertThrows(Failure.class,()->f.base.start("new-after-stop"));
    }
    @Test void unrelatedKillEpochDoesNotInvalidateAnExistingExactGrant(){
        var grant=f.issue();
        kill(new KillScope(KillScopeKind.TOOL,"other.tool/invoke"),KillMode.TERMINATE);
        f.send(grant,"handler");assertEquals(ServicePermitClaimState.CONSUMED,consume(grant).status());
    }
    @Test void interveningNonKillAuthorityRevisionCannotBeBridged(){
        var grant=f.issue();
        kill(new KillScope(KillScopeKind.TOOL,"other.tool/invoke"),KillMode.TERMINATE);
        var rig=f.base.rig;
        rig.store.transaction(rig.gateway.tenant(),true,tx->{var directory=tx.load();tx.save(directory,new Directory(directory.revision()+1,directory.entries()));return null;});
        assertThrows(Failure.class,()->f.send(grant,"handler"));
    }
    @Test void absentPackageMetadataCannotBeReplacedByTheToolNamePrefix(){
        var rig=f.base.rig;
        var scope=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().scope(f.invocation.reservedNonce()));
        assertNull(scope.subject().packageId());
        assertEquals("sha256:"+f.invocation.evaluation().packageDigest(),scope.subject().packageDigest());
        var grant=f.issue();f.send(grant,"handler");
        kill(new KillScope(KillScopeKind.PACKAGE,"different-package"),KillMode.TERMINATE);
        assertThrows(Failure.class,()->consume(grant));
    }

    @Test void attemptScopeIsRetainedAndCannotBeChangedAfterConsumption(){
        var rig=f.base.rig;
        var scope=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().scope(f.invocation.reservedNonce()));
        assertNotNull(scope);assertEquals(f.invocation.id(),scope.operation());
        assertEquals(f.invocation.evaluation().toolId(),scope.subject().toolId());
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.kills().bindScope(scope);return null;});
        var changed=new KillStore.Scope(scope.operation(),scope.attempt(),scope.device(),
            new KillPolicy.Subject(scope.subject().packageId(),scope.subject().packageDigest(),"other-publisher",scope.subject().toolId(),"moved-pool",scope.subject().imageDigest()));
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.kills().bindScope(changed);return null;}));
    }
    @Test void sqlGuardsIndependentlyRefuseTerminatedEffectConsumption(){
        var grant=f.issue();f.send(grant,"handler");
        var rig=f.base.rig;var claims=rig.signer.verifyEffect(grant.grant());
        kill(new KillScope(KillScopeKind.TOOL,f.invocation.evaluation().toolId()),KillMode.TERMINATE);
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().consume(
            claims.authNonce(),claims.httpRequestDigest(),"TARGET:billing","c".repeat(64),EnterpriseConformanceTest.NOW)));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.authNonce()).consumer()));
    }
    @Test void committedConsumptionRetainsLateTargetEvidenceButGrantsNoFurtherEffects(){
        var grant=f.issue();f.send(grant,"handler");assertEquals(ServicePermitClaimState.CONSUMED,consume(grant).status());
        kill(new KillScope(KillScopeKind.TENANT,null),KillMode.TERMINATE);
        assertEquals(ServicePermitClaimState.CONSUMED,consume(grant).status());
        var rig=f.base.rig;var claims=rig.signer.verifyEffect(grant.grant());var targets=new TargetClaims(rig.store,rig.graph.codec,rig.operations);
        var outcome=new ServicePermitTargetOutcome(new ServicePermitClaimReference(claims.authNonce(),"c".repeat(64)),
            ServicePermitTargetOutcomeKind.COMPLETED,"sha256:"+"e".repeat(64));
        assertEquals(ServicePermitClaimState.COMPLETED,rig.graph.codec.model(targets.outcome(rig.gateway,"billing",rig.graph.codec.json(outcome)).body(),ServicePermitClaimResult.class).status());
        assertThrows(Failure.class,f::issue);
    }
    @FunctionalInterface interface Scenario {void run()throws Exception;}
    void postgres(Scenario scenario)throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires owned PostgreSQL");
        // Each case gets a sibling directory; nesting would exhaust SQLite's path budget.
        Path suite=temp;temp=java.nio.file.Files.createDirectory(suite.resolve("case-"+UUID.randomUUID()));
        try{postgresCase(base,password,scenario);}finally{temp=suite;}
    }
    void postgresCase(String base,String password,Scenario scenario)throws Exception{
        setup();
        String database="kill_authority_"+UUID.randomUUID().toString().replace("-","");
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
            f.base.permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);f.prepare();
            scenario.run();
        }finally{try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    }
    @Test void postgresRuntimeRolesQualifyKillOrderingAndAttemptBindings()throws Exception{
        for(Scenario scenario:List.<Scenario>of(this::terminateCommittedBeforeConsumeRefusesAnAlreadySentGrant,
            this::stopNewDoesNotRevokeRunningEffectsButRefusesAnotherAdmission,this::unrelatedKillEpochDoesNotInvalidateAnExistingExactGrant,
            this::interveningNonKillAuthorityRevisionCannotBeBridged,this::attemptScopeIsRetainedAndCannotBeChangedAfterConsumption,
            this::sqlGuardsIndependentlyRefuseTerminatedEffectConsumption,this::committedConsumptionRetainsLateTargetEvidenceButGrantsNoFurtherEffects))
            postgres(scenario);
    }

}
