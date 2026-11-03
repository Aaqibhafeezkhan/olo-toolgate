// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Control decision/store/permit transitions, including revocation between admission and storage effect. */
class ServicePermitBridgeTest {
    @TempDir java.nio.file.Path temp;
    EnterpriseOperationTest rig;
    ServicePermits permits;
    static final String KEY="d".repeat(64);
    @BeforeAll static void key()throws Exception{EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup(){
        rig=new EnterpriseOperationTest();rig.temp=temp;rig.setup();
        String prefix="olo://"+EnterpriseConformanceTest.TENANT.value()+"/conversation/";
        var entries=new HashMap<Ids.RecordId,io.ololabs.toolgate.control.domain.Directory.Entry>();
        rig.graph.entries.forEach((id,e)->{
            var entry=rig.graph.codec.entry(id.kind(),e.document()
                .replace("\"read\"","\"memory.append\"").replace("\"write\"","\"memory.read\"")
                .replace("\"FILE\"","\"CUSTOM\"").replace("\"data\"","\""+prefix+"\"")
                .replace("\"tool\"","\"acme.test/tool\""));
            entries.put(entry.id(),entry);
        });
        rig.graph.entries=entries;rig.saveGraph();permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
    }
    EnterpriseInvocationRequest request(String id){
        var body=rig.graph.codec.model("{\"toolId\":\"acme.test/tool\",\"action\":\"memory.append\",\"arguments\":{\"path\":\"olo://"+rig.gateway.tenant().value()+"/conversation/c1\"}}",AuthorizationRequest.class);
        var context=new RequestContext(id,rig.gateway.tenant().value(),RequestMode.HUMAN,CredentialKind.STATIC_TOKEN,
            null,"alice",null,null,List.of(),1L,null,null,EnterpriseConformanceTest.NOW+15000,"binding-a",null);
        var tool=rig.graph.codec.model(rig.graph.entries.get(Ids.Kind.TOOL.id("acme.test/tool")).document(),ControlTool.class);
        return new EnterpriseInvocationRequest(context,body,new EnterpriseEvaluator(rig.graph.codec).toolDigest(rig.graph.directory(),tool),EnterpriseConformanceTest.DIGEST,null,null);
    }
    Store.Reply submit(EnterpriseInvocationRequest request){return rig.operations.submitHuman(new DirectoryService.Actor(rig.gateway.tenant(),"d".repeat(64),false,false,"alice"),request,"device");}
    EnterpriseInvocation start(String id){
        var i=rig.graph.codec.model(submit(request(id)).body(),EnterpriseInvocation.class);
        return rig.consume(rig.reserve(i));
    }
    ServicePermit legacyIssue(EnterpriseInvocation i){return rig.graph.codec.model(permits.issue(rig.gateway.tenant(),tx->"device",KEY,
        rig.graph.codec.json(new ServicePermitIssue(i.id(),(i.reservedNonce()==null?"not-admitted":i.reservedNonce()),ServiceAudience.olo_memory,null,null,null,null,null))).body(),ServicePermit.class);}
    ServicePermit issue(EnterpriseInvocation i){return issueBound(i,bound(i).digest());}
    ServicePermitConsumption consumption(ServicePermit p){var c=rig.signer.verifyService(p);return new ServicePermitConsumption(p,c.operation(),c.resources(),c.requestFingerprint(),KEY,c.httpRequestDigest());}
    record Bound(PreparedHttpRequestManifest manifest,SignedInvocationPermit proof,String digest) {}
    Bound bound(EnterpriseInvocation i){
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var fixtures=java.nio.file.Path.of(System.getProperty("toolgate.fixtures")).getParent().getParent().resolve("v2/valid.json");
            var corpus=json.readTree(java.nio.file.Files.readString(fixtures));
            var profile=(com.fasterxml.jackson.databind.node.ObjectNode)corpus.path("ServiceProfile").deepCopy();
            profile.put("profileId","sp-test").put("revision",1);
            var destination=(com.fasterxml.jackson.databind.node.ObjectNode)profile.path("destinations").get(0);
            destination.put("id","olo-memory").put("scheme","https").put("host","memory.test").put("port",443).put("mode","BROKERED");
            var route=(com.fasterxml.jackson.databind.node.ObjectNode)profile.path("routes").get(0);
            route.put("id","write").put("destination","olo-memory").put("method","POST").put("path","/v1/memory.append");
            route.putArray("headers").add("x-recipient");route.putArray("query").add("a").add("b");
            route.putArray("requestContentTypes").add("application/json");route.remove("auth");
            profile.putArray("privateRanges");
            String profileDigest=Canonical.digest(profile);
            rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.packages().saveDocument(profileDigest,profile.toString());
                tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease(i.id(),i.reservedNonce(),"device",i.expiresAtUnixMs()));return null;});
            var claims=(com.fasterxml.jackson.databind.node.ObjectNode)corpus.path("InvocationPermitClaims").deepCopy();
            claims.put("jti","proof-"+i.id()).put("iss","control").put("aud","device")
                .put("iat",EnterpriseConformanceTest.NOW).put("nbf",EnterpriseConformanceTest.NOW).put("exp",EnterpriseConformanceTest.NOW+1000)
                .put("nonce",i.reservedNonce()).put("invocationId",i.id()).put("deadlineUnixMs",i.expiresAtUnixMs())
                .put("argumentsDigest","sha256:"+i.evaluation().argumentsDigest()).put("action",i.evaluation().action());
            claims.set("resources",json.readTree(rig.graph.codec.json(i.evaluation().resources())));
            var identity=(com.fasterxml.jackson.databind.node.ObjectNode)claims.path("identity");
            identity.put("tenantId",i.evaluation().context().tenantId()).put("mode","HUMAN").put("userId","alice")
                .put("authorizationEpoch",i.authorizationEpoch());identity.remove("agentId");identity.putArray("chain");
            var target=(com.fasterxml.jackson.databind.node.ObjectNode)claims.path("target");
            target.put("toolId",i.evaluation().toolId()).put("toolDescriptorDigest","sha256:"+i.evaluation().toolDigest())
                .put("packageDigest","sha256:"+i.evaluation().packageDigest()).put("serviceProfileDigest",profileDigest).put("serviceProfileRevision",1);
            target.putArray("routes").add("write");
            var manifest=json.createObjectNode().put("version","toolgate-http-request-v1").put("profileId","sp-test")
                .put("profileDigest",profileDigest).put("routeId","write").put("scheme","https").put("host","memory.test").put("port",443)
                .put("method","POST").put("path","/v1/memory.append").put("query","").put("contentType","application/json")
                .put("bodyDigest","sha256:"+i.evaluation().argumentsDigest()).put("authDeclarationId","toolgate-service-permit:olo-memory");
            manifest.putArray("credentialHeaders").add("toolgate-service-permit");
            var headers=manifest.putObject("headers");headers.putArray("accept").add("*/*");headers.putArray("x-recipient").add("alice");
            return new Bound(rig.graph.codec.model(manifest.toString(),PreparedHttpRequestManifest.class),
                rig.signer.signInvocation(rig.graph.codec.model(claims.toString(),InvocationPermitClaims.class)),Canonical.arguments(manifest));
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    ServicePermit issueBound(EnterpriseInvocation i,String digest){
        var facts=bound(i);
        return rig.graph.codec.model(permits.issue(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,digest,facts.manifest(),facts.proof(),"broker-test","handler-test"))).body(),ServicePermit.class);
    }
    Store.Reply sending(ServicePermit permit,String broker,String handler){
        return permits.sending(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitDispatch(permit,broker,handler)));
    }
    static final String CLAIM_A="a".repeat(64),CLAIM_B="b".repeat(64),RESULT_DIGEST="sha256:"+"c".repeat(64);
    ServicePermitClaimResult claimConsume(ServicePermit p,String token){
        return rig.graph.codec.model(permits.consumeClaim(rig.gateway,ServiceAudience.olo_memory,
            rig.graph.codec.json(new ServicePermitClaimConsumption(consumption(p),token))).body(),ServicePermitClaimResult.class);
    }
    ServicePermitClaimReference reference(ServicePermit p,String token){return new ServicePermitClaimReference(rig.signer.verifyService(p).nonce(),token);}
    ServicePermitClaimResult claimStatus(ServicePermit p,String token){
        return rig.graph.codec.model(permits.claimStatus(rig.gateway,ServiceAudience.olo_memory,
            rig.graph.codec.json(reference(p,token))).body(),ServicePermitClaimResult.class);
    }
    ServicePermitClaimResult targetOutcome(ServicePermit p,String token,ServicePermitTargetOutcomeKind outcome){
        return rig.graph.codec.model(permits.targetOutcome(rig.gateway,ServiceAudience.olo_memory,
            rig.graph.codec.json(new ServicePermitTargetOutcome(reference(p,token),outcome,RESULT_DIGEST))).body(),ServicePermitClaimResult.class);
    }
    void claimReplayExercise(){
        var i=start("target-claim-"+UUID.randomUUID());var facts=bound(i);var permit=issueBound(i,facts.digest());
        var signed=rig.signer.verifyService(permit);assertNotNull(signed.effectId());assertEquals(1L,signed.authorizationGeneration().longValue());
        assertThrows(Failure.class,()->claimConsume(permit,CLAIM_A)); // no SENDING transition
        sending(permit,"broker-test","handler-test");
        var first=claimConsume(permit,CLAIM_A);assertEquals(ServicePermitClaimState.CONSUMED,first.status());
        assertEquals(signed.effectId(),first.effectId());assertEquals(signed.authorizationGeneration(),first.authorizationGeneration());
        assertEquals(first,claimConsume(permit,CLAIM_A));
        assertEquals(ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM,claimConsume(permit,CLAIM_B).status());
        assertEquals(ServicePermitClaimState.CONSUMED_BY_OTHER_CLAIM,claimStatus(permit,CLAIM_B).status());
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(permit))));
        assertThrows(Failure.class,()->issueBound(i,facts.digest()));
        assertThrows(Failure.class,()->targetOutcome(permit,CLAIM_B,ServicePermitTargetOutcomeKind.COMPLETED));
        assertThrows(Failure.class,()->permits.claimStatus(rig.gateway,ServiceAudience.olo_rag,rig.graph.codec.json(reference(permit,CLAIM_A))));
        assertEquals(ServicePermitClaimState.COMPLETED,targetOutcome(permit,CLAIM_A,ServicePermitTargetOutcomeKind.COMPLETED).status());
        assertEquals(ServicePermitClaimState.COMPLETED,targetOutcome(permit,CLAIM_A,ServicePermitTargetOutcomeKind.COMPLETED).status());
        assertEquals(ServicePermitClaimState.CONFLICT,targetOutcome(permit,CLAIM_A,ServicePermitTargetOutcomeKind.FAILED).status());
        assertEquals(ServicePermitClaimState.CONFLICT,claimConsume(permit,CLAIM_A).status());
    }
    @Test void targetClaimConsumptionReturnsStatusWithoutGrantingAnotherExecution(){claimReplayExercise();}
    @Test void revocationAndChangedFactsRefuseInitialClaimConsumption(){
        var i=start("claim-revoked");var facts=bound(i);var permit=issueBound(i,facts.digest());sending(permit,"broker-test","handler-test");
        var good=consumption(permit);
        for(var bad:List.of(
            new ServicePermitConsumption(permit,good.operation(),good.resources(),"e".repeat(64),KEY,good.httpRequestDigest()),
            new ServicePermitConsumption(permit,good.operation(),good.resources(),good.requestFingerprint(),"e".repeat(64),good.httpRequestDigest()),
            new ServicePermitConsumption(permit,good.operation(),good.resources(),good.requestFingerprint(),KEY,"sha256:"+"e".repeat(64)))){
            assertThrows(Failure.class,()->permits.consumeClaim(rig.gateway,ServiceAudience.olo_memory,
                rig.graph.codec.json(new ServicePermitClaimConsumption(bad,CLAIM_A))));
        }
        rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        assertThrows(Failure.class,()->claimConsume(permit,CLAIM_A));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(rig.signer.verifyService(permit).nonce()).consumer()));
    }
    @Test void expiredTerminatedClaimsAndRotatedSigningKeysDoNotLoseRecordedTargetOutcomes(){
        var i=start("late-claim");var permit=issueBound(i,bound(i).digest());sending(permit,"broker-test","handler-test");
        claimConsume(permit,CLAIM_A);
        rig.operations.report(rig.gateway.tenant(),"device",rig.graph.codec.json(new EnterpriseEffectReport(
            i.id(),i.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null)));
        var clock=java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW+120000),java.time.ZoneOffset.UTC);
        var late=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,clock,"control","toolgate-client",60000,100);
        permits=new ServicePermits(rig.store,rig.graph.codec,late);
        assertEquals(ServicePermitClaimState.AMBIGUOUS,claimConsume(permit,CLAIM_A).status());
        rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        EffectSigner rotated=new EffectSigner(){
            public EnterpriseSignedPermit sign(EnterprisePermitClaims claims){throw Failure.unavailable();}
            public EnterprisePermitClaims verify(EnterpriseSignedPermit permit){throw Failure.unavailable();}
        };
        permits=new ServicePermits(rig.store,rig.graph.codec,new EnterpriseOperations(rig.store,rig.graph.codec,rotated,clock,"control","toolgate-client",60000,100));
        assertThrows(Failure.class,()->claimConsume(permit,CLAIM_A));
        assertEquals(ServicePermitClaimState.AMBIGUOUS,claimStatus(permit,CLAIM_A).status());
        assertEquals(ServicePermitClaimState.COMPLETED,targetOutcome(permit,CLAIM_A,ServicePermitTargetOutcomeKind.COMPLETED).status());
        assertEquals(ServicePermitClaimState.COMPLETED,claimStatus(permit,CLAIM_A).status());
    }
    @Test void claimConsumptionAndOutcomesAlsoRunWithPostgresTenantRls()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL");
        String database="service_claims_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("CREATE DATABASE "+database);}
        String url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
        EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
        rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);claimReplayExercise();
    }
    @Test void legacyWritesRequireUpgradeAtIssuanceAndHistoricalConsumption(){
        var i=start("legacy-claim");
        var denied=assertThrows(Failure.class,()->legacyIssue(i));
        assertEquals(409,denied.status());
        assertEquals(ControlErrorDetailCode.BOUND_SERVICE_EFFECT_REQUIRED,denied.detail());
        var bound=issue(i);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var old=json.valueToTree(rig.signer.verifyService(bound));
        ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove(List.of("httpRequestDigest","effectId","authorizationGeneration"));
        var historical=rig.signer.signService(rig.graph.codec.model(old.toString(),ServicePermitClaims.class));
        var rejected=assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,
            rig.graph.codec.json(consumption(historical))));
        assertEquals(ControlErrorDetailCode.BOUND_SERVICE_EFFECT_REQUIRED,rejected.detail());
        assertThrows(Failure.class,()->claimConsume(historical,CLAIM_A));
        assertEquals("AUTHORIZED",rig.store.transaction(rig.gateway.tenant(),false,
            tx->tx.effects().byNonce(rig.signer.verifyService(bound).nonce()).state()));
    }
    @Test void legacyReadsStillIssueAndConsumeWithoutWriteAuthority(){
        var request=request("legacy-read");
        var read=new EnterpriseInvocationRequest(request.context(),
            new AuthorizationRequest(request.request().toolId(),"memory.read",request.request().arguments()),
            request.toolDigest(),request.packageDigest(),null,null);
        var queued=rig.graph.codec.model(submit(read).body(),EnterpriseInvocation.class);
        var running=rig.consume(rig.reserve(queued));
        var permit=legacyIssue(running);
        assertNull(rig.signer.verifyService(permit).httpRequestDigest());
        assertEquals(200,permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(permit))).status());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,
            tx->tx.effects().byNonce(rig.signer.verifyService(permit).nonce())));
    }
    @Test void preparedHttpDigestIsSignedImmutableAndRequiredForConsumption(){
        var i=start("http-bound");String digest=bound(i).digest();
        var permit=issueBound(i,digest);var claims=rig.signer.verifyService(permit);
        assertEquals(digest,claims.httpRequestDigest());assertEquals(permit,issueBound(i,digest));
        assertThrows(Failure.class,()->issueBound(i,"sha256:"+"b".repeat(64)));
        assertThrows(Failure.class,()->legacyIssue(i)); // no downgrade through the legacy issue request
        var good=consumption(permit);
        for(String bad:Arrays.asList(null,"sha256:"+"b".repeat(64))){
            assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,
                rig.graph.codec.json(new ServicePermitConsumption(permit,good.operation(),good.resources(),
                    good.requestFingerprint(),KEY,bad))));
        }
        assertEquals("AUTHORIZED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.nonce()).state()));
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(good)));
        sending(permit,"broker-test","handler-test");
        permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(good));
        assertEquals("CONSUMED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(claims.nonce()).state()));
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(good)));
    }
    @Test void malformedHttpDigestAndRevokedBoundGrantCannotAuthorize(){
        var i=start("http-revoked");
        assertThrows(Failure.class,()->issueBound(i,"not-a-digest"));
        var permit=issueBound(i,bound(i).digest());
        sending(permit,"broker-test","handler-test");
        rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(permit))));
    }
    @Test void independentManifestValidationRejectsChangedDestinationsAndUndeclaredScope(){
        var i=start("manifest-attacks");var facts=bound(i);
        assertThrows(Failure.class,()->legacyIssue(i)); // v2 leases cannot opt out even before the first bound grant
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var baseline=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(facts.manifest());
        var mutations=new LinkedHashMap<String,java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>();
        mutations.put("host",m->m.put("host","evil.test"));mutations.put("port",m->m.put("port",8443));
        mutations.put("scheme",m->m.put("scheme","http"));mutations.put("route",m->m.put("routeId","other"));
        mutations.put("profile",m->m.put("profileId","other"));mutations.put("digest",m->m.put("profileDigest","sha256:"+"a".repeat(64)));
        mutations.put("method",m->m.put("method","DELETE"));mutations.put("path",m->m.put("path","/v1/memory.delete"));
        mutations.put("query",m->m.put("query","admin=true"));mutations.put("queryOrder",m->m.put("query","b=1&a=2"));
        mutations.put("queryEncoding",m->m.put("query","%61=2"));mutations.put("queryUtf8",m->m.put("query","a=%FF"));
        mutations.put("queryNonCanonicalValue",m->m.put("query","a=+"));
        mutations.put("body",m->m.put("bodyDigest","sha256:"+"f".repeat(64)));
        mutations.put("contentType",m->m.put("contentType","text/plain"));
        mutations.put("auth",m->m.put("authDeclarationId","other"));mutations.put("credentials",m->m.putArray("credentialHeaders").add("x-recipient"));
        mutations.put("header",m->((com.fasterxml.jackson.databind.node.ObjectNode)m.path("headers")).putArray("x-extra").add("value"));
        mutations.put("forwarded",m->((com.fasterxml.jackson.databind.node.ObjectNode)m.path("headers")).putArray("x-forwarded-host").add("evil.test"));
        mutations.put("headerCase",m->((com.fasterxml.jackson.databind.node.ObjectNode)m.path("headers")).putArray("X-Recipient").add("alice"));
        mutations.put("headerDuplicate",m->((com.fasterxml.jackson.databind.node.ObjectNode)m.path("headers")).putArray("x-recipient").add("alice").add("bob"));
        mutations.put("acceptMissing",m->((com.fasterxml.jackson.databind.node.ObjectNode)m.path("headers")).remove("accept"));
        for(var entry:mutations.entrySet()){
            var changed=baseline.deepCopy();entry.getValue().accept(changed);
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new ServicePermitIssue(i.id(),i.reservedNonce(),
                ServiceAudience.olo_memory,Canonical.arguments(changed),facts.manifest(),facts.proof(),"broker-test","handler-test"));
            request.set("httpRequestManifest",changed);
            assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"device",KEY,request.toString()),entry.getKey());
        }
        String nonce="service-"+DirectoryService.digest(i.id()+"\n"+ServiceAudience.olo_memory).substring(0,48);
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().nonce(nonce)));
        assertNotNull(issueBound(i,facts.digest()));
    }
    @Test void missingOrForgedAdmissionProofCannotMintBoundAuthority(){
        var i=start("manifest-proof");var facts=bound(i);
        for(var proof:Arrays.asList(null,new SignedInvocationPermit(facts.proof().jws()+"corrupt"))){
            assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(
                new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,facts.digest(),facts.manifest(),proof,"broker-test","handler-test"))));
        }
        var other=start("manifest-other");
        assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(
            new ServicePermitIssue(other.id(),other.reservedNonce(),ServiceAudience.olo_memory,facts.digest(),facts.manifest(),facts.proof(),"broker-test","handler-test"))));
    }
    @Test void consumedAdmissionWindowMayExpireWhileCurrentExecutionAuthorityRemainsLive(){
        var i=start("manifest-live-lease");var facts=bound(i);
        var later=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW+2000),java.time.ZoneOffset.UTC),"control","toolgate-client",60000,100);
        var bridge=new ServicePermits(rig.store,rig.graph.codec,later);
        var permit=rig.graph.codec.model(bridge.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(
            new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,facts.digest(),facts.manifest(),facts.proof(),"broker-test","handler-test"))).body(),ServicePermit.class);
        assertEquals(facts.digest(),rig.signer.verifyService(permit).httpRequestDigest());
    }
    @Test void pathConstraintsAreBoundedAndUnsupportedExpressionsFailClosed(){
        var i=start("manifest-pattern");var facts=bound(i);
        String raw=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.packages().document(facts.manifest().profileDigest()));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var profile=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(rig.graph.codec.model(raw,ServiceProfile.class));
        var route=(com.fasterxml.jackson.databind.node.ObjectNode)profile.path("routes").get(0);
        for(String path:List.of("/v1/{operation}","/v1/{operation:[a-z.]+}")){
            route.put("path",path);
            ServiceRequestValidation.validateManifest(rig.graph.codec.model(profile.toString(),ServiceProfile.class),
                facts.manifest(),ServiceAudience.olo_memory,"memory.append",facts.manifest().bodyDigest());
        }
        for(String path:List.of("/v1/{operation:(a+)+}","/v1/{operation:[z-a]+}","/v1/delete")){
            route.put("path",path);
            assertThrows(Failure.class,()->ServiceRequestValidation.validateManifest(rig.graph.codec.model(profile.toString(),ServiceProfile.class),
                facts.manifest(),ServiceAudience.olo_memory,"memory.append",facts.manifest().bodyDigest()),path);
        }
    }
    @Test void signedRouteScopeRevisionAndPinnedProfileIntegrityAreMandatory()throws Exception{
        var i=start("manifest-profile-integrity");var facts=bound(i);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var original=rig.signer.verifyInvocation(facts.proof());
        for(String variant:List.of("revision","routes","integrity")){
            var claims=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(original);
            var target=(com.fasterxml.jackson.databind.node.ObjectNode)claims.path("target");
            var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(facts.manifest());
            if(variant.equals("revision"))target.put("serviceProfileRevision",2);
            if(variant.equals("routes"))target.putArray("routes");
            if(variant.equals("integrity")){
                String fake="sha256:"+"f".repeat(64);
                rig.store.transaction(rig.gateway.tenant(),true,tx->{
                    tx.packages().saveDocument(fake,tx.packages().document(facts.manifest().profileDigest()));return null;});
                target.put("serviceProfileDigest",fake);manifest.put("profileDigest",fake);
            }
            var proof=rig.signer.signInvocation(rig.graph.codec.model(claims.toString(),InvocationPermitClaims.class));
            var request=new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,Canonical.arguments(manifest),
                rig.graph.codec.model(manifest.toString(),PreparedHttpRequestManifest.class),proof,"broker-test","handler-test");
            assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(request)),variant);
        }
    }
    @Test void admissionJcsAndLegacyExactBodyDigestDomainsAreNotConfused()throws Exception{
        var original=request("manifest-number");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var args=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rig.graph.codec.json(original.request().arguments()));
        args.put("value",1.0);
        var authorization=rig.graph.codec.model("{\"toolId\":\"acme.test/tool\",\"action\":\"memory.append\",\"arguments\":"+args+"}",AuthorizationRequest.class);
        var submitted=rig.graph.codec.model(submit(new EnterpriseInvocationRequest(original.context(),authorization,
            original.toolDigest(),original.packageDigest(),null,null)).body(),EnterpriseInvocation.class);
        var i=rig.consume(rig.reserve(submitted));var facts=bound(i);
        var claims=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(rig.signer.verifyInvocation(facts.proof()));
        String jcs=Canonical.arguments(args);assertNotEquals("sha256:"+i.evaluation().argumentsDigest(),jcs);
        claims.put("argumentsDigest",jcs);
        var proof=rig.signer.signInvocation(rig.graph.codec.model(claims.toString(),InvocationPermitClaims.class));
        assertEquals(200,permits.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(new ServicePermitIssue(
            i.id(),i.reservedNonce(),ServiceAudience.olo_memory,facts.digest(),facts.manifest(),proof,"broker-test","handler-test"))).status());
    }
    @Test void onlyTheRecordedHandlerCanBeginOneWireDispatch()throws Exception{
        var i=start("owned-dispatch");var facts=bound(i);var permit=issueBound(i,facts.digest());
        for(String field:List.of("broker","handler")){
            var request=new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,facts.digest(),facts.manifest(),facts.proof(),
                field.equals("broker")?"another-broker":"broker-test",field.equals("handler")?"another-handler":"handler-test");
            assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"device",KEY,rig.graph.codec.json(request)));
        }
        assertThrows(Failure.class,()->sending(permit,"another-broker","handler-test"));
        assertThrows(Failure.class,()->sending(permit,"broker-test","another-handler"));
        assertThrows(Failure.class,()->permits.sending(rig.gateway.tenant(),tx->"device","e".repeat(64),
            rig.graph.codec.json(new ServicePermitDispatch(permit,"broker-test","handler-test"))));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var ready=new java.util.concurrent.CountDownLatch(2);var begin=new java.util.concurrent.CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<2;n++)futures.add(pool.submit(()->{
                ready.countDown();try{begin.await();sending(permit,"broker-test","handler-test");count.incrementAndGet();}
                catch(Failure denied){assertTrue(Set.of(403,409).contains(denied.status()));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            }));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));begin.countDown();
            for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,count.get());
        assertThrows(Failure.class,()->sending(permit,"broker-test","handler-test"));
        assertThrows(Failure.class,()->issueBound(i,facts.digest()));
        assertEquals("SENDING",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(rig.signer.verifyService(permit).nonce()).state()));
        permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(permit)));
    }
    @Test void ownerLossAndRevocationNeverCreateFreshDispatchAuthority(){
        var i=start("owner-revocation");var facts=bound(i);var permit=issueBound(i,facts.digest());
        rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        assertThrows(Failure.class,()->sending(permit,"broker-test","handler-test"));
        assertEquals("AUTHORIZED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(rig.signer.verifyService(permit).nonce()).state()));
    }
    @Test void admittedIdentityScopeAndBodyReachTheServiceAndTheNonceIsSingleUse(){
        var i=start("service-write");var p=issue(i);assertEquals(p,issue(i));var c=rig.signer.verifyService(p);
        assertEquals("alice",c.sub());assertNull(c.act());assertEquals(rig.gateway.tenant().value(),c.tenantId());
        assertEquals(i.evaluation().resources(),c.resources());assertEquals(i.evaluation().argumentsDigest(),c.requestFingerprint());
        assertEquals(15000,c.exp()-c.iat());
        sending(p,"broker-test","handler-test");
        permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p)));
        assertEquals("CONSUMED",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(c.nonce()).state()));
        assertEquals(1,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().attempts(i.id(),EnterpriseConformanceTest.NOW).size()).intValue());
        assertThrows(Failure.class,()->issue(i));
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
    }
    @Test void revocationAfterIssuanceStopsTheStorageEffect(){
        var p=issue(start("service-revoked"));sending(p,"broker-test","handler-test");rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
    }
    @Test void forgedBodyAudienceExecutorAndScopeAreRefused(){
        var p=issue(start("service-forgery"));sending(p,"broker-test","handler-test");var good=consumption(p);
        for(var bad:List.of(new ServicePermitConsumption(p,good.operation(),good.resources(),"e".repeat(64),KEY,null),
            new ServicePermitConsumption(p,good.operation(),good.resources(),good.requestFingerprint(),"e".repeat(64),null),
            new ServicePermitConsumption(p,"memory.delete",good.resources(),good.requestFingerprint(),KEY,null))){
            assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(bad)));
        }
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_rag,rig.graph.codec.json(good)));
        assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"other",KEY,rig.graph.codec.json(new ServicePermitIssue("service-forgery","wrong-fence",ServiceAudience.olo_memory,null,null,null,null,null))));
    }
    @Test void queuedAndApprovalCallsCannotObtainAPermit(){
        var queued=rig.graph.codec.model(submit(request("queued")).body(),EnterpriseInvocation.class);
        assertThrows(Failure.class,()->issue(queued));
        rig.graph.policy("ask",Decision.ASK);rig.saveGraph();
        var waiting=rig.graph.codec.model(submit(request("waiting")).body(),EnterpriseInvocation.class);
        assertThrows(Failure.class,()->issue(waiting));
    }
    @Test void cancelledExecutionCannotConsumeItsPreviouslyIssuedPermit(){
        var i=start("cancel-running");var p=issue(i);
        rig.operations.cancelFromGateway(rig.gateway,rig.graph.codec.json(request(i.id()).context()));
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(i.id())).state());
    }
    @Test void downstreamPermitsNeverExtendTheOriginalUserCredentialDeadline(){
        var i=start("deadline");
        var later=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW+14900),java.time.ZoneOffset.UTC),"control","toolgate-client",60000,100);
        var bridge=new ServicePermits(rig.store,rig.graph.codec,later);
        var facts=bound(i);
        var p=rig.graph.codec.model(bridge.issue(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,
                facts.digest(),facts.manifest(),facts.proof(),"broker-test","handler-test"))).body(),ServicePermit.class);
        var c=rig.signer.verifyService(p);
        assertEquals(EnterpriseConformanceTest.NOW+15000,c.exp());assertEquals(100,c.exp()-c.iat());
        var expired=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW+15001),java.time.ZoneOffset.UTC),"control","toolgate-client",60000,100);
        var stopped=new ServicePermits(rig.store,rig.graph.codec,expired);
        assertThrows(Failure.class,()->stopped.issue(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory,null,null,null,null,null))));
        assertThrows(Failure.class,()->stopped.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
    }

    @Test void cancellationBeforeSubmissionIsDurableAndNeverCreatesAnEffect(){
        var request=request("cancel-before-submit");
        rig.operations.cancelFromGateway(rig.gateway,rig.graph.codec.json(request.context()));
        assertThrows(Failure.class,()->submit(request));
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().invocation(request.context().requestId())));
        assertEquals(Boolean.TRUE,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().operationCancelled(request.context().requestId())));
    }
}
