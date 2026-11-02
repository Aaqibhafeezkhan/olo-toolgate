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
    private boolean postgres;
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
        rig.operations.consumeInvocation(rig.gateway.tenant(),tx->"device",consumption.toString());
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
                rig.graph.codec.model(manifest.toString(),PreparedHttpRequestManifest.class),signed,"broker-test",handler);
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
                        request.toolDigest(),request.packageDigest(),request.downstreamIdempotencyKey(),request.executionContext());
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
        assertEquals(failed,replay(unqualified,rig.operations,failed.id()));
        var qualified=fixture(true,false);var r=qualified.bridge().rig;
        var signed=permit(qualified,"public-sent");consume(qualified,signed);
        var write=grant(qualified,signed,"sent-handler");qualified.bridge().sending(write,"broker-test","sent-handler");
        var uncertain=reportFailure(qualified,signed);
        assertEquals(uncertain,replay(qualified,r.operations,uncertain.id()));
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
    @Test void lateReceiptsCannotReplaceTheRecoveredDispatchOrItsCurrentOutcome() throws Exception {
        var fixture=fixture(true,false);var rig=fixture.bridge().rig;
        var signed=permit(fixture,"scope-receipt");var claims=rig.signer.verifyInvocation(signed);
        var failed=failAfterConsumption(fixture,signed);
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.enterprise().rebind(failed,
                OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,rig.clock.millis()));
        var device=new EndpointDeviceRecord("device",rig.gateway.tenant().value(),"alice","f".repeat(64),
                EndpointState.ACTIVE,1L,0L,0L,null,null,true,1L,null,null);
        var late=JSON.createObjectNode().put("invocationId",failed.id()).put("fencingToken",claims.nonce()).put("outcome","COMPLETED");
        late.set("result",JSON.readTree("{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"isError\":false}"));
        var receipt=fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),late.toString());
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
        fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),late.toString());
        rig.operations.reportInvocation(rig.gateway.tenant(),tx->"device",late.toString(),claims.nonce());
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(failed.id())).state());
        var current=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().relayed(failed.id()));
        assertEquals("ADMITTED",current.state());assertFalse(JSON.readTree(current.document()).path("status").has("result"));
        var attempts=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(failed.id(),rig.clock.millis()));
        assertEquals(2,attempts.size());assertEquals(2L,attempts.getFirst().evidenceCount());assertEquals(0L,attempts.getLast().evidenceCount());
        late.put("fencingToken","not-an-issued-attempt");
        assertEquals(403,assertThrows(Failure.class,()->fixture.packages().result(rig.gateway.tenant(),tx->device,failed.id(),late.toString())).status());
    }
    @Test void signedScopeAndLeaseRecoveryAlsoHoldUnderThePostgresRuntimeRole() throws Exception {
        Assumptions.assumeTrue(System.getenv("CONTROL_TEST_URL")!=null&&System.getenv("CONTROL_TEST_PASSWORD")!=null,
                "Requires isolated PostgreSQL fixture");
        postgres=true;
        realSignedAdmissionCapturesScopeBeforeConsumptionAndBindsEverySignedDigest();
        anExecutorTierReportCannotEnableRecoveryWithoutTheIndependentOperatorPin();
        aPinnedOciExecutorWithAnOrdinaryHttpRouteStillCannotQualify();
        lateReceiptsCannotReplaceTheRecoveredDispatchOrItsCurrentOutcome();
        onlyAProvenUnsentGenerationCanReceiveFreshWriteAuthority();
        aRecoveredGenerationCanCompleteOnlyOnceThroughTheTargetClaimSdk();
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
}
