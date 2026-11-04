// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Public admission/replay plus real SQL review, original-window and generation guards. */
class TargetResendLifecycleTest {
    @TempDir java.nio.file.Path temp;
    EffectGrantTest fixture;EnterpriseOperationTest rig;
    EnterpriseInvocationRequest original;boolean brokerOnly;
    @BeforeAll static void key()throws Exception{ServicePermitBridgeTest.key();}
    @BeforeEach void setup()throws Exception{
        fixture=new EffectGrantTest();fixture.temp=temp;fixture.reconciliationMode="DOWNSTREAM_IDEMPOTENCY";fixture.brokerOnly=brokerOnly;fixture.setup();
        configure();
    }
    void configure()throws Exception{
        fixture.reviewedTargetContract();rig=fixture.base.rig;
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var before=tx.load();var entries=new HashMap<>(before.entries());var codec=rig.graph.codec;
            entries.put(Ids.Kind.ROLE.id("dedupe-reviewers"),codec.entry(Ids.Kind.ROLE,codec.json(new ControlRole(
                "dedupe-reviewers","Guarantee reviewers",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()))));
            entries.put(Ids.Kind.TEAM.id("dedupe-reviewers"),codec.entry(Ids.Kind.TEAM,codec.json(new ControlTeam(
                "dedupe-reviewers","Guarantee reviewers",true,1L,List.of("admin","alice"),List.of("dedupe-reviewers")))));
            entries.put(Ids.Kind.IDENTITY_BINDING.id("identity-admin"),codec.entry(Ids.Kind.IDENTITY_BINDING,codec.json(
                new ControlIdentityBinding("identity-admin","Administrator identity",true,1L,"admin",
                    "https://identity.example","admin-subject",1L,EnterpriseConformanceTest.NOW-1000,
                    EnterpriseConformanceTest.NOW,1L,"REVIEWED",0L))));
            tx.save(before,new Directory(before.revision()+1,entries));
            var current=new ServerSettingsService(null,codec,ClientCheckInPolicy.QUICKSTART).current(tx);
            tx.saveServerSettings(current.revision()+1,codec.json(new ControlServerSettings(2L,current.revision()+1,
                current.gateway(),current.deviceApproval(),current.clientCheckIn(),new ConfigurationSettings(1L))));
            return null;
        });
        var codec=rig.graph.codec;
        var doc=rig.store.transaction(rig.gateway.tenant(),false,tx->(ObjectNode)codec.value(
            tx.load().entries().get(Ids.Kind.TOOL.id("acme.test/tool")).document()));
        var guarantee=codec.model(codec.json(fixture.profile.path("effectReviews").get(0).path("policy").path("targetDedupeGuarantee")),TargetDedupeGuarantee.class);
        ((ObjectNode)doc.path("definition")).putArray("targetDedupeGuarantees").add(EffectGrantTest.JSON.valueToTree(
            new RouteDedupeConfiguration(fixture.issue.httpRequestManifest().profileDigest(),"write",guarantee)));
        var changes=new ConfigurationChanges(rig.store,codec,rig.clock,512,1048576);
        var maker=new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("maker"),true,true,"admin");
        var reviewer=new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("checker"),true,true,"alice");
        var c=codec.model(changes.mutate(maker,new EnterpriseConfigurationCommand(EnterpriseConfigurationOperation.UPDATE,ControlEntityKind.TOOL,
            "acme.test/tool",doc.toString(),doc.path("revision").asLong()),"target-guarantee-"+fixture.issue.httpRequestManifest().profileDigest().substring(7,23),"request").body(),EnterpriseConfigurationChange.class);
        for(String action:List.of("SUBMIT","APPROVE","APPLY"))
            c=codec.model(changes.transition(action.equals("APPROVE")?reviewer:maker,c.id(),
                codec.json(new EnterpriseConfigurationTransition(c.revision(),EnterpriseConfigurationAction.valueOf(action))),
                UUID.randomUUID().toString(),"request").body(),EnterpriseConfigurationChange.class);
        rig.graph.entries=rig.store.transaction(rig.gateway.tenant(),false,tx->new HashMap<>(tx.load().entries()));
        original=fixture.base.request("target-recovery");
    }
    EnterpriseInvocation submit(){return rig.graph.codec.model(fixture.base.submit(original).body(),EnterpriseInvocation.class);}
    EnterpriseInvocation start(){return consumeQualified(rig.reserve(submit()));}
    EnterpriseInvocation consumeQualified(EnterpriseReservation reservation){
        var i=reservation.invocation();var template=rig.signer.verifyInvocation(fixture.issue.invocationPermit());
        var scope=new RecoveryAdmissionPolicy.Scope("device","pool","sha256:"+"a".repeat(64),template.target().runtimeDigest(),
            "sha256:"+i.evaluation().toolDigest(),fixture.issue.httpRequestManifest().profileDigest(),1L);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveRecoveryScope(i.reservedNonce(),scope);return null;});
        var running=rig.consume(reservation);lease(running);return running;
    }
    void lease(EnterpriseInvocation i){rig.store.transaction(rig.gateway.tenant(),true,tx->{
        tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease(i.id(),i.reservedNonce(),"device",i.expiresAtUnixMs()));return null;});}
    EffectGrantIssue request(EnterpriseInvocation i,String key)throws Exception{
        var codec=rig.graph.codec;
        var claims=(ObjectNode)EffectGrantTest.JSON.readTree(codec.json(rig.signer.verifyInvocation(fixture.issue.invocationPermit())));
        claims.put("invocationId",i.id()).put("nonce",i.reservedNonce()).put("deadlineUnixMs",i.expiresAtUnixMs());
        ((ObjectNode)claims.path("identity")).put("authorizationEpoch",i.authorizationEpoch());
        ((ObjectNode)claims.path("target")).put("toolDescriptorDigest","sha256:"+i.evaluation().toolDigest());
        return new EffectGrantIssue(i.id(),i.reservedNonce(),rig.signer.signInvocation(codec.model(claims.toString(),InvocationPermitClaims.class)),
            "broker-"+i.reservedNonce(),"handler",fixture.issue.arguments(),fixture.issue.httpRequestDigest(),fixture.issue.httpRequestManifest(),key);
    }
    EffectGrant grant(EnterpriseInvocation i,String key)throws Exception{
        return rig.graph.codec.model(fixture.grants.issue(rig.gateway.tenant(),tx->"device",rig.graph.codec.json(request(i,key))).body(),EffectGrant.class);
    }
    void send(EnterpriseInvocation i,EffectGrant grant){
        assertEquals(200,fixture.grants.sending(rig.gateway.tenant(),tx->"device",rig.graph.codec.json(
            new EffectGrantDispatch(grant.grant(),"broker-"+i.reservedNonce(),"handler"))).status());
    }
    EnterpriseInvocation unknown(EnterpriseInvocation i){
        var changed=new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),EnterpriseInvocationState.OUTCOME_UNKNOWN,
            i.revision()+1,i.authorizationEpoch(),i.reservedNonce(),i.expiresAtUnixMs(),List.of(),i.downstreamIdempotencyKey(),null,i.diagnosticId(),i.reconciliation(), null);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveInvocation(changed,rig.clock.millis(),i.revision());return null;});return changed;
    }
    @Test void publicReplayCreatesANewAttemptAndGenerationWithTheSameTargetKeyAndNoExtraEffects()throws Exception{
        var first=start();var firstGrant=grant(first,"charge");send(first,firstGrant);unknown(first);
        var firstClaims=rig.signer.verifyEffect(firstGrant.grant());
        assertNotNull(rig.store.transaction(rig.gateway.tenant(),false,tx->TargetResends.plan(tx,rig.graph.codec,tx.enterprise().invocation(first.id()),rig.clock.millis())));
        var queued=submit();assertEquals(EnterpriseInvocationState.QUEUED,queued.state());
        assertEquals(first.expiresAtUnixMs(),queued.expiresAtUnixMs());
        var replacement=consumeQualified(rig.reserve(queued));
        assertNotEquals(first.reservedNonce(),replacement.reservedNonce());
        var secondGrant=grant(replacement,"charge");var secondClaims=rig.signer.verifyEffect(secondGrant.grant());
        assertEquals(firstClaims.effectId(),secondClaims.effectId());assertEquals(firstClaims.generation()+1,secondClaims.generation());
        assertEquals(firstGrant.downstreamKey(),secondGrant.downstreamKey());assertNotEquals(firstClaims.authNonce(),secondClaims.authNonce());
        assertThrows(Failure.class,()->grant(replacement,"extra-charge"));
        assertThrows(Failure.class,()->send(first,firstGrant));
        send(replacement,secondGrant);
        var binding=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().targetBinding(firstClaims.effectId()));
        assertEquals(EnterpriseConformanceTest.NOW,binding.firstAuthorizedAt());
        var history=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(first.id(),64));
        assertEquals("TARGET_DEDUPLICATION",history.getFirst().proof());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,1000)).contains("RECONCILIATION_RESEND"));
    }
    @Test void unqualifiedConsumptionRemainsUnknownDespiteAnApprovedTargetContract()throws Exception{
        var i=rig.consume(rig.reserve(submit()));lease(i);var granted=grant(i,"charge");send(i,granted);unknown(i);
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,submit().state());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(i.id(),64)).isEmpty());
    }
    @Test void revokingTheIndependentReviewerBlocksAnAlreadyIssuedReplacementSend()throws Exception{
        var first=start();var firstGrant=grant(first,"charge");send(first,firstGrant);unknown(first);
        var queued=submit();var replacement=consumeQualified(rig.reserve(queued));
        var second=grant(replacement,"charge");
        revokeReviewer();
        assertThrows(Failure.class,()->send(replacement,second));
        var claim=rig.signer.verifyEffect(second.grant());
        assertEquals("AUTHORIZED",rig.store.transaction(rig.gateway.tenant(),false,
            tx->tx.effects().byNonce(claim.authNonce()).state()));
    }
    void revokeReviewer(){
        rig.graph.entries.put(Ids.Kind.TEAM.id("dedupe-reviewers"),rig.graph.codec.entry(Ids.Kind.TEAM,
            rig.graph.codec.json(new ControlTeam("dedupe-reviewers","Guarantee reviewers",true,2L,
                List.of("admin"),List.of("dedupe-reviewers")))));
        rig.saveGraph();
    }
    @Test void revokingTheIndependentReviewerPreventsPublicReplay()throws Exception{
        var first=start();var firstGrant=grant(first,"charge");send(first,firstGrant);unknown(first);
        revokeReviewer();
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->
            TargetResends.plan(tx,rig.graph.codec,tx.enterprise().invocation(first.id()),rig.clock.millis())));
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,submit().state());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(first.id(),64)).isEmpty());
    }
    @Test void theOriginalWindowCannotBeExtendedByAnotherReplay()throws Exception{
        var first=start();var firstGrant=grant(first,"charge");send(first,firstGrant);unknown(first);
        var claim=rig.signer.verifyEffect(firstGrant.grant());
        var binding=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().targetBinding(claim.effectId()));
        assertNotNull(rig.store.transaction(rig.gateway.tenant(),false,tx->
            TargetResends.plan(tx,rig.graph.codec,tx.enterprise().invocation(first.id()),binding.validUntil()-1)));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->
            TargetResends.plan(tx,rig.graph.codec,tx.enterprise().invocation(first.id()),binding.validUntil())));
        assertEquals(binding,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().targetBinding(claim.effectId())));
    }
    @Test void concurrentPublicRetriesCreateOneImmutableRecovery()throws Exception{
        var first=start();var firstGrant=grant(first,"charge");send(first,firstGrant);unknown(first);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);var begin=new java.util.concurrent.CountDownLatch(1);
        try{
            var calls=new ArrayList<java.util.concurrent.Future<EnterpriseInvocation>>();
            for(int n=0;n<2;n++)calls.add(pool.submit(()->{
                ready.countDown();assertTrue(begin.await(5,java.util.concurrent.TimeUnit.SECONDS));return submit();
            }));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));begin.countDown();
            for(var call:calls){var queued=call.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(first.id(),queued.id());assertEquals(EnterpriseInvocationState.QUEUED,queued.state());}
        }finally{pool.shutdownNow();}
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(first.id(),64)).size());
    }
    void usePostgres()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL");
        String database="target_resends_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){
            q.execute("CREATE DATABASE "+database);
        }
        String url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        rig.keyPolicySource=source;
        rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(
            io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
        EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
        rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        fixture.base.permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
        fixture.prepare();configure();
    }
    @Test void actualPostgresRuntimeRolesEnforceReviewedRecoveryAndKeyStability()throws Exception{
        usePostgres();publicReplayCreatesANewAttemptAndGenerationWithTheSameTargetKeyAndNoExtraEffects();
    }

}
