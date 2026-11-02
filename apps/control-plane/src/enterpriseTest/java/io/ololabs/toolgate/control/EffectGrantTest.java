// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SQLite transactions, current authority, immutable intents and signed single-send grants. */
class EffectGrantTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path temp;
    ServicePermitBridgeTest base;EffectGrants grants;EnterpriseInvocation invocation;EffectGrantIssue issue;
    ObjectNode profile;String packageDigest;
    @BeforeAll static void key()throws Exception{ServicePermitBridgeTest.key();}
    @BeforeEach void setup()throws Exception{
        base=new ServicePermitBridgeTest();base.temp=temp;base.setup();prepare();
    }
    void prepare()throws Exception{
        invocation=base.start("generic-effect");
        grants=new EffectGrants(base.rig.store,base.rig.graph.codec,base.rig.operations);
        var facts=base.bound(invocation);var codec=base.rig.graph.codec;
        var claims=(ObjectNode)JSON.readTree(codec.json(base.rig.signer.verifyInvocation(facts.proof())));
        profile=base.rig.store.transaction(base.rig.gateway.tenant(),false,tx->{
            try{return (ObjectNode)JSON.readTree(tx.packages().document(facts.manifest().profileDigest()));}
            catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        });
        var destination=(ObjectNode)profile.path("destinations").get(0);destination.put("id","external").put("host","external.example.test");
        var route=(ObjectNode)profile.path("routes").get(0);route.put("destination","external").put("path","/send").put("streaming","NONE");
        var policy=JSON.createObjectNode().put("routeId","write").put("classification","WRITE")
            .put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
        var intent=policy.putObject("intent").put("path","/send").put("contentType","application/json").put("bodyMode","CANONICAL_JSON");
        intent.putObject("query");intent.putObject("headers");intent.putObject("body").putObject("path").put("$arg","/path");
        profile.putArray("effectReviews").addObject().put("reviewedBy","admin").put("reviewedAtUnixMs",EnterpriseConformanceTest.NOW).set("policy",policy);
        String profileDigest=Canonical.digest(profile);packageDigest=claims.path("target").path("packageDigest").asText();
        ((ObjectNode)claims.path("target")).put("serviceProfileDigest",profileDigest);
        var manifest=(ObjectNode)JSON.readTree(codec.json(facts.manifest()));
        manifest.put("host","external.example.test").put("path","/send").put("profileDigest",profileDigest).putNull("authDeclarationId");
        manifest.putArray("credentialHeaders");manifest.putObject("headers").putArray("accept").add("*/*");
        var args=JSON.createObjectNode().put("path","olo://"+base.rig.gateway.tenant().value()+"/conversation/c1");
        manifest.put("bodyDigest",Canonical.arguments(args));
        var packageDoc=JSON.createObjectNode();var descriptor=packageDoc.putObject("manifest").putArray("tools").addObject().putObject("descriptor");
        descriptor.put("toolId",invocation.evaluation().toolId()).putArray("httpAuth");descriptor.putObject("effect").put("safety","NON_IDEMPOTENT");
        base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{
            tx.packages().saveDocument(profileDigest,profile.toString());
            tx.packages().saveVersion(new PackageStore.Version(packageDigest,profile.path("packageId").asText(),"1.0.0",EnterpriseConformanceTest.NOW,packageDoc.toString()),new byte[0]);
            tx.packages().saveDeployment(profile.path("packageId").asText(),JSON.createObjectNode().put("packageDigest",packageDigest).put("serviceProfileDigest",profileDigest).toString());
            return null;
        });
        issue=new EffectGrantIssue(invocation.id(),invocation.reservedNonce(),base.rig.signer.signInvocation(codec.model(claims.toString(),InvocationPermitClaims.class)),
            "broker","handler",Map.of("path",args.path("path")),Canonical.arguments(manifest),codec.model(manifest.toString(),PreparedHttpRequestManifest.class));
    }
    EffectGrant issue(){
        return base.rig.graph.codec.model(grants.issue(base.rig.gateway.tenant(),tx->"device",base.rig.graph.codec.json(issue)).body(),EffectGrant.class);
    }
    Store.Reply send(EffectGrant grant,String owner){
        return grants.sending(base.rig.gateway.tenant(),tx->"device",base.rig.graph.codec.json(new EffectGrantDispatch(grant.grant(),"broker",owner)));
    }
    @Test void persistBeforeGrantAndOneOwnerCanSendOnlyOnce(){
        var grant=issue();var claims=base.rig.signer.verifyEffect(grant.grant());
        assertEquals(2000,grant.validForMs());assertEquals(2000,claims.exp()-claims.iat());
        var intent=base.rig.store.transaction(base.rig.gateway.tenant(),false,tx->tx.effects().intent(claims.effectId()));
        assertEquals(issue.httpRequestDigest(),intent.requestDigest());
        assertEquals(issue.httpRequestManifest(),base.rig.graph.codec.model(intent.document(),PreparedHttpRequestManifest.class));
        assertThrows(Failure.class,()->send(grant,"other-handler"));
        assertEquals(200,send(grant,"handler").status());
        assertThrows(Failure.class,()->send(grant,"handler"));
        assertThrows(Failure.class,this::issue);
    }
    @Test void losingIssuanceReplyCannotMintASecondSend(){
        var first=issue();assertThrows(Failure.class,this::issue);
        assertEquals(200,send(first,"handler").status());
    }
    @Test void wrongCertificateAndChangedArgumentsOrBodyCannotMintAuthority()throws Exception{
        assertThrows(Failure.class,()->grants.issue(base.rig.gateway.tenant(),tx->"other-device",base.rig.graph.codec.json(issue)));
        var changed=(ObjectNode)JSON.readTree(base.rig.graph.codec.json(issue));
        changed.putObject("arguments").put("path","olo://other/conversation/stolen");
        var alteredArguments=changed.toString();
        assertThrows(Failure.class,()->grants.issue(base.rig.gateway.tenant(),tx->"device",alteredArguments));
        changed=(ObjectNode)JSON.readTree(base.rig.graph.codec.json(issue));
        ((ObjectNode)changed.path("httpRequestManifest")).put("bodyDigest","sha256:"+"a".repeat(64));
        changed.put("httpRequestDigest",Canonical.arguments(changed.path("httpRequestManifest")));
        var body=changed.toString();
        assertThrows(Failure.class,()->grants.issue(base.rig.gateway.tenant(),tx->"device",body));
        assertNotNull(issue());
    }
    @Test void consentIsNotInferredFromAdmissionApproval()throws Exception{
        var changed=(ObjectNode)JSON.readTree(base.rig.graph.codec.json(issue));
        var rawProfile=profile.deepCopy();
        ((ObjectNode)rawProfile.path("effectReviews").get(0).path("policy")).put("consentRequired",true);
        var digest=Canonical.digest(rawProfile);
        var proof=(ObjectNode)JSON.readTree(base.rig.graph.codec.json(base.rig.signer.verifyInvocation(issue.invocationPermit())));
        ((ObjectNode)proof.path("target")).put("serviceProfileDigest",digest);
        changed.set("invocationPermit",JSON.readTree(base.rig.graph.codec.json(base.rig.signer.signInvocation(base.rig.graph.codec.model(proof.toString(),InvocationPermitClaims.class)))));
        ((ObjectNode)changed.path("httpRequestManifest")).put("profileDigest",digest);
        changed.put("httpRequestDigest",Canonical.arguments(changed.path("httpRequestManifest")));
        base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{tx.packages().saveDocument(digest,rawProfile.toString());return null;});
        assertThrows(Failure.class,()->grants.issue(base.rig.gateway.tenant(),tx->"device",changed.toString()));
    }
    @Test void redeployBetweenIssueAndSendRevokesProfileAuthority(){
        var grant=issue();
        base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{
            tx.packages().saveDeployment(profile.path("packageId").asText(),JSON.createObjectNode()
                .put("packageDigest",packageDigest).put("serviceProfileDigest","sha256:"+"a".repeat(64)).toString());return null;
        });
        assertThrows(Failure.class,()->send(grant,"handler"));assertThrows(Failure.class,this::issue);
    }
    @Test void revocationAndExpiredLeaseRefuseSending(){
        var grant=issue();
        base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{
            tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease(invocation.id(),invocation.reservedNonce(),"device",EnterpriseConformanceTest.NOW));return null;
        });
        assertThrows(Failure.class,()->send(grant,"handler"));assertThrows(Failure.class,this::issue);
    }
    @Test void jwsTypesAreNotInterchangeable(){
        var grant=issue();
        assertThrows(Failure.class,()->base.rig.signer.verifyInvocation(new SignedInvocationPermit(grant.grant().jws())));
        assertThrows(Failure.class,()->base.rig.signer.verifyService(new ServicePermit(grant.grant().jws())));
        assertThrows(Failure.class,()->base.rig.signer.verifyEffect(new SignedEffectGrant(issue.invocationPermit().jws())));
    }
    @Test void concurrentIssuersAndSendersHaveExactlyOneWinner()throws Exception{
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var issued=pool.invokeAll(List.<java.util.concurrent.Callable<EffectGrant>>of(
                ()->{try{return issue();}catch(Failure refused){return null;}},
                ()->{try{return issue();}catch(Failure refused){return null;}}));
            var grantsIssued=new ArrayList<EffectGrant>();
            for(var task:issued)if(task.get()!=null)grantsIssued.add(task.get());
            assertEquals(1,grantsIssued.size());var grant=grantsIssued.getFirst();
            var sent=pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(
                ()->{try{send(grant,"handler");return true;}catch(Failure refused){return false;}},
                ()->{try{send(grant,"handler");return true;}catch(Failure refused){return false;}}));
            int winners=0;for(var task:sent)if(task.get())winners++;assertEquals(1,winners);
        }finally{pool.shutdownNow();}
    }
    @Test void postgresRuntimeRoleRetainsIntentsAndSingleSendRaces()throws Exception{
        String url=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(url!=null&&password!=null,"Requires isolated PostgreSQL");
        String database="generic_effects_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var sql=c.createStatement()){sql.execute("CREATE DATABASE "+database);}
        url=url.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        base.rig.store=new io.ololabs.toolgate.control.adapter.PostgresStore(
            io.ololabs.toolgate.control.fixtures.Tenants.database(source),base.rig.graph.codec);
        base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{tx.save(tx.load(),base.rig.graph.directory());return null;});
        EnterpriseConformanceTest.online(base.rig.store,base.rig.graph.codec,"device");
        base.rig.operations=new EnterpriseOperations(base.rig.store,base.rig.graph.codec,base.rig.signer,base.rig.clock,
            "control","toolgate-client",60000,100);
        prepare();concurrentIssuersAndSendersHaveExactlyOneWinner();
        var effectId="eff-"+DirectoryService.digest(base.rig.graph.codec.json(List.of("toolgate-generic-effect-v1",
            base.rig.gateway.tenant().value(),invocation.id(),issue.httpRequestManifest().routeId())));
        var retained=base.rig.store.transaction(base.rig.gateway.tenant(),false,tx->tx.effects().intent(effectId));
        assertNotNull(retained);assertEquals(issue.httpRequestDigest(),retained.requestDigest());
        assertThrows(Failure.class,()->base.rig.store.transaction(base.rig.gateway.tenant(),true,tx->{
            tx.effects().recordIntent(new EffectStore.Intent(effectId,retained.requestDigest(),retained.argumentsDigest(),
                retained.profileDigest(),"READ",retained.document(),retained.recordedAt()));return null;
        }));
        assertEquals(retained,base.rig.store.transaction(base.rig.gateway.tenant(),false,tx->tx.effects().intent(effectId)));
    }

}
