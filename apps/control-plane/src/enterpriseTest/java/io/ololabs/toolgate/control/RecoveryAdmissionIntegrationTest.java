// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.contracts.EnterpriseInvocationState;
import io.ololabs.toolgate.contracts.EndpointDeviceRecord;
import io.ololabs.toolgate.contracts.EndpointState;
import io.ololabs.toolgate.contracts.PreparedHttpRequestManifest;
import io.ololabs.toolgate.contracts.ServiceAudience;
import io.ololabs.toolgate.contracts.ServicePermit;
import io.ololabs.toolgate.contracts.ServicePermitIssue;
import io.ololabs.toolgate.contracts.v2.PermitReservationRequest;
import io.ololabs.toolgate.contracts.v2.SignedInvocationPermit;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Control issuance/consumption/reporting over already-imported metadata fixtures; not an OCI deployment test. */
final class RecoveryAdmissionIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String IMAGE="sha256:"+"a".repeat(64);
    @TempDir Path temp;
    private boolean postgres;private boolean brokerOnlyApiKey;private boolean rawWorkerSecret;
    @BeforeAll static void key() throws Exception {EnterpriseOperationTest.signingKey();}
    record Fixture(ServicePermitBridgeTest bridge,ServerPackages packages,JsonNode profile) {}

    private Fixture fixture(boolean pins,boolean ordinary) throws Exception {
        var bridge=new ServicePermitBridgeTest();
        bridge.temp=Files.createDirectory(temp.resolve("fixture-"+UUID.randomUUID()));
        bridge.setup();var rig=bridge.rig;
        if(postgres){
            String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
            String database="recovery_scope_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=java.sql.DriverManager.getConnection(base,"control_migrator",password);var statement=connection.createStatement()){
                statement.execute("CREATE DATABASE "+database);
            }
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(
                    io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
            EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
            rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
            bridge.permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
        }
        var corpus=JSON.readTree(Files.readString(Path.of(System.getProperty("toolgate.fixtures"))
                .getParent().getParent().resolve("v2/valid.json")));
        var manifest=(ObjectNode)corpus.path("PackageManifest").deepCopy();
        manifest.put("id","acme.test");
        ((ObjectNode)manifest.path("sdk")).put("language","PYTHON");
        ((ObjectNode)manifest.path("compatibility")).put("contractsVersion",io.ololabs.toolgate.contracts.ContractSet.VERSION);
        var runtime=(ObjectNode)manifest.path("runtimes").get(0);
        runtime.put("runtimeId","python").put("language","python").put("version","3.12").put("baseImageDigest",IMAGE);
        var packaged=(ObjectNode)manifest.path("tools").get(0);
        packaged.put("runtimeId","python");
        var descriptor=(ObjectNode)packaged.path("descriptor");
        descriptor.put("toolId","acme.test/tool");
        descriptor.putArray("permissions").add("HTTP_ROUTE");
        descriptor.putArray("httpAuth");descriptor.putArray("tcp");
        ((ObjectNode)descriptor.path("variables")).putArray("secrets");
        var profile=(ObjectNode)corpus.path("ServiceProfile").deepCopy();
        profile.put("packageId","acme.test");
        var destination=(ObjectNode)profile.path("destinations").get(0);
        destination.put("id",ordinary?"ordinary-api":"olo-memory").put("host","memory.test").put("scheme","https").put("mode","BROKERED");
        var route=(ObjectNode)profile.path("routes").get(0);
        route.put("destination",destination.path("id").asText()).put("method","POST").put("path","/v1/memory.append").put("streaming","NONE");
        route.remove("auth");
        if(brokerOnlyApiKey){
            route.put("auth","broker-key");
            descriptor.putArray("httpAuth").addObject().put("id","broker-key").put("type","API_KEY")
                .put("header","x-api-key").put("secret","AUTH_ONLY").putArray("scopes");
            if(rawWorkerSecret)((ObjectNode)descriptor.path("variables")).putArray("secrets").addObject()
                .put("name","AUTH_ONLY").put("type","STRING").put("scope","TENANT");
            var policy=JSON.createObjectNode().put("routeId",route.path("id").asText()).put("classification","WRITE")
                .put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
            var recipe=policy.putObject("intent").put("path",route.path("path").asText()).put("contentType","application/json")
                .put("bodyMode","CANONICAL_JSON");
            recipe.putObject("query");recipe.putObject("headers");recipe.putObject("body").putObject("path").put("$arg","/path");
            profile.putArray("effectReviews").addObject().put("reviewedBy","admin").put("reviewedAtUnixMs",rig.clock.millis()).set("policy",policy);
        }
        descriptor.set("routes",profile.path("routes").deepCopy());
        packaged.put("toolDescriptorDigest",Canonical.digest(descriptor));
        var artifact=(ObjectNode)manifest.path("artifacts").get(0);artifact.put("runtimeId","python");
        var deployment=(ObjectNode)corpus.path("PackageDeployment").deepCopy();
        deployment.put("packageId","acme.test").put("packageDigest","sha256:"+EnterpriseConformanceTest.DIGEST)
                .put("serviceProfileDigest",Canonical.digest(profile)).put("serviceProfileRevision",profile.path("revision").asLong());
        deployment.putArray("toolIds").add("acme.test/tool");deployment.putArray("hostIds").add("device");
        var registration=(ObjectNode)corpus.path("ToolHostRegistration").deepCopy();
        registration.put("hostId","device").put("poolId","devices-a");
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var document=JSON.createObjectNode();document.set("manifest",manifest);
            tx.packages().saveVersion(new PackageStore.Version("sha256:"+EnterpriseConformanceTest.DIGEST,
                    "acme.test",manifest.path("version").asText(),rig.clock.millis(),document.toString()),new byte[0]);
            tx.packages().saveDeployment("acme.test",deployment.toString());
            tx.packages().saveDocument(Canonical.digest(profile),profile.toString());
            tx.packages().saveToolHost("device",registration.toString());return null;
        });
        return new Fixture(bridge,new ServerPackages(rig.store,rig.graph.codec,rig.operations,rig.clock,
                pins?Set.of("device@devices-a@"+IMAGE):Set.of()),profile);
    }
    private SignedInvocationPermit permit(Fixture fixture,String id) {
        var bridge=fixture.bridge();var rig=bridge.rig;var request=bridge.request(id);
        var invocation=rig.graph.codec.model(bridge.submit(request).body(),EnterpriseInvocation.class);
        return rig.graph.codec.model(fixture.packages().permit(rig.gateway,rig.graph.codec.json(
                new PermitReservationRequest(invocation.id(),"device",1L,request.request().arguments())),id).body(),SignedInvocationPermit.class);
    }
    private void consume(Fixture fixture,SignedInvocationPermit signed) {
        var rig=fixture.bridge().rig;var claims=rig.signer.verifyInvocation(signed);var target=claims.target();
        var consumption=JSON.createObjectNode().put("invocationId",claims.invocationId())
                .put("argumentsDigest",claims.argumentsDigest()).put("toolDescriptorDigest",target.toolDescriptorDigest())
                .put("packageDigest",target.packageDigest()).put("artifactDigest",target.artifactDigest());
        consumption.set("permit",JSON.valueToTree(signed));
        var reply=rig.operations.consumeInvocation(rig.gateway.tenant(),tx->"device",consumption.toString());
        var accepted=rig.graph.codec.model(reply.body(),io.ololabs.toolgate.contracts.v2.PermitConsumed.class);
        assertEquals(claims.nonce(),accepted.fencingToken());
        assertEquals(claims.invocationId(),accepted.invocationId());
        assertTrue(accepted.leaseExpiresAtUnixMs()>rig.clock.millis());
        assertTrue(accepted.leaseExpiresAtUnixMs()<=claims.deadlineUnixMs());
        var scope=accepted.killScope();
        assertEquals("acme.test",scope.packageId());assertEquals(target.packageDigest(),scope.packageDigest());
        assertEquals(target.toolId(),scope.toolId());assertEquals("acme",scope.publisher());
        assertEquals("devices-a",scope.poolId());assertEquals(IMAGE,scope.imageDigest());
        rig.store.transaction(rig.gateway.tenant(),false,tx->{
            assertEquals(tx.enterprise().authorizationEpoch(),accepted.authorizationEpoch());
            var pinned=tx.kills().scope(claims.nonce()).subject();
            assertEquals(pinned.poolId(),scope.poolId());assertEquals(pinned.publisher(),scope.publisher());
            return null;
        });
    }
    @Test void acceptedScopeRetainsPermitPlacementAfterHostRegistrationChanges()throws Exception{
        var fixture=fixture(true,false);var rig=fixture.bridge().rig;var signed=permit(fixture,"pinned-kill-scope");
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var registration=(ObjectNode)rig.graph.codec.value(tx.packages().toolHost("device"));
            registration.put("poolId","devices-b");tx.packages().saveToolHost("device",registration.toString());return null;
        });
        consume(fixture,signed);
    }
    private EnterpriseInvocation failAfterConsumption(Fixture fixture,SignedInvocationPermit signed) {
        consume(fixture,signed);
        return reportFailure(fixture,signed);
    }
    private EnterpriseInvocation reportFailure(Fixture fixture,SignedInvocationPermit signed) {
        var rig=fixture.bridge().rig;var claims=rig.signer.verifyInvocation(signed);
        var report=JSON.createObjectNode().put("invocationId",claims.invocationId()).put("fencingToken",claims.nonce())
                .put("outcome","FAILED").put("executionTier","OCI_SANDBOX");
        return rig.graph.codec.model(rig.operations.reportInvocation(rig.gateway.tenant(),tx->"device",
                report.toString(),claims.nonce()).body(),EnterpriseInvocation.class);
    }
    private ServicePermit grant(Fixture fixture,SignedInvocationPermit signed,String handler){
        var rig=fixture.bridge().rig;var claims=rig.signer.verifyInvocation(signed);
        var i=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(claims.invocationId()));
        var profile=fixture.profile();var route=profile.path("routes").get(0);var destination=profile.path("destinations").get(0);
        var manifest=JSON.createObjectNode().put("version","toolgate-http-request-v1")
                .put("profileId",profile.path("profileId").asText()).put("profileDigest",claims.target().serviceProfileDigest())
                .put("routeId",route.path("id").asText()).put("scheme","https").put("host",destination.path("host").asText())
                .put("port",destination.path("port").asInt()).put("method","POST").put("path","/v1/memory.append")
                .put("query","").put("contentType","application/json").put("bodyDigest","sha256:"+i.evaluation().argumentsDigest())
                .put("authDeclarationId","toolgate-service-permit:olo-memory");
        manifest.putArray("credentialHeaders").add("toolgate-service-permit");
        manifest.putObject("headers").putArray("accept").add("*/*");
        var request=new ServicePermitIssue(i.id(),claims.nonce(),ServiceAudience.olo_memory,Canonical.arguments(manifest),
                rig.graph.codec.model(manifest.toString(),PreparedHttpRequestManifest.class),signed,"broker-test",handler, "memory-effect");
        return rig.graph.codec.model(fixture.bridge().permits.issue(rig.gateway.tenant(),tx->"device",ServicePermitBridgeTest.KEY,
                rig.graph.codec.json(request)).body(),ServicePermit.class);
    }
    private EnterpriseInvocation replay(Fixture f,EnterpriseOperations operations,String id) {
        var rig=f.bridge().rig;
        return rig.graph.codec.model(operations.submitHuman(new DirectoryService.Actor(rig.gateway.tenant(),
                "d".repeat(64),false,false,"alice"),f.bridge().request(id),"device").body(),EnterpriseInvocation.class);
    }
    @Test void concurrentPublicReplayAfterRestartCreatesOneRecoveryAndOneNewPermit() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;
        var first=permit(f,"public-race");var failed=failAfterConsumption(f,first);
        var replica=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();return replay(f,rig.operations,failed.id());});
            var b=pool.submit(()->{start.await();return replay(f,replica,failed.id());});
            start.countDown();var winner=a.get();assertEquals(winner,b.get());
            assertEquals(EnterpriseInvocationState.QUEUED,winner.state());
            assertEquals(failed.expiresAtUnixMs(),winner.expiresAtUnixMs());assertEquals(failed.id(),winner.id());
            assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(failed.id(),100)).size());
            var next=permit(f,failed.id());consume(f,next);
            assertThrows(Failure.class,()->consume(f,first));
            var attempts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(failed.id(),rig.clock.millis()));
            assertEquals(2,attempts.size());assertEquals(2L,rig.signer.verifyInvocation(next).attempt());
        }finally{pool.shutdownNow();}
    }
    private void ask(EnterpriseOperationTest rig,String id){
        var scope=new io.ololabs.toolgate.contracts.EnterpriseScope(
                new io.ololabs.toolgate.contracts.GroupSelection(List.of("tools-a"),false),
                new io.ololabs.toolgate.contracts.GroupSelection(List.of("devices-a"),false),
                List.of("memory.append"),false,List.of(new io.ololabs.toolgate.contracts.EnterpriseResourceRule(
                        io.ololabs.toolgate.contracts.ResourceKind.CUSTOM,"olo://"+rig.gateway.tenant().value()+"/conversation/",
                        io.ololabs.toolgate.contracts.EnterpriseResourceMatch.PREFIX)),rig.graph.conditions());
        rig.graph.add(io.ololabs.toolgate.control.domain.Ids.Kind.POLICY,new io.ololabs.toolgate.contracts.ControlPolicy(
                id,id,true,1L,io.ololabs.toolgate.contracts.Decision.ASK,scope,
                new io.ololabs.toolgate.contracts.GroupSelection(List.of(),true),
                new io.ololabs.toolgate.contracts.GroupSelection(List.of(),true),
                new io.ololabs.toolgate.contracts.GroupSelection(List.of("team-b"),false)));
        rig.saveGraph();
    }
    @Test void anExistingLiveApprovalAllowsRecoveryButAChangedObligationDoesNot() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;
        ask(rig,"approved-ask");
        var pending=rig.graph.codec.model(f.bridge().submit(f.bridge().request("approved-recovery")).body(),EnterpriseInvocation.class);
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,pending.state());
        rig.approve(rig.approval(pending),"approved-ask");
        var signed=permit(f,pending.id());var failed=failAfterConsumption(f,signed);
        var queued=replay(f,rig.operations,failed.id());assertEquals(EnterpriseInvocationState.QUEUED,queued.state());
        var next=permit(f,failed.id());failAfterConsumption(f,next);
        ask(rig,"extra-ask");
        assertThrows(Failure.class,()->replay(f,rig.operations,failed.id()));
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(failed.id(),100)).size());
    }
    @Test void publicRecoveryRechecksCurrentGrantApprovalCredentialAndOriginalDeadline() throws Exception {
        for(var denial:List.of("grant","approval","credential","deadline","cancel")){
            var f=fixture(true,false);var rig=f.bridge().rig;
            var signed=permit(f,"public-denied-"+denial);var failed=failAfterConsumption(f,signed);
            switch(denial){
                case "grant" -> {rig.graph.remove(io.ololabs.toolgate.control.domain.Ids.Kind.GRANT,"human");rig.saveGraph();}
                case "approval" -> {ask(rig,"new-ask");}
                case "credential","deadline" -> {
                    rig.clock=java.time.Clock.offset(rig.clock,java.time.Duration.ofMillis(denial.equals("credential")?15000:60000));
                    rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
                }
                // A durable cancellation racing the terminal report still fences replay.
                case "cancel" -> rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().cancelOperation(failed.id());return null;});
            }
            if(denial.equals("deadline")){
                // Renew the credential, so this checks the original operation deadline itself.
                var request=f.bridge().request(failed.id());var c=request.context();
                var renewed=new io.ololabs.toolgate.contracts.v2.RequestContext(c.requestId(),c.tenantId(),c.mode(),
                        c.credentialKind(),c.issuer(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),
                        c.sessionEpoch(),c.credentialEpoch(),c.delegatedSessionEpoch(),EnterpriseConformanceTest.NOW+120000,
                        c.bindingId(),c.credentialSha256());
                var replay=new io.ololabs.toolgate.contracts.EnterpriseInvocationRequest(renewed,request.request(),
                        request.toolDigest(),request.packageDigest(),request.downstreamIdempotencyKey(),request.executionContext(), null);
                var terminal=rig.graph.codec.model(f.bridge().submit(replay).body(),EnterpriseInvocation.class);
                assertEquals(EnterpriseInvocationState.FAILED,terminal.state());
                assertEquals(failed.expiresAtUnixMs(),terminal.expiresAtUnixMs());
            }else assertThrows(Failure.class,()->replay(f,rig.operations,failed.id()),denial);
            assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(failed.id(),100)).isEmpty(),denial);
            assertNotEquals(EnterpriseInvocationState.QUEUED,rig.store.transaction(rig.gateway.tenant(),false,
                    tx->tx.enterprise().invocation(failed.id())).state());
        }
    }
    @Test void unqualifiedFailureAndAnySentGenerationStayFencedOnPublicReplay() throws Exception {
        var unqualified=fixture(false,false);var rig=unqualified.bridge().rig;
        var failed=failAfterConsumption(unqualified,permit(unqualified,"public-unqualified"));
        var replayed=replay(unqualified,rig.operations,failed.id());
        assertEquals(failed,EnterpriseOperationTest.reported(replayed));
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOperationState.OUTCOME_UNKNOWN,replayed.operationOutcome().state());
        assertFalse(replayed.operationOutcome().retryable());
        var qualified=fixture(true,false);var r=qualified.bridge().rig;
        var signed=permit(qualified,"public-sent");consume(qualified,signed);
        var write=grant(qualified,signed,"sent-handler");qualified.bridge().sending(write,"broker-test","sent-handler");
        var uncertain=reportFailure(qualified,signed);
        assertEquals(uncertain,EnterpriseOperationTest.reported(replay(qualified,r.operations,uncertain.id())));
        assertTrue(r.store.transaction(r.gateway.tenant(),false,tx->tx.enterprise().recoveries(uncertain.id(),100)).isEmpty());
    }

    @Test void onlyAProvenUnsentGenerationCanReceiveFreshWriteAuthority() throws Exception {
        var fixture=fixture(true,false);var rig=fixture.bridge().rig;
        var first=permit(fixture,"scope-unsent");consume(fixture,first);
        var old=grant(fixture,first,"first-handler");var oldClaims=rig.signer.verifyService(old);
        assertEquals(1L,oldClaims.authorizationGeneration());
        var failed=reportFailure(fixture,first);
        assertEquals("RELEASED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(oldClaims.nonce())).state());
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().rebind(failed,
                OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,rig.clock.millis()));
        var second=permit(fixture,failed.id());consume(fixture,second);
        var fresh=grant(fixture,second,"second-handler");var freshClaims=rig.signer.verifyService(fresh);
        assertNotEquals(oldClaims.nonce(),freshClaims.nonce());assertEquals(oldClaims.effectId(),freshClaims.effectId());
        assertEquals(2L,freshClaims.authorizationGeneration());
        assertThrows(Failure.class,()->fixture.bridge().sending(old,"broker-test","first-handler"));
        fixture.bridge().sending(fresh,"broker-test","second-handler");
        assertEquals("SENDING",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(freshClaims.nonce())).state());
        var uncertain=reportFailure(fixture,second);
        var facts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryFacts(uncertain.id()));
        assertNull(OperationRebindingPolicy.proof(facts,rig.clock.millis()));
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().rebind(
                uncertain,OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,rig.clock.millis())));
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(failed.id(),100)).size());
    }

    @Test void aRecoveredGenerationCanCompleteOnlyOnceThroughTheTargetClaimSdk() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;
        var first=permit(f,"recovered-target");consume(f,first);var unsent=grant(f,first,"unsent");
        var failed=reportFailure(f,first);assertEquals(EnterpriseInvocationState.QUEUED,replay(f,rig.operations,failed.id()).state());
        var replacement=permit(f,failed.id());consume(f,replacement);var write=grant(f,replacement,"fresh");
        var claims=rig.signer.verifyService(write);assertEquals(2L,claims.authorizationGeneration());
        f.bridge().sending(write,"broker-test","fresh");
        var sdk=new io.ololabs.toolgate.service.JdbcEffectClaims();
        var binding=new io.ololabs.toolgate.service.JdbcEffectClaims.Binding(rig.gateway.tenant().value(),claims.effectId(),
                claims.authorizationGeneration(),claims.nonce(),claims.httpRequestDigest());
        String url="jdbc:sqlite:"+temp.resolve("target-"+UUID.randomUUID()+".sqlite");
        io.ololabs.toolgate.service.JdbcEffectClaims.Owner owner;
        try(var c=java.sql.DriverManager.getConnection(url)){
            c.setAutoCommit(false);io.ololabs.toolgate.service.JdbcEffectClaims.initialize(c);
            try(var statement=c.createStatement()){statement.executeUpdate("CREATE TABLE mutations(effect_id TEXT PRIMARY KEY)");}
            var acquired=sdk.claim(c,binding,claims.exp());assertTrue(acquired.acquired());owner=acquired.owner();c.commit();
        }
        var accepted=f.bridge().claimConsume(write,owner.claimToken());
        assertEquals(io.ololabs.toolgate.contracts.ServicePermitClaimState.CONSUMED,accepted.status());
        try(var c=java.sql.DriverManager.getConnection(url)){
            c.setAutoCommit(false);assertTrue(sdk.beginApply(c,owner));
            try(var statement=c.prepareStatement("INSERT INTO mutations VALUES(?)")){statement.setString(1,claims.effectId());statement.executeUpdate();}
            assertTrue(sdk.finish(c,owner,true,ServicePermitBridgeTest.RESULT_DIGEST));c.commit();
        }
        assertEquals(io.ololabs.toolgate.contracts.ServicePermitClaimState.COMPLETED,
                f.bridge().targetOutcome(write,owner.claimToken(),io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED).status());
        try(var c=java.sql.DriverManager.getConnection(url)){
            c.setAutoCommit(false);assertFalse(sdk.claim(c,binding,claims.exp()).acquired());
            assertFalse(sdk.beginApply(c,owner));
            try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM mutations")){
                assertTrue(rows.next());assertEquals(1,rows.getInt(1));
            }c.commit();
        }
        assertThrows(Failure.class,()->f.bridge().sending(unsent,"broker-test","unsent"));
        assertThrows(Failure.class,()->f.bridge().claimConsume(unsent,owner.claimToken()));
        assertEquals("COMPLETED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.nonce())).state());
    }

    private EnterpriseStore.AttemptSettlement settle(Fixture f,SignedInvocationPermit signed){
        var rig=f.bridge().rig;var claims=rig.signer.verifyInvocation(signed);
        return rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().settleAttempt(claims.invocationId(),
                claims.nonce(),EnterpriseStore.SettlementProof.QUALIFIED_BROKER_ONLY,rig.clock.millis()));
    }
    @Test void aQualifiedTerminalAttemptClosesItsLeaseAndSettlesWithoutNewAuthority() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"qualified-settlement");consume(f,signed);
        assertThrows(Failure.class,()->settle(f,signed));
        var failed=reportFailure(f,signed);
        var lease=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().executionLease(failed.id()));
        assertTrue(lease.expiresAt()<=rig.clock.millis());
        var record=settle(f,signed);assertEquals(record,settle(f,signed));
        assertEquals(EnterpriseStore.SettlementProof.QUALIFIED_BROKER_ONLY,record.proof());
        assertEquals(EnterpriseInvocationState.FAILED,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(failed.id())).state());
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(failed.id(),rig.clock.millis())).size());
    }
    @Test void settlementRejectsEveryPotentialWriteUntilTargetWitnessResolvesIt() throws Exception {
        var unsent=fixture(true,false);var u=permit(unsent,"settle-unsent");consume(unsent,u);
        var old=grant(unsent,u,"unsent");reportFailure(unsent,u);
        assertNotNull(settle(unsent,u));assertThrows(Failure.class,()->unsent.bridge().sending(old,"broker-test","unsent"));
        var sent=fixture(true,false);var s=permit(sent,"settle-sent");consume(sent,s);
        var grant=grant(sent,s,"sent");sent.bridge().sending(grant,"broker-test","sent");reportFailure(sent,s);
        assertThrows(Failure.class,()->settle(sent,s));
        var pending=fixture(true,false);var p=permit(pending,"settle-target");consume(pending,p);
        var target=grant(pending,p,"target");pending.bridge().sending(target,"broker-test","target");
        String token="e".repeat(64);pending.bridge().claimConsume(target,token);reportFailure(pending,p);
        assertThrows(Failure.class,()->settle(pending,p));
        pending.bridge().targetOutcome(target,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.FAILED);
        assertNotNull(settle(pending,p));
        // A later contradiction remains independently visible despite the immutable settlement.
        pending.bridge().targetOutcome(target,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        var rig=pending.bridge().rig;var claims=rig.signer.verifyService(target);
        assertEquals("CONFLICT",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.nonce())).state());
    }
    @Test void aDisabledEndpointOrDirectoryDeviceCannotSupplySettlementProof() throws Exception {
        for(boolean endpoint:List.of(true,false)){
            var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"disabled-settlement");
            consume(f,signed);
            if(endpoint)rig.store.transaction(rig.gateway.tenant(),true,tx->{
                var old=tx.endpoint("device");var document=(ObjectNode)rig.graph.codec.value(old.document());
                document.put("state","REVOKED").put("revision",document.path("revision").asLong()+1);
                tx.saveEndpoint(new Store.EndpointRecord(old.id(),old.fingerprint(),document.toString(),old.csr(),old.reportDigest(),old.acknowledgment()));
                return null;
            });
            else{
                var id=io.ololabs.toolgate.control.domain.Ids.Kind.DEVICE.id("device");
                var document=(ObjectNode)rig.graph.codec.value(rig.graph.entries.get(id).document());
                document.put("enabled",false).put("revision",document.path("revision").asLong()+1);
                var entry=rig.graph.codec.entry(io.ololabs.toolgate.control.domain.Ids.Kind.DEVICE,document.toString());
                rig.graph.entries.put(entry.id(),entry);rig.saveGraph();
            }
            reportFailure(f,signed);
            assertThrows(Failure.class,()->settle(f,signed));
            var claims=rig.signer.verifyInvocation(signed);
            assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attemptSettlement(claims.invocationId(),claims.nonce())));
        }
    }

    private OperationFinalizationPolicy.Result project(Fixture f,String operation){
        var rig=f.bridge().rig;
        return rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var persisted=new OperationFinalizer(rig.graph.codec).refresh(tx,operation,rig.clock.millis());
            var derived=OperationFinalizationPolicy.derive(tx.enterprise().finalizationFacts(operation,rig.clock.millis()));
            assertEquals(derived.status().name(),persisted.state().name());
            assertFalse(persisted.retryable());
            assertEquals(persisted,new OperationFinalizer(rig.graph.codec).refresh(tx,operation,rig.clock.millis()+1));
            return derived;
        });
    }
    private EnterpriseInvocation reportSuccess(Fixture f,SignedInvocationPermit signed){
        var rig=f.bridge().rig;var claims=rig.signer.verifyInvocation(signed);
        var report=JSON.createObjectNode().put("invocationId",claims.invocationId()).put("fencingToken",claims.nonce()).put("outcome","COMPLETED").put("executionTier","OCI_SANDBOX");
        var result=report.putObject("result");result.put("kind","RESULT").put("resultType","complete").put("isError",false);result.putArray("content");
        return rig.graph.codec.model(rig.operations.reportInvocation(rig.gateway.tenant(),tx->"device",report.toString(),claims.nonce()).body(),EnterpriseInvocation.class);
    }
    @Test void authoritativeProjectionUsesAllRecoveryGenerationsAndWaitsForWorkerClosure() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var a=permit(f,"all-generation-projection");consume(f,a);
        grant(f,a,"unsent");var failed=reportFailure(f,a);
        assertEquals(OperationFinalizationPolicy.Status.FAILED,project(f,failed.id()).status());
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade.CONTROL_PROVEN,
                rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome(failed.id())).value().grade());
        assertEquals(EnterpriseInvocationState.QUEUED,replay(f,rig.operations,failed.id()).state());
        var b=permit(f,failed.id());consume(f,b);var write=grant(f,b,"replacement");
        f.bridge().sending(write,"broker-test","replacement");String token="f".repeat(64);f.bridge().claimConsume(write,token);
        assertEquals(OperationFinalizationPolicy.Status.RUNNING,project(f,failed.id()).status());
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        assertEquals(OperationFinalizationPolicy.Status.RUNNING,project(f,failed.id()).status());
        reportSuccess(f,b);
        assertEquals(OperationFinalizationPolicy.Status.SUCCEEDED,project(f,failed.id()).status());
        assertEquals(2,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().finalizationFacts(failed.id(),rig.clock.millis())).effects().getFirst().generations().size());
        var observed=rig.graph.codec.model(rig.operations.get(rig.gateway,failed.id()).body(),EnterpriseInvocation.class);
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOperationState.SUCCEEDED,observed.operationOutcome().state());
        var first=rig.graph.codec.model(rig.operations.history(rig.gateway,failed.id(),null,1).body(),
                io.ololabs.toolgate.contracts.EnterpriseOperationHistoryPage.class);
        assertEquals(2,first.attempts().size());assertEquals(1,first.effects().size());assertNotNull(first.next());
        var second=rig.graph.codec.model(rig.operations.history(rig.gateway,failed.id(),first.next(),1).body(),
                io.ololabs.toolgate.contracts.EnterpriseOperationHistoryPage.class);
        assertEquals(1,second.effects().size());assertNull(second.next());
        assertEquals(2L,second.effects().getFirst().generation());assertNotNull(second.effects().getFirst().receiptDigest());
        String metadata=rig.graph.codec.json(second);
        for(String forbidden:List.of(token,"claimToken","authNonce","credentialHeaders","arguments","permit"))
            assertFalse(metadata.contains(forbidden),forbidden);
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.FAILED);
        assertEquals(409,assertThrows(Failure.class,()->rig.operations.history(rig.gateway,failed.id(),first.next(),1)).status());
        assertEquals(OperationFinalizationPolicy.Status.RECONCILIATION_REQUIRED,project(f,failed.id()).status());
    }
    @Test void anUnqualifiedSuccessfulReportAndAnUnresolvedQualifiedWriteStayUnknown() throws Exception {
        var unqualified=fixture(false,false);var signed=permit(unqualified,"unqualified-projection");consume(unqualified,signed);
        var i=reportSuccess(unqualified,signed);
        assertEquals(EnterpriseInvocationState.SUCCEEDED,i.state());
        assertEquals(OperationFinalizationPolicy.Status.OUTCOME_UNKNOWN,project(unqualified,i.id()).status());
        var f=fixture(true,false);var p=permit(f,"ambiguous-projection");consume(f,p);
        var write=grant(f,p,"ambiguous");f.bridge().sending(write,"broker-test","ambiguous");
        var failed=reportFailure(f,p);var result=project(f,failed.id());
        assertEquals(OperationFinalizationPolicy.Status.OUTCOME_UNKNOWN,result.status());
        assertEquals(1,result.unresolvedEffects());
    }
    @Test void completedTransportWithAnErrorFrameCannotSupplySuccessfulAttemptEvidence() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"error-frame");
        consume(f,signed);var claims=rig.signer.verifyInvocation(signed);var write=grant(f,signed,"error-frame");
        f.bridge().sending(write,"broker-test","error-frame");String token="e".repeat(64);f.bridge().claimConsume(write,token);
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        var report=JSON.createObjectNode().put("invocationId",claims.invocationId()).put("fencingToken",claims.nonce()).put("outcome","COMPLETED").put("executionTier","OCI_SANDBOX");
        var result=report.putObject("result");result.put("kind","RESULT").put("resultType","complete").put("isError",true);result.putArray("content");
        var received=rig.graph.codec.model(rig.operations.reportInvocation(rig.gateway.tenant(),tx->"device",report.toString(),claims.nonce()).body(),EnterpriseInvocation.class);
        assertEquals(EnterpriseInvocationState.FAILED,received.state());
        assertEquals(OperationFinalizationPolicy.Status.PARTIAL,project(f,received.id()).status());
    }
    @Test void contradictoryTargetEvidenceCancelsActiveAuthorityButKeepsReportAndHistory() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"active-conflict");
        consume(f,signed);var claims=rig.signer.verifyInvocation(signed);var write=grant(f,signed,"active-conflict");
        f.bridge().sending(write,"broker-test","active-conflict");String token="c".repeat(64);f.bridge().claimConsume(write,token);
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.FAILED);
        assertEquals(403,assertThrows(Failure.class,()->grant(f,signed,"refused-conflict")).status());
        var heartbeat=JSON.createObjectNode().put("invocationId",claims.invocationId()).put("fencingToken",claims.nonce());
        var reply=rig.operations.heartbeat(rig.gateway.tenant(),tx->"device",claims.invocationId(),heartbeat.toString());
        assertTrue(JSON.readTree(reply.body()).path("cancelRequested").asBoolean());
        var current=rig.graph.codec.model(rig.operations.get(rig.gateway,claims.invocationId()).body(),EnterpriseInvocation.class);
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOperationState.RECONCILIATION_REQUIRED,current.operationOutcome().state());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,current.state());
        assertEquals(current.state(),reportSuccess(f,signed).state());
        var history=JSON.readTree(rig.operations.history(rig.gateway,claims.invocationId(),null,100).body());
        assertEquals("RECONCILIATION_REQUIRED",history.path("operation").path("state").asText());
        assertFalse(history.path("operation").path("retryable").asBoolean());
        assertEquals("CONFLICT",history.path("effects").get(0).path("state").asText());
    }
    @Test void qualifiedLateSuccessDoesNotResolveAPendingWriteButCanCompleteAfterItsTargetWitness() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"late-qualified-success");
        consume(f,signed);var claims=rig.signer.verifyInvocation(signed);var write=grant(f,signed,"late-qualified");
        f.bridge().sending(write,"broker-test","late-qualified");String token="7".repeat(64);f.bridge().claimConsume(write,token);
        rig.clock=java.time.Clock.offset(rig.clock,java.time.Duration.ofMillis(30001));
        rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        f.bridge().permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
        rig.operations.sweep(rig.gateway.tenant());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,reportSuccess(f,signed).state());
        assertEquals(OperationFinalizationPolicy.Status.OUTCOME_UNKNOWN,project(f,claims.invocationId()).status());
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        assertEquals(OperationFinalizationPolicy.Status.SUCCEEDED,project(f,claims.invocationId()).status());
        // Renew the user's credential through ordinary admission replay; this cannot resend a completed effect.
        var original=f.bridge().request(claims.invocationId());var context=original.context();
        var renewed=new io.ololabs.toolgate.contracts.v2.RequestContext(context.requestId(),context.tenantId(),context.mode(),
                context.credentialKind(),context.issuer(),context.userId(),context.agentId(),context.workloadBindingId(),context.chain(),
                context.sessionEpoch(),context.credentialEpoch(),context.delegatedSessionEpoch(),rig.clock.millis()+15000,
                context.bindingId(),context.credentialSha256());
        var request=new io.ololabs.toolgate.contracts.EnterpriseInvocationRequest(renewed,original.request(),
                original.toolDigest(),original.packageDigest(),original.downstreamIdempotencyKey(),original.executionContext(),original.businessKeyHash());
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,rig.graph.codec.model(f.bridge().submit(request).body(),EnterpriseInvocation.class).state());
        var history=rig.graph.codec.model(rig.operations.history(rig.gateway,claims.invocationId(),null,10).body(),
                io.ololabs.toolgate.contracts.EnterpriseOperationHistoryPage.class);
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOperationState.SUCCEEDED,history.operation().state());
        assertEquals(EnterpriseInvocationState.SUCCEEDED,history.attempts().getFirst().state());
        assertEquals(io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade.REPORTED,history.attempts().getFirst().grade());
        assertTrue(history.attempts().getFirst().settled());assertFalse(history.operation().retryable());
        assertTrue(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(claims.invocationId(),100)).isEmpty());
    }
    @Test void reenablingADeviceCannotUpgradeAnEarlierUnqualifiedLateReport() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"late-disabled-success");
        consume(f,signed);var write=grant(f,signed,"disabled-late");String token="8".repeat(64);
        f.bridge().sending(write,"broker-test","disabled-late");f.bridge().claimConsume(write,token);reportFailure(f,signed);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var old=tx.endpoint("device");var document=(ObjectNode)rig.graph.codec.value(old.document());
            document.put("state","REVOKED").put("revision",document.path("revision").asLong()+1);
            tx.saveEndpoint(new Store.EndpointRecord(old.id(),old.fingerprint(),document.toString(),old.csr(),old.reportDigest(),old.acknowledgment()));return null;
        });
        // Trusted receipt-port fixture: public disabled-device ingress is separately authenticated.
        assertEquals(EnterpriseInvocationState.FAILED,reportSuccess(f,signed).state());
        assertEquals(OperationFinalizationPolicy.Status.OUTCOME_UNKNOWN,project(f,"late-disabled-success").status());
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var old=tx.endpoint("device");var document=(ObjectNode)rig.graph.codec.value(old.document());
            document.put("state","ACTIVE").put("revision",document.path("revision").asLong()+1);
            tx.saveEndpoint(new Store.EndpointRecord(old.id(),old.fingerprint(),document.toString(),old.csr(),old.reportDigest(),old.acknowledgment()));return null;
        });
        f.bridge().targetOutcome(write,token,io.ololabs.toolgate.contracts.ServicePermitTargetOutcomeKind.COMPLETED);
        assertEquals(OperationFinalizationPolicy.Status.PARTIAL,project(f,"late-disabled-success").status());
        reportSuccess(f,signed); // The same immutable report remains unqualified despite re-enablement.
        assertEquals(OperationFinalizationPolicy.Status.PARTIAL,project(f,"late-disabled-success").status());
    }
    @Test void outcomePersistenceAndItsAuditRollBackTogether() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"outcome-rollback");
        consume(f,signed);
        String before=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100));
        var previous=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome("outcome-rollback"));
        assertThrows(IllegalStateException.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var running=tx.enterprise().invocation("outcome-rollback");
            var terminal=new EnterpriseInvocation(running.id(),running.requestDigest(),running.evaluation(),EnterpriseInvocationState.SUCCEEDED,
                    running.revision()+1,running.authorizationEpoch(),running.reservedNonce(),running.expiresAtUnixMs(),
                    running.evaluation().resources(),running.downstreamIdempotencyKey(),"a".repeat(64),running.diagnosticId(),running.reconciliation(), null);
            tx.enterprise().saveInvocation(terminal,rig.clock.millis(),running.revision());
            var value=new OperationFinalizer(rig.graph.codec).refresh(tx,"outcome-rollback",rig.clock.millis());
            assertTrue(value.finalized());
            throw new IllegalStateException("rollback");
        }));
        assertEquals(previous,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome("outcome-rollback")));
        assertEquals(before,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.auditPage(0,100)));
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation("outcome-rollback")).state());
        reportSuccess(f,signed);project(f,"outcome-rollback");
    }
    @Test void recordedSettlementDoesNotLoseItsHistoricalProofWhenDeviceTrustChanges() throws Exception {
        var f=fixture(true,false);var rig=f.bridge().rig;var signed=permit(f,"historical-settlement");
        consume(f,signed);reportSuccess(f,signed);
        assertEquals(OperationFinalizationPolicy.Status.SUCCEEDED,project(f,"historical-settlement").status());
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var old=tx.endpoint("device");var document=(ObjectNode)rig.graph.codec.value(old.document());
            document.put("state","REVOKED").put("revision",document.path("revision").asLong()+1);
            tx.saveEndpoint(new Store.EndpointRecord(old.id(),old.fingerprint(),document.toString(),old.csr(),old.reportDigest(),old.acknowledgment()));return null;
        });
        assertEquals(OperationFinalizationPolicy.Status.SUCCEEDED,project(f,"historical-settlement").status());
    }

    @Test void realSignedAdmissionCapturesScopeBeforeConsumptionAndBindsEverySignedDigest() throws Exception {
        var fixture=fixture(true,false);var signed=permit(fixture,"scope-signed");
        var rig=fixture.bridge().rig;var claims=rig.signer.verifyInvocation(signed);
        var scope=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(claims.nonce()));
        assertNotNull(scope);assertEquals("device",scope.deviceId());assertEquals(IMAGE,scope.imageDigest());
        assertEquals(claims.target().runtimeDigest(),scope.runtimeDigest());
        assertEquals(claims.target().toolDescriptorDigest(),scope.descriptorDigest());
        assertEquals(claims.target().serviceProfileDigest(),scope.profileDigest());
        var failed=failAfterConsumption(fixture,signed);
        var facts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryFacts(failed.id()));
        assertEquals(OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,OperationRebindingPolicy.proof(facts,rig.clock.millis()));
        var oldLease=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().executionLease(failed.id()));
        var replacement=rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().rebind(failed,
                OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,rig.clock.millis()));
        assertEquals(failed.expiresAtUnixMs(),replacement.expiresAtUnixMs());
        var next=rig.graph.codec.model(fixture.packages().permit(rig.gateway,rig.graph.codec.json(new PermitReservationRequest(
                failed.id(),"device",1L,fixture.bridge().request(failed.id()).request().arguments())),"replacement").body(),SignedInvocationPermit.class);
        var nextClaims=rig.signer.verifyInvocation(next);
        assertNotEquals(claims.nonce(),nextClaims.nonce());assertEquals(2L,nextClaims.attempt());
        failAfterConsumption(fixture,next);
        var newLease=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().executionLease(failed.id()));
        assertEquals(nextClaims.nonce(),newLease.fencingToken());assertNotEquals(oldLease.fencingToken(),newLease.fencingToken());
        var history=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveries(failed.id(),100));
        assertEquals(1,history.size());
        var archive=JSON.readTree(history.getFirst().document());
        assertEquals(oldLease.fencingToken(),JSON.readTree(archive.path("lease").asText()).path("fencingToken").asText());
    }
    private String relayResult(com.fasterxml.jackson.databind.node.ObjectNode report){
        var frame=report.deepCopy();frame.remove("executionTier");return frame.toString();
    }
    @Test void lateReceiptsCannotReplaceTheRecoveredDispatchOrItsCurrentOutcome() throws Exception {
        var fixture=fixture(true,false);var rig=fixture.bridge().rig;
        var signed=permit(fixture,"scope-receipt");var claims=rig.signer.verifyInvocation(signed);
        var failed=failAfterConsumption(fixture,signed);
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().rebind(failed,
                OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,rig.clock.millis()));
        var device=new EndpointDeviceRecord("device",rig.gateway.tenant().value(),"alice","f".repeat(64),
                EndpointState.ACTIVE,1L,0L,0L,null,null,true,1L,null,null);
        var late=JSON.createObjectNode().put("invocationId",failed.id()).put("fencingToken",claims.nonce()).put("outcome","COMPLETED").put("executionTier","OCI_SANDBOX");
        late.set("result",JSON.readTree("{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"isError\":false}"));
        var receipt=fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),relayResult(late));
        assertEquals("EXPIRED",JSON.readTree(receipt.body()).path("state").asText());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().relayed(failed.id())));
        var next=rig.graph.codec.model(fixture.packages().permit(rig.gateway,rig.graph.codec.json(
                new PermitReservationRequest(failed.id(),"device",1L,fixture.bridge().request(failed.id()).request().arguments())),
                "next").body(),SignedInvocationPermit.class);
        var nextClaims=rig.signer.verifyInvocation(next);
        var dispatch=JSON.createObjectNode();dispatch.set("permit",JSON.valueToTree(next));
        dispatch.set("arguments",JSON.valueToTree(fixture.bridge().request(failed.id()).request().arguments()));
        fixture.packages().relay(rig.gateway,dispatch.toString());
        assertEquals(200,fixture.packages().next(rig.gateway.tenant(),tx->device).status());
        var oldAdmission=JSON.createObjectNode().put("kind","ADMITTED").put("invocationId",failed.id()).put("fencingToken",claims.nonce());
        assertEquals(409,assertThrows(Failure.class,()->fixture.packages().admission(rig.gateway.tenant(),tx->device,failed.id(),oldAdmission.toString())).status());
        var oldRefusal=JSON.createObjectNode().put("kind","REFUSED").put("invocationId",failed.id()).put("reason","BUSY");
        assertEquals(409,assertThrows(Failure.class,()->fixture.packages().admission(rig.gateway.tenant(),tx->device,failed.id(),oldRefusal.toString())).status());
        consume(fixture,next);
        oldAdmission.put("fencingToken",nextClaims.nonce());
        fixture.packages().admission(rig.gateway.tenant(),tx->device,failed.id(),oldAdmission.toString());
        fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),relayResult(late));
        rig.operations.reportInvocation(rig.gateway.tenant(),tx->"device",late.toString(),claims.nonce());
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(failed.id())).state());
        var current=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().relayed(failed.id()));
        assertEquals("ADMITTED",current.state());assertFalse(JSON.readTree(current.document()).path("status").has("result"));
        var attempts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(failed.id(),rig.clock.millis()));
        assertEquals(2,attempts.size());
        // Relay transport result and tier-bearing Control report are distinct current evidence.
        assertEquals(3L,attempts.getFirst().evidenceCount());assertEquals(0L,attempts.getLast().evidenceCount());
        late.put("fencingToken","not-an-issued-attempt");
        assertEquals(403,assertThrows(Failure.class,()->fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),relayResult(late))).status());
    }
    @Test void consumedRelayedCallsOutliveAdmissionPermitButNeverTheirExecutionDeadline() throws Exception {
        var fixture=fixture(true,false);var rig=fixture.bridge().rig;
        var signed=permit(fixture,"admitted-after-permit-expiry");var claims=rig.signer.verifyInvocation(signed);
        assertTrue(claims.exp()<claims.deadlineUnixMs());
        var device=new EndpointDeviceRecord("device",rig.gateway.tenant().value(),"alice","f".repeat(64),
                EndpointState.ACTIVE,1L,0L,0L,null,null,true,1L,null,null);
        var dispatch=JSON.createObjectNode();dispatch.set("permit",JSON.valueToTree(signed));
        dispatch.set("arguments",JSON.valueToTree(fixture.bridge().request(claims.invocationId()).request().arguments()));
        fixture.packages().relay(rig.gateway,dispatch.toString());
        assertEquals(200,fixture.packages().next(rig.gateway.tenant(),tx->device).status());
        consume(fixture,signed);
        var afterPermit=new ServerPackages(rig.store,rig.graph.codec,rig.operations,
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(claims.exp()+1),java.time.ZoneOffset.UTC));
        assertEquals("CLAIMED",JSON.readTree(afterPermit.relayed(rig.gateway,claims.invocationId()).body()).path("state").asText());
        var admission=JSON.createObjectNode().put("kind","ADMITTED").put("invocationId",claims.invocationId()).put("fencingToken","wrong-fence");
        assertEquals(409,assertThrows(Failure.class,()->afterPermit.admission(rig.gateway.tenant(),tx->device,claims.invocationId(),admission.toString())).status());
        admission.put("fencingToken",claims.nonce());
        assertEquals("ADMITTED",JSON.readTree(afterPermit.admission(rig.gateway.tenant(),tx->device,claims.invocationId(),admission.toString()).body()).path("state").asText());
        assertEquals("ADMITTED",JSON.readTree(afterPermit.relayed(rig.gateway,claims.invocationId()).body()).path("state").asText());
        var expired=new ServerPackages(rig.store,rig.graph.codec,rig.operations,
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(claims.deadlineUnixMs()+1),java.time.ZoneOffset.UTC));
        assertEquals("EXPIRED",JSON.readTree(expired.relayed(rig.gateway,claims.invocationId()).body()).path("state").asText());
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().invocation(claims.invocationId())).state());

        // A merely claimed permit still expires. Metadata does not consume it.
        var unused=permit(fixture,"never-consumed-relay");var unusedClaims=rig.signer.verifyInvocation(unused);
        dispatch.set("permit",JSON.valueToTree(unused));
        dispatch.set("arguments",JSON.valueToTree(fixture.bridge().request(unusedClaims.invocationId()).request().arguments()));
        fixture.packages().relay(rig.gateway,dispatch.toString());
        assertEquals(200,fixture.packages().next(rig.gateway.tenant(),tx->device).status());
        var neverConsumed=new ServerPackages(rig.store,rig.graph.codec,rig.operations,
                java.time.Clock.fixed(java.time.Instant.ofEpochMilli(unusedClaims.exp()+1),java.time.ZoneOffset.UTC));
        assertEquals("EXPIRED",JSON.readTree(neverConsumed.relayed(rig.gateway,unusedClaims.invocationId()).body()).path("state").asText());
        admission.put("invocationId",unusedClaims.invocationId()).put("fencingToken",unusedClaims.nonce());
        assertEquals(409,assertThrows(Failure.class,()->neverConsumed.admission(rig.gateway.tenant(),tx->device,unusedClaims.invocationId(),admission.toString())).status());
    }

    @Test void signedScopeAndLeaseRecoveryAlsoHoldUnderThePostgresRuntimeRole() throws Exception {
        Assumptions.assumeTrue(System.getenv("CONTROL_TEST_URL")!=null&&System.getenv("CONTROL_TEST_PASSWORD")!=null,
                "Requires isolated PostgreSQL fixture");
        postgres=true;
        consumedRelayedCallsOutliveAdmissionPermitButNeverTheirExecutionDeadline();
        realSignedAdmissionCapturesScopeBeforeConsumptionAndBindsEverySignedDigest();
        anExecutorTierReportCannotEnableRecoveryWithoutTheIndependentOperatorPin();
        aPinnedOciExecutorWithAnOrdinaryHttpRouteStillCannotQualify();
        lateReceiptsCannotReplaceTheRecoveredDispatchOrItsCurrentOutcome();
        onlyAProvenUnsentGenerationCanReceiveFreshWriteAuthority();
        aRecoveredGenerationCanCompleteOnlyOnceThroughTheTargetClaimSdk();
        aQualifiedTerminalAttemptClosesItsLeaseAndSettlesWithoutNewAuthority();
        settlementRejectsEveryPotentialWriteUntilTargetWitnessResolvesIt();
        aDisabledEndpointOrDirectoryDeviceCannotSupplySettlementProof();
        authoritativeProjectionUsesAllRecoveryGenerationsAndWaitsForWorkerClosure();
        anUnqualifiedSuccessfulReportAndAnUnresolvedQualifiedWriteStayUnknown();
        recordedSettlementDoesNotLoseItsHistoricalProofWhenDeviceTrustChanges();
        outcomePersistenceAndItsAuditRollBackTogether();
        qualifiedLateSuccessDoesNotResolveAPendingWriteButCanCompleteAfterItsTargetWitness();
        reenablingADeviceCannotUpgradeAnEarlierUnqualifiedLateReport();
        completedTransportWithAnErrorFrameCannotSupplySuccessfulAttemptEvidence();
        contradictoryTargetEvidenceCancelsActiveAuthorityButKeepsReportAndHistory();
        concurrentPublicReplayAfterRestartCreatesOneRecoveryAndOneNewPermit();
        publicRecoveryRechecksCurrentGrantApprovalCredentialAndOriginalDeadline();
        anExistingLiveApprovalAllowsRecoveryButAChangedObligationDoesNot();
        unqualifiedFailureAndAnySentGenerationStayFencedOnPublicReplay();
    }
    @Test void anExecutorTierReportCannotEnableRecoveryWithoutTheIndependentOperatorPin() throws Exception {
        var fixture=fixture(false,false);var signed=permit(fixture,"scope-no-pin");
        var rig=fixture.bridge().rig;var failed=failAfterConsumption(fixture,signed);
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(failed.reservedNonce())));
        assertNull(OperationRebindingPolicy.proof(rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().recoveryFacts(failed.id())),rig.clock.millis()));
    }
    @Test void aPinnedOciExecutorWithAnOrdinaryHttpRouteStillCannotQualify() throws Exception {
        var fixture=fixture(true,true);var signed=permit(fixture,"scope-ordinary");
        var rig=fixture.bridge().rig;var failed=failAfterConsumption(fixture,signed);
        assertNull(OperationRebindingPolicy.proof(rig.store.transaction(rig.gateway.tenant(),false,
                tx->tx.enterprise().recoveryFacts(failed.id())),rig.clock.millis()));
    }
    @Test void reviewedGenericBrokerRoutesCaptureRealSignedRecoveryScopeAndMissingReviewCannotQualify()throws Exception{
        var f=fixture(true,true);var rig=f.bridge().rig;
        var profile=(ObjectNode)f.profile();var route=profile.path("routes").get(0);
        var policy=JSON.createObjectNode().put("routeId",route.path("id").asText()).put("classification","WRITE")
            .put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
        var intent=policy.putObject("intent").put("path",route.path("path").asText()).put("contentType","application/json")
            .put("bodyMode","CANONICAL_JSON");
        intent.putObject("query");intent.putObject("headers");intent.putObject("body").putObject("path").put("$arg","/path");
        profile.putArray("effectReviews").addObject().put("reviewedBy","admin").put("reviewedAtUnixMs",rig.clock.millis()).set("policy",policy);
        String digest=Canonical.digest(profile);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var deployment=(ObjectNode)JSON.valueToTree(rig.graph.codec.value(tx.packages().deployment("acme.test")));
            deployment.put("serviceProfileDigest",digest);
            tx.packages().saveDocument(digest,profile.toString());tx.packages().saveDeployment("acme.test",deployment.toString());return null;
        });
        var signed=permit(f,"reviewed-generic-recovery");var claims=rig.signer.verifyInvocation(signed);
        var scope=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(claims.nonce()));
        assertNotNull(scope);assertEquals(digest,scope.profileDigest());assertEquals(IMAGE,scope.imageDigest());
        // Removing review changes the pinned profile and refuses qualification for the next admission.
        profile.remove("effectReviews");String missing=Canonical.digest(profile);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            var deployment=(ObjectNode)JSON.valueToTree(rig.graph.codec.value(tx.packages().deployment("acme.test")));
            deployment.put("serviceProfileDigest",missing);tx.packages().saveDocument(missing,profile.toString());
            tx.packages().saveDeployment("acme.test",deployment.toString());return null;
        });
        var next=rig.signer.verifyInvocation(permit(f,"unreviewed-generic-recovery"));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(next.nonce())));
    }

    @Test void brokerOnlyApiKeyRoutesQualifyButWorkerReadableSecretDeclarationsCannot()throws Exception{
        brokerOnlyApiKey=true;
        var broker=fixture(true,true);var rig=broker.bridge().rig;
        var claims=rig.signer.verifyInvocation(permit(broker,"broker-api-key-scope"));
        assertNotNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(claims.nonce())));
        rawWorkerSecret=true;
        var unsafe=fixture(true,true);var other=unsafe.bridge().rig;
        var denied=other.signer.verifyInvocation(permit(unsafe,"worker-secret-scope"));
        assertNull(other.store.transaction(other.gateway.tenant(),false,tx->tx.enterprise().recoveryScope(denied.nonce())));
    }

}
