// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.RsaFleetCrypto;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual current-directory review and commit effects; no reviewer identity comes from JSON. */
class KillSwitchTest {
    @TempDir Path temp;EffectGrantTest f;EnterpriseOperationTest rig;KillSwitch kills;KillCrypto crypto;
    @BeforeAll static void keys()throws Exception{KillAuthorityTest.keys();}
    @BeforeEach void setup()throws Exception{
        f=new EffectGrantTest();f.temp=temp;f.fencedTarget=new FencedTarget("billing",FencedTargetClass.SHARED_SQL);
        f.configure=value->{
            var graph=value.base.rig.graph;
            for(String user:List.of("checker-one","checker-two"))graph.add(Ids.Kind.USER,new ControlUser(user,user,true,1L,null,false,Map.of()));
            var rules=new ArrayList<EnterpriseManagementRule>();
            for(var type:EnterpriseGroupType.values())rules.add(new EnterpriseManagementRule(List.of("kill.read","kill.create","kill.approve","kill.recover","package.read"),
                type,new GroupSelection(List.of(),true),List.of(),graph.conditions(),null));
            graph.add(Ids.Kind.ROLE,new ControlRole("kill-operators","Kill operators",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));
            graph.add(Ids.Kind.TEAM,new ControlTeam("kill-reviewers","Kill reviewers",true,1L,List.of("admin","checker-one","checker-two"),List.of("kill-operators"),null));
            value.base.rig.saveGraph();
            value.base.rig.store.transaction(value.base.rig.gateway.tenant(),true,tx->{tx.packages().saveToolHost("device",registration(value.base.rig.graph.codec));return null;});
        };
        f.setup();rig=f.base.rig;
        crypto=new RsaFleetCrypto(rig.graph.codec,(RSAPrivateCrtKey)KillAuthorityTest.organization.getPrivate(),"organization",
            List.of(KillAuthorityTest.trust("release",KillAuthorityTest.release)),List.of(KillAuthorityTest.trust("organization",KillAuthorityTest.organization)),List.of());
        kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
    }
    static String registration(Codec codec){
        return codec.json(codec.model(codec.json(Map.of("hostId","device","poolId","default-devices","version","1.0.0",
            "protocol",Map.of("min",2,"max",2),"launcherVersion","1.0.0","tiers",List.of("OCI_SANDBOX"))),ToolHostRegistration.class));
    }
    DirectoryService.Actor actor(String user){return new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("kill-user:"+user),true,true,user);}
    String json(Object value){return rig.graph.codec.json(value);}
    KillProposal proposal(String kind,Object request){return rig.graph.codec.model(kills.propose(actor("admin"),kind,json(request),"propose-"+UUID.randomUUID()).body(),KillProposal.class);}
    KillProposal current(String id){return rig.graph.codec.model(kills.read(actor("admin"),id).body(),KillProposal.class);}
    KillProposal review(KillProposal p,String user,boolean approve){return rig.graph.codec.model(kills.decide(actor(user),p.id(),json(new KillProposalDecision(p.revision(),approve)),"review-"+UUID.randomUUID()).body(),KillProposal.class);}
    KillProposal terminate(){return proposal("KILL",new KillRequest(new KillScope(KillScopeKind.TENANT,null),KillMode.TERMINATE,"Emergency"));}
    long epoch(){return rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().authorizationEpoch());}
    EnterpriseInvocation invocation(){return rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(f.invocation.id()));}
    @Test void twoIndependentCurrentReviewsCommitKillEpochAndCancellationTogether(){
        long before=epoch();var p=terminate();assertEquals(KillProposalState.PENDING,p.state());assertEquals(before,epoch());
        assertThrows(Failure.class,()->review(p,"admin",true));
        var first=review(p,"checker-one",true);assertEquals(1,first.reviews().size());assertEquals(before,epoch());
        assertThrows(Failure.class,()->review(first,"checker-one",true));
        var applied=review(first,"checker-two",true);assertEquals(KillProposalState.APPLIED,applied.state());assertEquals(before+1,epoch());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,invocation().state());
        var event=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().byId(applied.eventId()));
        var signed=(com.fasterxml.jackson.databind.node.ObjectNode)rig.graph.codec.value(event.document());String signature=signed.remove("signature").asText();
        assertEquals(signed,crypto.verifyEvent(signature));assertEquals(rig.gateway.tenant().value(),signed.path("tenantId").asText());
        assertTrue(rig.store.<Boolean>transaction(rig.gateway.tenant(),false,tx->tx.enterprise().operationCancelled(f.invocation.id())));
        assertThrows(Failure.class,f::issue);
    }
    @Test void recoveryNeedsTwoOtherHumansAndCannotRestartCancelledWork()throws Exception{
        var kill=review(review(terminate(),"checker-one",true),"checker-two",true);
        var recovery=proposal("RECOVERY",new RecoveryRequest(List.of(kill.eventId()),"Verified recovery"));
        assertThrows(Failure.class,()->review(recovery,"admin",true));
        var one=review(recovery,"checker-one",true);assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().effective()).size());
        var applied=review(one,"checker-two",true);assertEquals(KillProposalState.APPLIED,applied.state());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().effective()).isEmpty());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,invocation().state());
        assertThrows(Failure.class,f::issue);
        assertEquals(EnterpriseInvocationState.EXECUTING,f.base.start("fresh-after-recovery").state());
    }
    @Test void rejectionAndExpiryNeverCreateAuthority(){
        long before=epoch();var p=terminate();var denied=review(p,"checker-one",false);
        assertEquals(KillProposalState.REJECTED,denied.state());assertEquals(before,epoch());
        assertThrows(Failure.class,()->review(denied,"checker-two",true));
        var stale=terminate();rig.advanceKeyClock(600001);
        kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
        assertEquals(KillProposalState.EXPIRED,current(stale.id()).state());
        assertThrows(Failure.class,()->review(stale,"checker-one",true));assertEquals(before,epoch());
    }
    @Test void storedReviewCannotBeForgedByTheMakerOrAppliedWithOneVote(){
        var p=terminate();
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.kills().review(p.id(),new KillReview("admin",true,EnterpriseConformanceTest.NOW));return null;}));
        var one=review(p,"checker-one",true);
        var other=review(review(terminate(),"checker-one",true),"checker-two",true);
        // A real matching-kind event exists: refusal must come from the independent-vote guard.
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.kills().close(p.id(),new KillStore.Closure("APPLIED",other.eventId(),EnterpriseConformanceTest.NOW));return null;}));
        assertEquals(KillProposalState.PENDING,current(one.id()).state());
    }
    @Test void replaysDoNotAllocateAnotherEventAndStaleProposalsCannotApply(){
        var p=review(terminate(),"checker-one",true);String key="last-review",document=json(new KillProposalDecision(p.revision(),true));
        var first=kills.decide(actor("checker-two"),p.id(),document,key);
        assertEquals(first,kills.decide(actor("checker-two"),p.id(),document,key));
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().effective()).size());
        var stale=terminate();
        rig.store.transaction(rig.gateway.tenant(),true,tx->{var d=tx.load();tx.save(d,new Directory(d.revision()+1,d.entries()));return null;});
        assertThrows(Failure.class,()->review(stale,"checker-one",true));
    }
    @Test void noServiceOrForeignTenantCanProposeAndInvalidRecoveriesAreRefused(){
        var request=new KillRequest(new KillScope(KillScopeKind.TENANT,null),KillMode.TERMINATE,"Emergency");
        assertThrows(Failure.class,()->kills.propose(rig.gateway,"KILL",json(request),"service-denied"));
        var other=new DirectoryService.Actor(io.ololabs.toolgate.control.fixtures.Tenants.id("other"),DirectoryService.digest("other-user"),true,true,"admin");
        assertThrows(Failure.class,()->kills.propose(other,"KILL",json(request),"tenant-denied"));
        assertThrows(Failure.class,()->proposal("RECOVERY",new RecoveryRequest(List.of("control:999"),"No such effective kill")));
        assertThrows(Failure.class,()->proposal("KILL",new KillRequest(new KillScope(KillScopeKind.TENANT,"extra"),KillMode.TERMINATE,"Ambiguous scope")));
    }

    EndpointDeviceRecord peer(Store.Session tx){return rig.graph.codec.model(tx.endpoint("device").document(),EndpointDeviceRecord.class);}
    @Test void signedLongPollWaitsOutsideTheTenantTransactionAndAcknowledgesOnlyItsHost()throws Exception{
        var initial=rig.graph.codec.model(kills.hostState(rig.gateway.tenant(),this::peer).body(),KillPush.class);
        var ready=new java.util.concurrent.CountDownLatch(1);var reads=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var pending=executor.submit(()->kills.poll(rig.gateway.tenant(),tx->{var device=peer(tx);if(reads.incrementAndGet()>=2)ready.countDown();return device;},initial.authorizationEpoch(),3000));
            assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var proposal=review(terminate(),"checker-one",true);var applied=review(proposal,"checker-two",true);
            var observed=rig.graph.codec.model(pending.get(5,java.util.concurrent.TimeUnit.SECONDS).body(),KillPush.class);
            assertEquals(epoch(),observed.authorizationEpoch());assertEquals(applied.eventId(),observed.kills().getFirst().eventId());
            var unsigned=(com.fasterxml.jackson.databind.node.ObjectNode)rig.graph.codec.value(json(observed));
            String signature=unsigned.remove("signature").asText();assertEquals(unsigned,crypto.verifyPush(signature));
            var ack=new KillAcknowledgement(observed.authorizationEpoch(),KillDeliveryPath.PUSH);
            assertEquals(200,kills.acknowledge(rig.gateway.tenant(),this::peer,json(ack)).status());
            var record=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().acknowledgements()).getFirst();
            assertEquals("device",record.component());assertEquals("TOOL_HOST",record.kind());
            assertThrows(Failure.class,()->kills.acknowledge(rig.gateway.tenant(),this::peer,json(new KillAcknowledgement(epoch()+1,KillDeliveryPath.PUSH))));
            assertThrows(Failure.class,()->kills.acknowledge(rig.gateway.tenant(),this::peer,json(new KillAcknowledgement(0L,KillDeliveryPath.POLL))));
            assertThrows(Failure.class,()->kills.acknowledge(rig.gateway.tenant(),this::peer,json(new KillAcknowledgement(epoch(),KillDeliveryPath.CHECK_IN))));
            assertThrows(Failure.class,()->kills.acknowledge(rig.gateway.tenant(),this::peer,"{\"authorizationEpoch\":0,\"deliveryPath\":\"POLL\",\"component\":\"other\"}"));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void pollIdentityFloorConcurrencyAndCancellationCannotGrantOrRetainAuthority()throws Exception{
        long epoch=epoch();var ready=new java.util.concurrent.CountDownLatch(1);var released=new java.util.concurrent.CountDownLatch(1);
        var reads=new java.util.concurrent.atomic.AtomicInteger();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var pending=executor.submit(()->{try{return kills.poll(rig.gateway.tenant(),tx->{var value=peer(tx);if(reads.incrementAndGet()>=2)ready.countDown();return value;},epoch,20000);}
                catch(Failure expected){interrupted.set(Thread.currentThread().isInterrupted());throw expected;}finally{released.countDown();}});
            assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(Failure.class,()->kills.poll(rig.gateway.tenant(),this::peer,epoch,0));
            pending.cancel(true);assertTrue(released.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(interrupted.get());
            assertEquals(200,kills.poll(rig.gateway.tenant(),this::peer,epoch,0).status());
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        assertThrows(Failure.class,()->kills.poll(rig.gateway.tenant(),this::peer,epoch+1,0));
        assertThrows(Failure.class,()->kills.poll(rig.gateway.tenant(),this::peer,-1,0));
        assertThrows(Failure.class,()->kills.poll(rig.gateway.tenant(),this::peer,epoch,20001));
        assertThrows(Failure.class,()->kills.hostState(rig.gateway.tenant(),tx->null));
        var acceptedPeer=rig.store.transaction(rig.gateway.tenant(),false,this::peer);
        assertThrows(Failure.class,()->kills.hostState(io.ololabs.toolgate.control.fixtures.Tenants.id("other"),tx->acceptedPeer));
    }
    @FunctionalInterface interface Scenario {void run()throws Exception;}
    void postgres(Scenario scenario)throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires owned PostgreSQL");
        Path suiteRoot=temp;
        try{
        temp=java.nio.file.Files.createDirectory(suiteRoot.resolve("case-"+UUID.randomUUID()));setup();
        String database="kill_reviews_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("CREATE DATABASE "+database);}
        try{
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            rig.keyPolicySource=source;
            rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
            EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.packages().saveToolHost("device",registration(rig.graph.codec));return null;});
            rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
            f.base.permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);f.prepare();
            kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
            scenario.run();
        }finally{KillStorageTest.dropDatabase(base,password,database);}
        }finally{temp=suiteRoot;}
    }
    @Test void postgresRuntimeRolesQualifyIndependentReviewRecoveryAndCancellation()throws Exception{
        for(Scenario scenario:List.<Scenario>of(this::twoIndependentCurrentReviewsCommitKillEpochAndCancellationTogether,
                this::recoveryNeedsTwoOtherHumansAndCannotRestartCancelledWork,this::rejectionAndExpiryNeverCreateAuthority,
                this::storedReviewCannotBeForgedByTheMakerOrAppliedWithOneVote,
                this::replaysDoNotAllocateAnotherEventAndStaleProposalsCannotApply,
                this::noServiceOrForeignTenantCanProposeAndInvalidRecoveriesAreRefused,
                this::signedLongPollWaitsOutsideTheTenantTransactionAndAcknowledgesOnlyItsHost,
                this::pollIdentityFloorConcurrencyAndCancellationCannotGrantOrRetainAuthority,
                this::operatorEmergencyIsGloballySingleUseAndNormalRecoveryStillNeedsTwoHumans,
                this::invalidExpiredAndReplayedOperatorRequestsCannotChangeEpoch,
                this::nativeDeliveryAndAckUseOnlyTheAcceptedUnregisteredExecutor,
                this::gatewaySignedDeliveryRegistersOnlyItsVerifiedTenantPrincipal,
                this::archiveWaitsForEveryRegisteredConsumerOrThirtyDaysAndNeverDeletesEvents,
                this::gatewayCatalogHidesKilledToolsAndRecoveryReturnsOnlyDiscoveryAuthority,
                this::packageSuspensionTracksEveryEffectiveKillAndRestoresTheImmutablePriorState,
                this::stopNewCancelsQueuedAndReservedAttemptsWithoutRestartingThemOnRecovery))postgres(scenario);
    }

    private static final List<java.security.KeyPair> OPERATORS=new ArrayList<>();
    static synchronized List<java.security.KeyPair> operators()throws Exception{
        if(OPERATORS.isEmpty()){var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
            for(int n=0;n<3;n++)OPERATORS.add(generator.generateKeyPair());}
        return OPERATORS;
    }
    OperatorApprovals operatorVerifier()throws Exception{
        var keys=new ArrayList<KillTrustKey>();int n=0;
        for(var pair:operators()){
            var key=KillAuthorityTest.trust("operator-"+n++,pair);
            keys.add(new KillTrustKey(key.kid(),key.n(),key.e()));
        }
        return new io.ololabs.toolgate.control.adapter.RsaOperatorApprovals(rig.graph.codec,keys,List.of(
            ((java.security.interfaces.RSAPublicKey)KillAuthorityTest.organization.getPublic()).getModulus(),
            ((java.security.interfaces.RSAPublicKey)KillAuthorityTest.release.getPublic()).getModulus()));
    }
    BreakGlassRequest emergency(String requestId,long issued,long expiry)throws Exception{
        var scope=new KillScope(KillScopeKind.TENANT,null);String reason="Emergency incident";
        var claims=new BreakGlassApprovalClaims(rig.gateway.tenant().value(),requestId,scope,KillMode.TERMINATE,reason,issued,expiry);
        var tokens=new ArrayList<String>();var encoder=Base64.getUrlEncoder().withoutPadding();int n=0;
        for(var pair:operators().subList(0,2)){
            var header=(com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value(json(Map.of("alg","RS256","typ","toolgate-breakglass-approval+jws","kid","operator-"+n++)));
            var payload=(com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value(json(claims));
            String message=encoder.encodeToString(Canonical.jcs(header))+"."+encoder.encodeToString(Canonical.jcs(payload));
            var signature=java.security.Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());
            signature.update(message.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            tokens.add(message+"."+encoder.encodeToString(signature.sign()));
        }
        return new BreakGlassRequest(scope,KillMode.TERMINATE,reason,tokens);
    }
    @Test void operatorEmergencyIsGloballySingleUseAndNormalRecoveryStillNeedsTwoHumans()throws Exception{
        long before=epoch(),now=rig.clock.millis();var verifier=operatorVerifier();
        var request=emergency("operator-incident",now,now+600000);
        var response=kills.breakGlass(rig.gateway.tenant(),"a".repeat(64),"operator-incident",json(request),verifier);
        assertEquals(201,response.status());assertEquals(before+1,epoch());
        var event=rig.graph.codec.model(response.body(),KillEvent.class);
        assertEquals(KillIssuer.control_breakglass,event.issuer());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,invocation().state());
        var retained=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().emergency("operator-incident"));
        assertEquals(event.eventId(),retained.event());assertEquals("a".repeat(64),retained.certificate());
        assertThrows(Failure.class,()->kills.breakGlass(rig.gateway.tenant(),"b".repeat(64),"operator-incident",json(request),verifier));
        assertEquals(before+1,epoch());
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.kills().recordEmergency(new KillStore.EmergencyRequest("other",retained.digest(),retained.event(),retained.certificate(),retained.notAfter(),retained.at()));return null;}));
        var recovery=proposal("RECOVERY",new RecoveryRequest(List.of(event.eventId()),"Reviewed resolution"));
        assertThrows(Failure.class,()->review(recovery,"admin",true));
        var first=review(recovery,"checker-one",true);assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().effective()).size());
        review(first,"checker-two",true);assertEquals(before+2,epoch());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().effective()).isEmpty());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,invocation().state());
    }
    @Test void invalidExpiredAndReplayedOperatorRequestsCannotChangeEpoch()throws Exception{
        long before=epoch(),now=rig.clock.millis();var verifier=operatorVerifier();
        var expired=emergency("expired",now-600000,now);
        assertThrows(Failure.class,()->kills.breakGlass(rig.gateway.tenant(),"a".repeat(64),"expired",json(expired),verifier));
        var valid=emergency("bound-id",now,now+600000);
        assertThrows(Failure.class,()->kills.breakGlass(rig.gateway.tenant(),"a".repeat(64),"wrong-id",json(valid),verifier));
        var one=new BreakGlassRequest(valid.scope(),valid.mode(),valid.reason(),valid.operatorSignatures().subList(0,1));
        assertThrows(Failure.class,()->kills.breakGlass(rig.gateway.tenant(),"a".repeat(64),"bound-id",json(one),verifier));
        assertEquals(before,epoch());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().emergency("bound-id")));
        assertEquals(EnterpriseInvocationState.EXECUTING,invocation().state());
    }

    @Test void separateOperatorMtlsListenerCommitsOnlyAcceptedOfflineQuorum()throws Exception{
        long before=epoch(),now=rig.clock.millis();
        io.ololabs.toolgate.control.adapter.KillOperatorTlsFixture.qualify(temp,rig.gateway.tenant(),
            rig.graph.codec,kills,operatorVerifier(),json(emergency("tls-incident",now,now+600000)),"tls-incident");
        assertEquals(before+1,epoch());assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,invocation().state());
    }

    @Test void nativeDeliveryAndAckUseOnlyTheAcceptedUnregisteredExecutor(){
        EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"native-device");
        java.util.function.Function<Store.Session,EndpointDeviceRecord> nativePeer=tx->
            rig.graph.codec.model(tx.endpoint("native-device").document(),EndpointDeviceRecord.class);
        assertThrows(Failure.class,()->kills.nativeKeys(rig.gateway.tenant(),this::peer));
        assertThrows(Failure.class,()->kills.hostKeys(rig.gateway.tenant(),nativePeer));
        var keys=rig.graph.codec.model(kills.nativeKeys(rig.gateway.tenant(),nativePeer).body(),KillTrustDocument.class);
        assertEquals(rig.gateway.tenant().value(),keys.tenantId());assertEquals(crypto.publicKeys(),keys.keys());
        var push=rig.graph.codec.model(kills.nativeState(rig.gateway.tenant(),nativePeer).body(),KillPush.class);
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)rig.graph.codec.value(json(push));String signature=value.remove("signature").asText();
        assertEquals(value,crypto.verifyPush(signature));
        var request=new KillAcknowledgement(epoch(),KillDeliveryPath.CHECK_IN);
        kills.nativeAcknowledge(rig.gateway.tenant(),nativePeer,json(request));
        var acks=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().acknowledgements());
        assertTrue(acks.stream().anyMatch(ack->ack.kind().equals("ENDPOINT")&&ack.component().equals("native-device")&&ack.epoch()==epoch()));
        assertThrows(Failure.class,()->kills.nativeAcknowledge(rig.gateway.tenant(),nativePeer,json(new KillAcknowledgement(epoch()+1,KillDeliveryPath.CHECK_IN))));
        assertThrows(Failure.class,()->kills.nativeAcknowledge(rig.gateway.tenant(),nativePeer,json(new KillAcknowledgement(epoch(),KillDeliveryPath.POLL))));
        assertThrows(Failure.class,()->kills.nativeAcknowledge(rig.gateway.tenant(),this::peer,json(request)));
    }

    @Test void gatewayCatalogHidesKilledToolsAndRecoveryReturnsOnlyDiscoveryAuthority(){
        var tool=rig.graph.codec.model(rig.graph.entries.get(Ids.Kind.TOOL.id("acme.test/tool")).document(),ControlTool.class);
        var hint=new LocalToolInfo("acme.test/tool","memory.append","Current tool",true,tool.definition().inputSchema(),
            new EnterpriseEvaluator(rig.graph.codec).toolDigest(rig.graph.directory(),tool),tool.packageDigest(),null);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.saveEndpointConfiguration(new Store.EndpointConfiguration("device",tx.load().revision(),"{}",null,
                json(new LocalToolCatalog(List.of(hint)))));return null;
        });
        var endpoints=new EndpointService(rig.store,rig.graph.codec,null,rig.clock,true,rig.gateway.tenant().value(),
            "server","Organization","https://control.example.test","https://gateway.example.test");
        var service=new McpService(rig.store,rig.graph.codec,endpoints,rig.operations,rig.clock,rig.gateway.tenant(),"server");
        String context=json(f.base.request("kill-catalog").context());
        java.util.function.Supplier<Store.Reply> catalog=()->service.catalog(rig.gateway,true,context);
        var before=catalog.get();assertEquals(epoch(),before.revision());
        assertEquals(1,rig.graph.codec.model(before.body(),LocalToolCatalog.class).tools().size());
        assertTrue(overview().components().stream().anyMatch(c->c.kind()==KillComponentKind.GATEWAY&&c.componentId().equals(rig.gateway.id())));
        var kill=apply(proposal("KILL",new KillRequest(new KillScope(KillScopeKind.TOOL,"acme.test/tool"),KillMode.STOP_NEW,"Withdraw discovery")));
        var hidden=catalog.get();assertEquals(epoch(),hidden.revision());assertTrue(hidden.revision()>before.revision());
        assertTrue(rig.graph.codec.model(hidden.body(),LocalToolCatalog.class).tools().isEmpty());
        assertEquals(EnterpriseInvocationState.EXECUTING,invocation().state());
        apply(proposal("RECOVERY",new RecoveryRequest(List.of(kill.eventId()),"Approved discovery recovery")));
        assertEquals(1,rig.graph.codec.model(catalog.get().body(),LocalToolCatalog.class).tools().size());
        assertEquals(EnterpriseInvocationState.EXECUTING,invocation().state());
    }

    @Test void stopNewCancelsQueuedAndReservedAttemptsWithoutRestartingThemOnRecovery(){
        var queued=rig.graph.codec.model(f.base.submit(f.base.request("queued-before-kill")).body(),EnterpriseInvocation.class);
        var toReserve=rig.graph.codec.model(f.base.submit(f.base.request("reserved-before-kill")).body(),EnterpriseInvocation.class);
        var reserved=rig.reserve(toReserve);
        var applied=apply(proposal("KILL",new KillRequest(new KillScope(KillScopeKind.TOOL,"acme.test/tool"),
            KillMode.STOP_NEW,"Withdraw queued authority")));
        for(String id:List.of(queued.id(),reserved.invocation().id()))
            assertEquals(EnterpriseInvocationState.CANCELLED,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(id)).state());
        assertEquals(EnterpriseInvocationState.EXECUTING,invocation().state());
        assertThrows(Failure.class,()->rig.operations.consume(rig.gateway.tenant(),"device",rig.consumption(reserved)));
        apply(proposal("RECOVERY",new RecoveryRequest(List.of(applied.eventId()),"Verified recovery")));
        for(String id:List.of(queued.id(),reserved.invocation().id()))
            assertEquals(EnterpriseInvocationState.CANCELLED,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(id)).state());
        assertThrows(Failure.class,()->rig.operations.consume(rig.gateway.tenant(),"device",rig.consumption(reserved)));
        assertEquals(EnterpriseInvocationState.EXECUTING,f.base.start("fresh-after-stop-new-recovery").state());
    }

    @Test void packageSuspensionTracksEveryEffectiveKillAndRestoresTheImmutablePriorState()throws Exception{
        var packages=new ServerPackages(rig.store,rig.graph.codec,rig.operations,rig.clock);
        String digest="sha256:"+"c".repeat(64),packageId="acme.suspended";
        var uploaded=new PackageVersion(packageId,"1.0.0",digest,"sha256:"+"d".repeat(64),
            PackageVersionState.VERIFIED,1L,rig.clock.millis(),null);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.packages().saveVersion(new PackageStore.Version(digest,packageId,"1.0.0",rig.clock.millis(),json(uploaded)),new byte[0]);
            return null;
        });
        java.util.function.Supplier<String> state=()->{
            var page=(com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value(packages.versions(actor("admin")).body());
            for(var item:page.path("items"))if(item.path("packageDigest").asText().equals(digest))return item.path("state").asText();
            throw new AssertionError("Missing uploaded version");
        };
        var one=apply(proposal("KILL",new KillRequest(new KillScope(KillScopeKind.PACKAGE,packageId),KillMode.STOP_NEW,"Suspend package")));
        assertEquals("SUSPENDED",state.get());
        var two=apply(proposal("KILL",new KillRequest(new KillScope(KillScopeKind.PACKAGE_DIGEST,digest),KillMode.TERMINATE,"Suspend exact version")));
        apply(proposal("RECOVERY",new RecoveryRequest(List.of(one.eventId()),"Resolve package incident")));
        assertEquals("SUSPENDED",state.get());
        var stored=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().version(digest).document());
        assertEquals("VERIFIED",((com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value(stored)).path("state").asText());
        apply(proposal("RECOVERY",new RecoveryRequest(List.of(two.eventId()),"Resolve exact-version incident")));
        assertEquals("VERIFIED",state.get());
        assertEquals(stored,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().version(digest).document()));
    }

    @Test void gatewaySignedDeliveryRegistersOnlyItsVerifiedTenantPrincipal(){
        assertThrows(Failure.class,()->kills.gatewayKeys(actor("admin")));
        assertThrows(Failure.class,()->kills.gatewayState(actor("checker-one")));
        var event=apply(terminate());
        var trust=rig.graph.codec.model(kills.gatewayKeys(rig.gateway).body(),KillTrustDocument.class);
        assertEquals(rig.gateway.tenant().value(),trust.tenantId());
        assertEquals(epoch(),trust.authorizationEpoch());
        assertFalse(trust.keys().isEmpty());
        var state=rig.graph.codec.model(kills.gatewayState(rig.gateway).body(),KillPush.class);
        assertEquals(rig.gateway.tenant().value(),state.tenantId());
        assertEquals(List.of(event.eventId()),state.kills().stream().map(KillEvent::eventId).toList());
        var signed=(com.fasterxml.jackson.databind.node.ObjectNode)rig.graph.codec.value(json(state));
        String signature=signed.remove("signature").asText();
        assertEquals(signed,crypto.verifyPush(signature));
        var components=overview().components().stream().filter(c->c.kind()==KillComponentKind.GATEWAY).toList();
        assertEquals(1,components.size());
        assertEquals(rig.gateway.id(),components.getFirst().componentId());
        assertNull(components.getFirst().authorizationEpoch());
        kills.gatewayAcknowledge(rig.gateway,json(new KillAcknowledgement(epoch(),KillDeliveryPath.POLL)));
        assertEquals(epoch(),overview().components().stream().filter(c->c.kind()==KillComponentKind.GATEWAY)
            .findFirst().orElseThrow().authorizationEpoch());
    }

    KillOverview overview(){return rig.graph.codec.model(kills.overview(actor("admin")).body(),KillOverview.class);}
    KillProposal apply(KillProposal proposal){return review(review(proposal,"checker-one",true),"checker-two",true);}
    @Test void archiveWaitsForEveryRegisteredConsumerOrThirtyDaysAndNeverDeletesEvents(){
        EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"native-device");
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.kills().registerGateway(rig.gateway.id(),rig.clock.millis());return null;});
        var kill=apply(terminate());var recovery=apply(proposal("RECOVERY",new RecoveryRequest(List.of(kill.eventId()),"Resolution")));
        long recovered=epoch();var before=overview();
        assertEquals(3,before.components().size());assertEquals(2L,before.retainedEventCount());assertEquals(0L,before.archivedEventCount());
        kills.acknowledge(rig.gateway.tenant(),this::peer,json(new KillAcknowledgement(recovered,KillDeliveryPath.POLL)));
        assertEquals(0L,overview().archivedEventCount());
        java.util.function.Function<Store.Session,EndpointDeviceRecord> nativePeer=tx->rig.graph.codec.model(tx.endpoint("native-device").document(),EndpointDeviceRecord.class);
        kills.nativeAcknowledge(rig.gateway.tenant(),nativePeer,json(new KillAcknowledgement(recovered,KillDeliveryPath.CHECK_IN)));
        assertEquals(0L,overview().archivedEventCount());
        String acknowledgement=json(new KillAcknowledgement(recovered,KillDeliveryPath.POLL));
        assertThrows(Failure.class,()->kills.gatewayAcknowledge(actor("admin"),acknowledgement));
        var unregistered=new DirectoryService.Actor(rig.gateway.tenant(),"b".repeat(64),false);
        assertThrows(Failure.class,()->kills.gatewayAcknowledge(unregistered,acknowledgement));
        kills.gatewayAcknowledge(rig.gateway,acknowledgement);
        var all=overview();assertEquals(2L,all.retainedEventCount());assertEquals(2L,all.archivedEventCount());
        assertTrue(all.state().kills().isEmpty()&&all.state().recoveries().isEmpty());assertEquals(recovered,epoch());
        assertNotNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.kills().byId(recovery.eventId())));
        var next=apply(terminate());apply(proposal("RECOVERY",new RecoveryRequest(List.of(next.eventId()),"Unreachable consumer")));
        assertEquals(2L,overview().archivedEventCount());
        rig.advanceKeyClock(30L*24*60*60*1000-1);kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
        kills.sweep(rig.gateway.tenant());assertEquals(2L,overview().archivedEventCount());
        rig.advanceKeyClock(1);kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
        kills.sweep(rig.gateway.tenant());assertEquals(4L,overview().archivedEventCount());
        var active=apply(terminate());
        rig.advanceKeyClock(60L*24*60*60*1000);kills=new KillSwitch(rig.store,rig.graph.codec,rig.operations,()->crypto);
        kills.sweep(rig.gateway.tenant());
        var retained=overview();assertEquals(5L,retained.retainedEventCount());assertEquals(4L,retained.archivedEventCount());
        assertEquals(List.of(active.eventId()),retained.state().kills().stream().map(KillEvent::eventId).toList());
    }

}
