// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseOperationTest {
    @TempDir java.nio.file.Path temp;
    static java.security.interfaces.RSAPrivateCrtKey key;
    EnterpriseConformanceTest graph;PostgresStore store;EnterpriseOperations operations;EffectSigner signer;Clock clock;
    DirectoryService.Actor gateway,reviewer;
    javax.sql.DataSource keyPolicySource;
    @BeforeAll static void signingKey()throws Exception{var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=(java.security.interfaces.RSAPrivateCrtKey)generator.generateKeyPair().getPrivate();}
    @BeforeEach void setup(){
        graph=new EnterpriseConformanceTest();graph.graph();clock=Clock.fixed(Instant.ofEpochMilli(EnterpriseConformanceTest.NOW),ZoneOffset.UTC);
        var rules=List.of(new EnterpriseManagementRule(List.of("operation.approve","operation.read","operation.cancel"),EnterpriseGroupType.TOOL_GROUP,new GroupSelection(List.of("tools-a"),false),List.of(),graph.conditions(),null),new EnterpriseManagementRule(List.of("operation.approve","operation.read","operation.cancel"),EnterpriseGroupType.DEVICE_GROUP,new GroupSelection(List.of("devices-a"),false),List.of(),graph.conditions(),null));
        graph.add(Kind.ROLE,new ControlRole("reviewer-role","Reviewer",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));
        graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,1L,List.of("alice","admin"),List.of("reviewer-role")));
        keyPolicySource=SqliteState.open(temp.resolve("operations.sqlite"));store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(keyPolicySource),graph.codec);store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});EnterpriseConformanceTest.online(store,graph.codec,"device");
        signer=new RsaEffectSigner(key,"permit-key",graph.codec);operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        gateway=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"a".repeat(64),false);reviewer=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"c".repeat(64),true,false,"admin");
    }
    void saveGraph(){store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{var d=tx.load();tx.save(d,new Directory(d.revision()+1,graph.entries));return null;});}
    EnterpriseInvocationRequest request(String id,String path){var context=new RequestContext(id,EnterpriseConformanceTest.TENANT.value(),RequestMode.DELEGATED,CredentialKind.STATIC_TOKEN,null,"alice","agent","workload",List.of(),1L,1L,null,null,"binding-a",EnterpriseConformanceTest.PROOF);var request=graph.codec.model("{\"toolId\":\"tool\",\"action\":\"read\",\"arguments\":{\"path\":\""+path+"\"}}",AuthorizationRequest.class);var tool=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);return new EnterpriseInvocationRequest(context,request,new EnterpriseEvaluator(graph.codec).toolDigest(graph.directory(),tool),EnterpriseConformanceTest.DIGEST,null,null);}
    EnterpriseInvocation submit(String id){return graph.codec.model(operations.submit(gateway,graph.codec.json(request(id,"data/report.txt"))).body(),EnterpriseInvocation.class);}
    EnterpriseReservation reserve(EnterpriseInvocation i){return graph.codec.model(operations.reserve(gateway,graph.codec.json(new EnterpriseReservationRequest(i.id(),i.revision()))).body(),EnterpriseReservation.class);}
    String consumption(EnterpriseReservation r){var e=r.invocation().evaluation();return graph.codec.json(new EnterprisePermitConsumption(r.invocation().id(),r.permit(),e.argumentsDigest(),e.resources(),e.toolDigest(),e.packageDigest()));}
    EnterpriseInvocation consume(EnterpriseReservation r){return graph.codec.model(operations.consume(EnterpriseConformanceTest.TENANT,"device",consumption(r)).body(),EnterpriseInvocation.class);}
    EnterpriseApproval approval(EnterpriseInvocation i){return store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().approval("operation-"+DirectoryService.digest(i.id()).substring(0,40)));}
    EnterpriseApproval approve(EnterpriseApproval a,String obligation){return graph.codec.model(operations.decide(reviewer,a.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(a.revision(),obligation,EnterpriseReviewDecision.APPROVE)),"review-"+obligation).body(),EnterpriseApproval.class);}

    private HumanRuntime completedHumanHostCall() {
        var original=request("human-host", "data/report.txt");
        var context=new RequestContext("human-host",gateway.tenant().value(),RequestMode.HUMAN,CredentialKind.STATIC_TOKEN,
                null,"alice",null,null,List.of(),1L,null,null,null,"binding-a",null);
        var alice=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        var i=graph.codec.model(operations.submitHuman(alice,new EnterpriseInvocationRequest(context,original.request(),
                original.toolDigest(),original.packageDigest(),null,null),"device").body(),EnterpriseInvocation.class);
        var finished=new EnterpriseInvocation(i.id(),i.requestDigest(),i.evaluation(),EnterpriseInvocationState.SUCCEEDED,
                i.revision()+1,i.authorizationEpoch(),"host-fence",i.expiresAtUnixMs(),i.evaluation().resources(),
                i.downstreamIdempotencyKey(),EnterpriseConformanceTest.DIGEST,i.diagnosticId(),i.reconciliation());
        store.transaction(gateway.tenant(),true,tx->{
            tx.enterprise().saveInvocation(finished,clock.millis(),i.revision());tx.packages().saveToolHost("device","{}");
            tx.packages().saveRelayed(new PackageStore.Relayed(i.id(),"device","FINISHED",i.expiresAtUnixMs(),
                "{\"status\":{\"state\":\"FINISHED\",\"result\":{\"invocationId\":\"human-host\",\"outcome\":\"COMPLETED\",\"result\":{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"structuredContent\":{\"message\":\"Hello World\"},\"isError\":false}}}}"));
            return null;
        });
        return new HumanRuntime(store,graph.codec,operations,null,new ServerPackages(store,graph.codec,operations,clock));
    }

    @Test void humanHostResultUsesTheDispatchStoreAndPreservesLiveCallerAuthority() {
        var runtime=completedHumanHostCall();
        var alice=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        var response=graph.codec.model(runtime.result(alice,1L,"human-host").body(),RemoteToolResponse.class);
        assertEquals(RemoteToolState.DONE,response.record().state());
        assertEquals("Hello World",response.result().output().get("message").asText());
        assertEquals(403,assertThrows(Failure.class,()->runtime.result(alice,2L,"human-host")).status());
        assertEquals(403,assertThrows(Failure.class,()->runtime.result(reviewer,1L,"human-host")).status());
        graph.remove(Kind.GRANT,"human");saveGraph();
        assertEquals(403,assertThrows(Failure.class,()->runtime.result(alice,1L,"human-host")).status());
    }

    @Test void humanHostResultCannotBypassANewApprovalObligation() {
        var runtime=completedHumanHostCall();graph.policy("ask-result",Decision.ASK);saveGraph();
        var alice=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        assertEquals(403,assertThrows(Failure.class,()->runtime.result(alice,1L,"human-host")).status());
    }
    @Test void superAdministratorCanApproveOwnOperationOutsideApproverTeams(){
        graph.add(Kind.ROLE,new ControlRole("super-role","Super Admin",true,1L,UserRole.SUPER_ADMIN,EnterpriseRoleType.MANAGEMENT,List.of()));graph.add(Kind.TEAM,new ControlTeam("super-team","Owners",true,1L,List.of("alice"),List.of("super-role")));graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,2L,List.of("admin"),List.of("reviewer-role")));graph.policy("ask",Decision.ASK);saveGraph();var i=submit("super-request");var a=approval(i);var owner=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"d".repeat(64),true,false,"alice");var reply=graph.codec.model(operations.decide(owner,a.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(a.revision(),"ask",EnterpriseReviewDecision.APPROVE)),"super-self").body(),EnterpriseApproval.class);assertEquals(EnterpriseApprovalState.APPROVED,reply.state());assertEquals("alice",reply.reviews().getFirst().reviewerUserId());assertEquals(EnterpriseInvocationState.EXECUTING,consume(reserve(store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation(i.id())))).state());
    }
    @Test void claimedSuperFlagCannotOverrideOperationReviewIndependence(){graph.policy("ask",Decision.ASK);saveGraph();var a=approval(submit("claimed-super"));var owner=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"d".repeat(64),true,true,"alice");assertEquals(403,assertThrows(Failure.class,()->operations.decide(owner,a.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(a.revision(),"ask",EnterpriseReviewDecision.APPROVE)),"claimed")).status());}

    EnterpriseInvocationRequest delegated(String id,CredentialKind kind,String issuer){var r=request(id,"data/report.txt");
        var c=new RequestContext(id,EnterpriseConformanceTest.TENANT.value(),RequestMode.DELEGATED,kind,issuer,"alice","agent","workload",List.of(),1L,1L,null,null,"binding-a",EnterpriseConformanceTest.PROOF);
        return new EnterpriseInvocationRequest(c,r.request(),r.toolDigest(),r.packageDigest(),r.downstreamIdempotencyKey(),null);}
    /** M3.10: an on-behalf-of binding takes its user only from a verified user credential of the configured issuer. */
    @Test void onBehalfOfBindingNeedsAVerifiedUserCredential(){
        graph.remove(Kind.WORKLOAD_BINDING,"workload");graph.workload("workload","agent",RequestMode.DELEGATED,null,null);saveGraph();
        assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(delegated("static",CredentialKind.STATIC_TOKEN,null))),"a static token cannot claim the user");
        assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(delegated("untrusted",CredentialKind.JWT,"toolgate-control"))),"no trusted issuer configured");
        operations.userCredentialIssuer("toolgate-control");
        assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(delegated("other-issuer",CredentialKind.JWT,"someone-else"))));
        var i=graph.codec.model(operations.submit(gateway,graph.codec.json(delegated("verified",CredentialKind.JWT,"toolgate-control"))).body(),EnterpriseInvocation.class);
        assertEquals(EnterpriseInvocationState.QUEUED,i.state());assertEquals("alice",i.evaluation().context().userId());
    }
    /** M4.4: the submit row carries the authorization facts as separate fields. */
    @Test void submitAuditCarriesAuthorizationEvidence(){var i=submit("audited");
        var page=(com.fasterxml.jackson.databind.JsonNode)graph.codec.value(store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.auditPage(0,100)));
        com.fasterxml.jackson.databind.JsonNode row=null;for(var item:page.get("items"))if(item.get("operation").asText().equals("INVOCATION_SUBMIT")&&item.get("target").asText().equals("invocation:"+i.id()))row=item;
        assertNotNull(row);var e=row.get("evidence");
        assertEquals("alice",e.get("subjectUserId").asText());assertEquals("agent",e.get("actorAgentId").asText());assertEquals("workload",e.get("workloadBindingId").asText());
        assertEquals("read",e.get("operation").asText());assertEquals("FILE:data/report.txt",e.get("resources").get(0).asText());assertEquals("ALLOW",e.get("decision").asText());
        assertEquals("DELEGATED",e.get("mode").asText());
    }
    EnterpriseInvocationRequest epochs(String id,String path,long session,long credential){var r=request(id,path);var c=r.context();
        var renewed=new RequestContext(c.requestId(),c.tenantId(),c.mode(),c.credentialKind(),c.issuer(),c.userId(),c.agentId(),c.workloadBindingId(),c.chain(),session,credential,c.delegatedSessionEpoch(),c.expiresAtUnixMs(),c.bindingId(),c.credentialSha256());
        return new EnterpriseInvocationRequest(renewed,r.request(),r.toolDigest(),r.packageDigest(),r.downstreamIdempotencyKey(),null);}
    EnterpriseInvocation submitted(EnterpriseInvocationRequest r){return graph.codec.model(operations.submit(gateway,graph.codec.json(r)).body(),EnterpriseInvocation.class);}
    /** M5.3: the same logical operation with changed arguments is a conflict and never creates or changes an invocation. */
    @Test void changedArgumentsUnderTheSameKeyAreAConflict(){var first=submit("op-1");
        var conflict=assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(request("op-1","data/other.txt"))));
        assertEquals(409,conflict.status());
        assertEquals(first,store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation("op-1")));}
    /** M5.3: rotating the workload credential replays the same invocation under the current credential epoch. */
    @Test void aRotatedCredentialReplaysTheSameInvocation(){var first=submit("op-2");
        graph.remove(Kind.WORKLOAD_BINDING,"workload");
        graph.add(Kind.WORKLOAD_BINDING,new ControlWorkloadBinding("workload","workload",true,2L,"agent",RequestMode.DELEGATED,"gateway","workload-subject","gateway",EnterpriseConformanceTest.PROOF,2L,EnterpriseConformanceTest.NOW+100000,"alice",null,1L,null,null));saveGraph();
        assertThrows(Failure.class,()->submitted(epochs("op-2","data/report.txt",1L,1L)),"the old credential no longer decides");
        var replay=submitted(epochs("op-2","data/report.txt",1L,2L));
        assertEquals(first.id(),replay.id());assertEquals(first.requestDigest(),replay.requestDigest());assertEquals(2L,replay.evaluation().context().credentialEpoch());
        assertEquals("op-2",store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation("op-2")).id());}
    /** M5.3: a replay never revives an invocation for a session that was revoked meanwhile. */
    @Test void aRevokedSessionCannotReplayItsInvocation(){submit("op-3");
        graph.remove(Kind.IDENTITY_BINDING,"identity-alice");
        graph.add(Kind.IDENTITY_BINDING,new ControlIdentityBinding("identity-alice","Alice identity",true,2L,"alice","https://identity.example","alice-subject",2L,EnterpriseConformanceTest.NOW-1000,EnterpriseConformanceTest.NOW,1L,"REVIEWED",0L));saveGraph();
        assertThrows(Failure.class,()->submitted(epochs("op-3","data/report.txt",1L,1L)));}
    /** M5.3: concurrent submissions of one key create exactly one invocation, and every caller gets it. */
    @Test void concurrentSubmissionsOfOneKeyCreateExactlyOneInvocation()throws Exception{var pool=Executors.newFixedThreadPool(6);
        try{var jobs=new ArrayList<Future<String>>();for(int n=0;n<6;n++)jobs.add(pool.submit(()->submit("op-4").id()));
            for(var job:jobs)assertEquals("op-4",job.get());}finally{pool.shutdownNow();}
        assertEquals("op-4",store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation("op-4")).id());}
    /** M5.5: an operation waits up to the approval expiry, and approval resumes that same invocation with a fresh window. */
    @Test void approvalResumesTheOriginalInvocationWithAFreshDispatchWindow(){
        operations.approvalExpiry(EnterpriseOperations.MAX_APPROVAL_EXPIRY_MS);graph.policy("ask",Decision.ASK);saveGraph();
        var pending=submit("op-5");
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,pending.state());
        assertEquals(EnterpriseConformanceTest.NOW+EnterpriseOperations.MAX_APPROVAL_EXPIRY_MS,pending.expiresAtUnixMs());
        approve(approval(pending),"ask");
        var resumed=store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation("op-5"));
        assertEquals(EnterpriseInvocationState.QUEUED,resumed.state());
        assertEquals(EnterpriseConformanceTest.NOW+60000,resumed.expiresAtUnixMs());
        assertEquals(pending.requestDigest(),resumed.requestDigest());
        var replay=submit("op-5");
        assertEquals("op-5",replay.id());assertEquals(EnterpriseInvocationState.QUEUED,replay.state());
        assertThrows(IllegalArgumentException.class,()->operations.approvalExpiry(EnterpriseOperations.MAX_APPROVAL_EXPIRY_MS+1));}
    @Test void exactInvocationIsDurableAndIdempotentWithoutStoringArguments(){var i=submit("request");assertEquals(EnterpriseInvocationState.QUEUED,i.state());assertEquals(i,submit("request"));assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(request("request","data/other.txt"))));assertFalse(graph.codec.json(i).contains("credentialSha256"));}
    @Test void signedPermitBindsTargetVersionsArgumentsAndFullResourceSet(){var r=reserve(submit("request"));var claims=signer.verify(r.permit());assertEquals("binding-a",claims.bindingId());assertEquals("device",claims.deviceId());var e=r.invocation().evaluation();var other=new EnterprisePermitConsumption("request",r.permit(),e.argumentsDigest(),List.of(new ResourceDescriptor(ResourceKind.FILE,"data/other.txt")),e.toolDigest(),e.packageDigest());assertThrows(Failure.class,()->operations.consume(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(other)));assertEquals(EnterpriseInvocationState.EXECUTING,consume(r).state());}
    @Test void wrongCertificateDeviceAndTenantCannotConsume(){var r=reserve(submit("request"));assertThrows(Failure.class,()->operations.consume(EnterpriseConformanceTest.TENANT,"different",consumption(r)));assertThrows(Failure.class,()->operations.consume(io.ololabs.toolgate.control.fixtures.Tenants.id("other"),"device",consumption(r)));}
    @Test void permitCannotBeConsumedTwiceOrReservedAgainAfterConsumption(){var r=reserve(submit("request"));var i=consume(r);assertThrows(Failure.class,()->consume(r));assertThrows(Failure.class,()->reserve(i));}
    @Test void parallelReplicasOnlyConsumeOneNonce()throws Exception{var r=reserve(submit("request"));var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Future<Boolean>>();for(int n=0;n<4;n++)jobs.add(pool.submit(()->{try{consume(r);return true;}catch(Failure rejected){return false;}}));int successful=0;for(var job:jobs)if(job.get())successful++;assertEquals(1,successful);}finally{pool.shutdownNow();}}
    @Test void tamperedSignatureNeverConsumes(){var r=reserve(submit("request"));String jws=r.permit().jws();var altered=new EnterpriseReservation(r.invocation(),new EnterpriseSignedPermit(jws.substring(0,jws.length()-8)+"aaaaaaaa"));assertThrows(Failure.class,()->consume(altered));assertEquals(EnterpriseInvocationState.EXECUTING,consume(r).state());}
    @Test void directoryRevocationBetweenReservationAndEffectDenies(){var r=reserve(submit("request"));graph.remove(Kind.GRANT,"human");saveGraph();assertThrows(Failure.class,()->consume(r));}
    @Test void everyAskObligationNeedsIndependentReview(){graph.policy("ask-a",Decision.ASK);graph.policy("ask-b",Decision.ASK);saveGraph();var i=submit("request");assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,i.state());assertThrows(Failure.class,()->reserve(i));var a=approve(approval(i),"ask-a");assertEquals(EnterpriseApprovalState.PENDING,a.state());a=approve(a,"ask-b");assertEquals(EnterpriseApprovalState.APPROVED,a.state());var queued=store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation(i.id()));assertEquals(EnterpriseInvocationState.EXECUTING,consume(reserve(queued)).state());}
    @Test void requesterCannotReviewOwnOperationEvenWithManagementRole(){graph.policy("ask",Decision.ASK);saveGraph();var i=submit("request");var a=approval(i);var self=new DirectoryService.Actor(EnterpriseConformanceTest.TENANT,"d".repeat(64),true,false,"alice");assertThrows(Failure.class,()->operations.decide(self,a.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(a.revision(),"ask",EnterpriseReviewDecision.APPROVE)),"self"));}
    @Test void approvedOperationNeverManufacturesMissingGrant(){graph.policy("ask",Decision.ASK);saveGraph();var i=submit("request");approve(approval(i),"ask");graph.remove(Kind.GRANT,"human");saveGraph();var queued=store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation(i.id()));assertThrows(Failure.class,()->reserve(queued));}
    @Test void approvalRevocationAfterReservationStopsConsumption(){graph.policy("ask",Decision.ASK);saveGraph();var i=submit("request");var a=approve(approval(i),"ask");var queued=store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation(i.id()));var r=reserve(queued);operations.decide(reviewer,a.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(a.revision(),"ask",EnterpriseReviewDecision.REVOKE)),"revoke");assertThrows(Failure.class,()->consume(r));}
    @Test void deniedApprovalEndsTheOriginalInvocationAndReplayDoesNotWaitAgain(){
        graph.policy("ask",Decision.ASK);saveGraph();var invocation=submit("denied-original");var pending=approval(invocation);
        operations.decide(reviewer,pending.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(pending.revision(),"ask",EnterpriseReviewDecision.DENY)),"deny-original");
        var denied=submit("denied-original");
        assertEquals(invocation.id(),denied.id());assertEquals(EnterpriseInvocationState.FAILED,denied.state());
        assertEquals(EnterpriseApprovalState.DENIED,approval(denied).state());assertThrows(Failure.class,()->reserve(denied));
    }
    @Test void revokedQueuedApprovalCancelsBeforeDispatchAndCannotBeResumed(){
        graph.policy("ask",Decision.ASK);saveGraph();var invocation=submit("revoked-original");var approved=approve(approval(invocation),"ask");
        operations.decide(reviewer,approved.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(approved.revision(),"ask",EnterpriseReviewDecision.REVOKE)),"revoke-original");
        var cancelled=submit("revoked-original");
        assertEquals(invocation.id(),cancelled.id());assertEquals(EnterpriseInvocationState.CANCELLED,cancelled.state());
        assertThrows(Failure.class,()->reserve(cancelled));
    }
    @Test void revocationDuringAnExecutingEffectRetainsAnUnknownOutcome(){
        var old=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var definition=graph.codec.model(graph.codec.json(old.definition()).replaceFirst("[{]","{\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\","),ToolDefinition.class);
        graph.add(Kind.TOOL,new ControlTool(old.id(),old.name(),old.enabled(),old.revision()+1,definition,old.extractorId(),old.version(),old.packageDigest()));
        graph.policy("ask",Decision.ASK);saveGraph();var invocation=submit("executing-original");var approved=approve(approval(invocation),"ask");
        var executing=consume(reserve(submit("executing-original")));assertEquals(EnterpriseInvocationState.EXECUTING,executing.state());
        operations.decide(reviewer,approved.id(),graph.codec.json(new EnterpriseApprovalDecisionRequest(approved.revision(),"ask",EnterpriseReviewDecision.REVOKE)),"revoke-executing");
        var unknown=submit("executing-original");
        assertEquals(invocation.id(),unknown.id());assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,unknown.state());
        assertThrows(Failure.class,()->reserve(unknown));
    }
    /** A declaration alone cannot authorize a new consumed attempt (ADR 035). */
    @Test void downstreamIdempotentDeclarationDoesNotReopenAnUnknownAttempt(){
        var old=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var definition=graph.codec.model(graph.codec.json(old.definition()).replaceFirst("[{]","{\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\","),ToolDefinition.class);
        graph.add(Kind.TOOL,new ControlTool(old.id(),old.name(),old.enabled(),old.revision()+1,definition,old.extractorId(),old.version(),old.packageDigest()));saveGraph();
        var i=consume(reserve(submit("resend")));
        assertEquals(ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY,i.reconciliation());
        var report=new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null);
        operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(report));
        var resent=submit("resend");
        assertEquals("resend",resent.id());assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,resent.state());
        assertEquals(i.requestDigest(),resent.requestDigest());
        assertThrows(Failure.class,()->reserve(resent));}
    @Test void invocationsRecordTheirToolsReconciliationMethod(){assertEquals(ReconciliationMethod.MANUAL,submit("manual").reconciliation());}
    @Test void unknownExternalOutcomeCannotTriggerBlindRetry(){var i=consume(reserve(submit("request")));var report=new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null);var unknown=graph.codec.model(operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(report)).body(),EnterpriseInvocation.class);assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,unknown.state());assertThrows(Failure.class,()->reserve(unknown));assertEquals(unknown,submit("request"));}
    @Test void successRequiresEveryResourceAndAResultDigest(){var i=consume(reserve(submit("request")));assertThrows(Failure.class,()->operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.SUCCEEDED,List.of(),EnterpriseConformanceTest.DIGEST))));var success=new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.SUCCEEDED,i.evaluation().resources(),EnterpriseConformanceTest.DIGEST);assertEquals(EnterpriseInvocationState.SUCCEEDED,graph.codec.model(operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(success)).body(),EnterpriseInvocation.class).state());}
    @Test void responseRetrievalChecksRevokedAuthorization(){var i=consume(reserve(submit("request")));assertEquals(200,operations.get(gateway,i.id()).status());graph.remove(Kind.GRANT,"human");saveGraph();assertThrows(Failure.class,()->operations.get(gateway,i.id()));}
    @Test void durableGroupBudgetHasOneWinnerAcrossConcurrentSubmissions()throws Exception{
        var entry=graph.entries.get(Kind.GRANT.id("human"));var node=(com.fasterxml.jackson.databind.node.ObjectNode)graph.codec.value(entry.document());((com.fasterxml.jackson.databind.node.ObjectNode)node.path("scope").path("conditions")).put("maxInvocationsPerMinute",1);graph.entries.put(entry.id(),graph.codec.entry(Kind.GRANT,graph.codec.json(node)));saveGraph();
        var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Future<Boolean>>();for(int n=0;n<4;n++){String id="budget-"+n;jobs.add(pool.submit(()->{try{submit(id);return true;}catch(Failure denied){return false;}}));}int winners=0;for(var job:jobs)if(job.get())winners++;assertEquals(1,winners);}finally{pool.shutdownNow();}
        assertEquals(1L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().budgetUsage("GRANT:human",EnterpriseConformanceTest.NOW,null)).longValue());
        assertEquals(0L,store.<Long>transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().budgetUsage("GRANT:unrelated",EnterpriseConformanceTest.NOW,null)).longValue());
    }
    @Test void interruptedExpiredExecutionIsRecordedUnknownAndNeverRedispatched(){
        var i=consume(reserve(submit("request")));var later=new EnterpriseOperations(store,graph.codec,signer,Clock.fixed(Instant.ofEpochMilli(i.expiresAtUnixMs()+1),ZoneOffset.UTC),"control","toolgate-client",60000,100);
        var expired=graph.codec.model(later.get(reviewer,i.id()).body(),EnterpriseInvocation.class);assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,expired.state());assertThrows(Failure.class,()->reserve(expired));assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,store.transaction(EnterpriseConformanceTest.TENANT,false,tx->tx.enterprise().invocation(i.id())).state());
    }
    @Test void reconciliationRequiresIndependentCurrentCheckerAndNeverReissuesPermit(){
        graph.add(Kind.USER,new ControlUser("checker","Independent checker",true,1L));graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,2L,List.of("alice","admin","checker"),List.of("reviewer-role")));saveGraph();var executing=consume(reserve(submit("unknown-review")));
        var unknown=graph.codec.model(operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(new EnterpriseEffectReport(executing.id(),executing.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null))).body(),EnterpriseInvocation.class);
        var request=new EnterpriseReconciliationRequest(unknown.revision(),EnterpriseReconciledState.SUCCEEDED,unknown.evaluation().resources(),EnterpriseConformanceTest.DIGEST,EnterpriseConformanceTest.DIGEST);
        var proposed=graph.codec.model(operations.proposeReconciliation(reviewer,unknown.id(),graph.codec.json(request),"reconcile-key").body(),EnterpriseReconciliation.class);assertEquals(proposed.id(),graph.codec.model(operations.proposeReconciliation(reviewer,unknown.id(),graph.codec.json(request),"reconcile-key").body(),EnterpriseReconciliation.class).id());
        String decision=graph.codec.json(new EnterpriseReconciliationDecision(proposed.revision(),true));assertEquals(403,assertThrows(Failure.class,()->operations.reconcile(reviewer,proposed.id(),decision)).status());
        var checker=new DirectoryService.Actor(reviewer.tenant(),"f".repeat(64),true,false,"checker");assertEquals(EnterpriseReconciliationState.APPLIED,graph.codec.model(operations.reconcile(checker,proposed.id(),decision).body(),EnterpriseReconciliation.class).state());
        var resolved=graph.codec.model(operations.get(reviewer,unknown.id()).body(),EnterpriseInvocation.class);assertEquals(EnterpriseInvocationState.SUCCEEDED,resolved.state());assertEquals(resolved.id(),graph.codec.model(operations.deviceOutcome(reviewer.tenant(),tx->"device",resolved.id()).body(),EnterpriseInvocation.class).id());assertEquals(403,assertThrows(Failure.class,()->operations.deviceOutcome(reviewer.tenant(),tx->"other-device",resolved.id())).status());assertThrows(Failure.class,()->reserve(resolved));assertThrows(Failure.class,()->operations.reconcile(checker,proposed.id(),decision));
    }
    @Test void watchdogCommitsUnknownEvenWhenSubsequentAuthorityIsRevoked(){var i=consume(reserve(submit("watchdog")));graph.remove(Kind.GRANT,"human");saveGraph();var later=new EnterpriseOperations(store,graph.codec,signer,Clock.fixed(Instant.ofEpochMilli(i.expiresAtUnixMs()+1),ZoneOffset.UTC),"control","toolgate-client",60000,100);assertEquals(1,later.sweep(gateway.tenant()));assertEquals(0,later.sweep(gateway.tenant()));assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocation(i.id())).state());}

    EnterpriseInvocation keyed(String requestId,String path,String hash){
        var r=request(requestId,path);
        return graph.codec.model(operations.submit(gateway,graph.codec.json(new EnterpriseInvocationRequest(r.context(),r.request(),r.toolDigest(),r.packageDigest(),null,null,hash))).body(),EnterpriseInvocation.class);
    }
    @Test void businessKeySurvivesNewRequestIdsAndStoreReopen(){
        var first=keyed("step-a","data/report.txt","a".repeat(64));
        assertTrue(first.id().startsWith("op-"));
        store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("operations.sqlite"))),graph.codec);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        assertEquals(first,keyed("step-b","data/report.txt","a".repeat(64)));
        assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
        assertEquals(409,assertThrows(Failure.class,()->keyed("step-c","data/different.txt","a".repeat(64))).status());
    }
    @Test void concurrentBusinessKeyReservationsCreateOneInvocation()throws Exception{
        var pool=Executors.newFixedThreadPool(4);
        try{
            var tasks=new ArrayList<Callable<String>>();for(int n=0;n<8;n++){int index=n;tasks.add(()->keyed("parallel-"+index,"data/report.txt","b".repeat(64)).id());}
            var results=pool.invokeAll(tasks);var ids=new HashSet<String>();for(var result:results)ids.add(result.get());
            assertEquals(1,ids.size());assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
        }finally{pool.shutdownNow();}
    }
    @Test void ambiguousBusinessOperationCannotBeReopenedByDownstreamRetry(){
        var initial=keyed("ambiguous-a","data/report.txt","c".repeat(64));var executing=consume(reserve(initial));
        var unknown=new EnterpriseInvocation(executing.id(),executing.requestDigest(),executing.evaluation(),EnterpriseInvocationState.OUTCOME_UNKNOWN,executing.revision()+1,executing.authorizationEpoch(),executing.reservedNonce(),executing.expiresAtUnixMs(),List.of(),executing.downstreamIdempotencyKey(),null,executing.diagnosticId(),ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY);
        store.transaction(gateway.tenant(),true,tx->{tx.enterprise().saveInvocation(unknown,clock.millis(),executing.revision());return null;});
        assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,keyed("ambiguous-b","data/report.txt","c".repeat(64)).state());
        assertEquals(409,assertThrows(Failure.class,()->reserve(unknown)).status());
    }
    @Test void businessReplayChecksCurrentAuthorityAndRetention(){
        keyed("authority-a","data/report.txt","d".repeat(64));graph.remove(Kind.GRANT,"capability");saveGraph();
        assertEquals(403,assertThrows(Failure.class,()->keyed("authority-b","data/report.txt","d".repeat(64))).status());
    }

    @Test void businessKeyOwnerAndExpiredRetentionNeverPermitAnotherExecution(){
        var first=keyed("owner-a","data/report.txt","e".repeat(64));
        var r=request("owner-b","data/report.txt");var c=r.context();
        var other=new RequestContext(c.requestId(),c.tenantId(),c.mode(),c.credentialKind(),c.issuer(),"admin",c.agentId(),c.workloadBindingId(),c.chain(),c.sessionEpoch(),c.credentialEpoch(),c.delegatedSessionEpoch(),c.expiresAtUnixMs(),c.bindingId(),c.credentialSha256());
        assertEquals(409,assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(new EnterpriseInvocationRequest(other,r.request(),r.toolDigest(),r.packageDigest(),null,null,"e".repeat(64))))).status());
        operations=new EnterpriseOperations(store,graph.codec,signer,Clock.offset(clock,Duration.ofDays(31)),"control","toolgate-client",60000,100);
        assertEquals(409,assertThrows(Failure.class,()->keyed("owner-c","data/report.txt","e".repeat(64))).status());
        assertEquals(first.id(),store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).getFirst().id()));
    }
    @Test void businessKeysAlsoHoldUnderPostgresRuntimeRole()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL fixture");
        String database="business_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var stmt=c.createStatement()){stmt.execute("CREATE DATABASE "+database);}
        String url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).cleanDisabled(true).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        keyPolicySource=source;store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),graph.codec);
        store.transaction(gateway.tenant(),true,tx->{tx.save(tx.load(),graph.directory());return null;});EnterpriseConformanceTest.online(store,graph.codec,"device");
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        concurrentBusinessKeyReservationsCreateOneInvocation();
        ambiguousBusinessOperationCannotBeReopenedByDownstreamRetry();
        businessKeyOwnerAndExpiredRetentionNeverPermitAnotherExecutionPostgres();
        expiredReservationsRetainIndependentAttemptHistory();
    }
    void businessKeyOwnerAndExpiredRetentionNeverPermitAnotherExecutionPostgres(){
        var first=keyed("pg-retry-a","data/report.txt","f".repeat(64));
        assertEquals(first,keyed("pg-retry-b","data/report.txt","f".repeat(64)));
        assertEquals(409,assertThrows(Failure.class,()->keyed("pg-retry-c","data/different.txt","f".repeat(64))).status());
    }

    @Test void expiredUnconsumedBusinessInvocationIsNotSilentlyRebound(){
        var original=keyed("expiry-a","data/report.txt","1".repeat(64));
        operations=new EnterpriseOperations(store,graph.codec,signer,Clock.offset(clock,Duration.ofSeconds(61)),"control","toolgate-client",60000,100);
        var replay=keyed("expiry-b","data/report.txt","1".repeat(64));
        assertEquals(original.id(),replay.id());assertEquals(EnterpriseInvocationState.EXPIRED,replay.state());
        var key=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","1".repeat(64)));
        assertEquals(original.id(),key.invocation());
    }
    @Test void deniedBusinessAdmissionLeavesNoReservation(){
        var capability=graph.entries.get(Kind.GRANT.id("capability"));graph.remove(Kind.GRANT,"capability");saveGraph();
        assertEquals(403,assertThrows(Failure.class,()->keyed("denied-a","data/report.txt","2".repeat(64))).status());
        assertNull(store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","2".repeat(64))));
        graph.entries.put(Kind.GRANT.id("capability"),capability);saveGraph();
        assertEquals(EnterpriseInvocationState.QUEUED,keyed("denied-b","data/report.txt","2".repeat(64)).state());
    }

    @Test void keyedCancellationAcceptsOnlyExistingOwnedOperation(){
        var invocation=keyed("cancel-keyed","data/report.txt","3".repeat(64));var c=request(invocation.id(),"data/report.txt").context();
        var other=new RequestContext(c.requestId(),c.tenantId(),c.mode(),c.credentialKind(),c.issuer(),"admin",c.agentId(),c.workloadBindingId(),c.chain(),c.sessionEpoch(),c.credentialEpoch(),c.delegatedSessionEpoch(),c.expiresAtUnixMs(),c.bindingId(),c.credentialSha256());
        assertEquals(403,assertThrows(Failure.class,()->operations.cancelFromGateway(gateway,graph.codec.json(other))).status());
        assertEquals(200,operations.cancelFromGateway(gateway,graph.codec.json(c)).status());
        assertEquals(EnterpriseInvocationState.CANCELLED,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocation(invocation.id()).state()));
        var missing=request("op-"+"0".repeat(64),"data/report.txt").context();
        assertEquals(404,assertThrows(Failure.class,()->operations.cancelFromGateway(gateway,graph.codec.json(missing))).status());
        assertFalse(store.transaction(gateway.tenant(),false,tx->tx.enterprise().operationCancelled(missing.requestId())).booleanValue());
    }

    @Test void expiredReservationsRetainIndependentAttemptHistory(){
        var first=reserve(submit("attempt-history"));String oldFence=first.invocation().reservedNonce();
        operations=new EnterpriseOperations(store,graph.codec,signer,Clock.offset(clock,Duration.ofSeconds(11)),"control","toolgate-client",60000,100);
        var next=reserve(first.invocation());assertNotEquals(oldFence,next.invocation().reservedNonce());
        var attempts=store.transaction(gateway.tenant(),false,tx->tx.enterprise().attempts(first.invocation().id(),clock.millis()+11000));
        assertEquals(2,attempts.size());assertEquals("EXPIRED",attempts.getFirst().state());assertNull(attempts.getFirst().consumedAt());
        assertEquals("RESERVED",attempts.getLast().state());assertEquals(oldFence,attempts.getFirst().id());
        assertThrows(Failure.class,()->consume(first));
        var executing=consume(next);
        var success=new EnterpriseEffectReport(executing.id(),executing.revision(),EnterpriseInvocationState.SUCCEEDED,executing.evaluation().resources(),EnterpriseConformanceTest.DIGEST);
        operations.report(gateway.tenant(),"device",graph.codec.json(success));
        assertEquals("OUTCOME_UNKNOWN",store.transaction(gateway.tenant(),false,tx->tx.enterprise().operation(executing.id()).state()));
        var retained=store.transaction(gateway.tenant(),false,tx->tx.enterprise().attempts(executing.id(),clock.millis()+11000));
        assertEquals("EXPIRED",retained.getFirst().state());assertEquals("SUCCEEDED",retained.getLast().state());
        assertNotNull(retained.getLast().consumedAt());
    }
    @Test void operationCanFinalizeFailureOnlyWithoutConsumedAuthority(){
        var reserved=reserve(submit("never-started-operation"));
        operations=new EnterpriseOperations(store,graph.codec,signer,Clock.offset(clock,Duration.ofSeconds(61)),"control","toolgate-client",60000,100);
        var expired=submit("never-started-operation");assertEquals(EnterpriseInvocationState.EXPIRED,expired.state());
        assertEquals("FAILED",store.transaction(gateway.tenant(),false,tx->tx.enterprise().operation(expired.id()).state()));
        assertThrows(Failure.class,()->consume(reserved));
    }
    @Test void consumedFailureRemainsUnknownAtOperationLevel(){
        var executing=consume(reserve(submit("failed-attempt")));
        operations.report(gateway.tenant(),"device",graph.codec.json(new EnterpriseEffectReport(executing.id(),executing.revision(),EnterpriseInvocationState.FAILED,List.of(),null)));
        assertEquals("OUTCOME_UNKNOWN",store.transaction(gateway.tenant(),false,tx->tx.enterprise().operation(executing.id()).state()));
        assertEquals("FAILED",store.transaction(gateway.tenant(),false,tx->tx.enterprise().attempts(executing.id(),clock.millis()).getFirst().state()));
    }
    @Test void operationIntentAndAttemptHistorySurviveStoreReopen(){
        var executing=consume(reserve(submit("reopen-attempt")));
        store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("operations.sqlite"))),graph.codec);
        var operation=store.transaction(gateway.tenant(),false,tx->tx.enterprise().operation(executing.id()));
        assertEquals("RUNNING",operation.state());assertFalse(operation.legacy());
        var attempts=store.transaction(gateway.tenant(),false,tx->tx.enterprise().attempts(executing.id(),clock.millis()));
        assertEquals(executing.reservedNonce(),attempts.getFirst().id());assertEquals("EXECUTING",attempts.getFirst().state());assertNotNull(attempts.getFirst().consumedAt());
    }


    void businessPolicy(BusinessKeyPolicy policy) {
        var original=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var d=original.definition();
        var definition=new ToolDefinition(d.id(),d.name(),d.description(),d.actions(),d.inputSchema(),
                d.outputSchema(),d.reconciliation(),policy);
        graph.add(Kind.TOOL,new ControlTool(original.id(),original.name(),original.enabled(),
                original.revision()+1,definition,original.extractorId(),original.version(),original.packageDigest()));
        saveGraph();
    }
    @Test void requiredBusinessKeyIsRefusedBeforeAnyInvocationIsAdmitted() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,null,null));
        assertEquals(400,assertThrows(Failure.class,()->submit("missing-required-key")).status());
        assertTrue(store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100)).isEmpty());
        assertTrue(keyed("required-key-present","data/report.txt","4".repeat(64)).id().startsWith("op-"));
    }
    @Test void tenantBusinessKeyUsesTheEmptyVerifiedNamespaceIdentity() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.TENANT,null,null,null,null));
        var first=keyed("tenant-key-a","data/report.txt","5".repeat(64));
        assertEquals("op-"+DirectoryService.digest("toolgate-operation-v1\n"+gateway.tenant().value()+"\ntool\nTENANT\n\n"+"5".repeat(64)),first.id());
        assertEquals(first,keyed("tenant-key-b","data/report.txt","5".repeat(64)));
        var row=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","TENANT","","5".repeat(64)));
        assertEquals(first.id(),row.invocation());
        assertEquals("alice",row.user());
        assertEquals(409,assertThrows(Failure.class,()->keyed("tenant-key-changed","data/other.txt","5".repeat(64))).status());
    }
    EnterpriseInvocation argumentKey(String id,String explicit) {
        var r=request(id,"data/report.txt");
        var arguments=new HashMap<>(r.request().arguments());
        arguments.put("paymentKey",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("payment-42"));
        var call=new AuthorizationRequest(r.request().toolId(),r.request().action(),arguments);
        return graph.codec.model(operations.submit(gateway,graph.codec.json(new EnterpriseInvocationRequest(
                r.context(),call,r.toolDigest(),r.packageDigest(),null,null,explicit))).body(),EnterpriseInvocation.class);
    }
    @Test void argumentBusinessKeysAreDerivedAndPersistTheirSource() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,"paymentKey",null,null,null));
        var first=argumentKey("argument-key-a",null);
        assertEquals(first,argumentKey("argument-key-b",null));
        String hash=DirectoryService.digest("toolgate-business-key-v1\0"+gateway.tenant().value()+"\0payment-42");
        var row=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent",hash));
        assertEquals("ARGUMENT",row.source());
        assertEquals(first.id(),row.invocation());
        assertEquals(409,assertThrows(Failure.class,()->argumentKey("argument-key-mismatch","f".repeat(64))).status());
        assertFalse(store.transaction(gateway.tenant(),false,tx->tx.auditPage(0,100)).contains("payment-42"));
    }
    @Test void businessKeyReplayDoesNotExtendSnapshottedRetention() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,60000L,30000L));
        var first=keyed("retention-policy-a","data/report.txt","6".repeat(64));
        long expected=clock.millis()+60000;
        clock=Clock.fixed(Instant.ofEpochMilli(clock.millis()+1000),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        assertEquals(first,keyed("retention-policy-b","data/report.txt","6".repeat(64)));
        var row=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","6".repeat(64)));
        assertEquals(expected,row.retainUntil());
    }
    @Test void businessKeyPolicyReplaysStillRequireCurrentAuthorization() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,null,null));
        keyed("policy-authority-a","data/report.txt","7".repeat(64));
        graph.remove(Kind.GRANT,"capability");saveGraph();
        assertEquals(403,assertThrows(Failure.class,()->keyed("policy-authority-b","data/report.txt","7".repeat(64))).status());
    }
    @Test void businessKeyPoliciesAlsoHoldUnderPostgresRuntimeRole() throws Exception {
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL fixture");
        String database="keypolicy_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var stmt=c.createStatement()){stmt.execute("CREATE DATABASE "+database);}
        String url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).cleanDisabled(true).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        keyPolicySource=source;store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),graph.codec);
        store.transaction(gateway.tenant(),true,tx->{tx.save(tx.load(),graph.directory());return null;});EnterpriseConformanceTest.online(store,graph.codec,"device");
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        requiredBusinessKeyIsRefusedBeforeAnyInvocationIsAdmitted();
        tenantBusinessKeyUsesTheEmptyVerifiedNamespaceIdentity();
        argumentBusinessKeysAreDerivedAndPersistTheirSource();
        businessKeyReplayDoesNotExtendSnapshottedRetention();
        businessKeyPolicyReplaysStillRequireCurrentAuthorization();
    }


    @Test void businessKeyMaintenanceIsBoundedAndNeverTombstonesAmbiguousWork() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,1000L,null));
        var owner=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        for(char key:List.of('8','9','a')) {
            var invocation=keyed("maintenance-"+key,"data/report.txt",String.valueOf(key).repeat(64));
            operations.cancel(owner,invocation.id(),invocation.revision());
        }
        var unknown=consume(reserve(keyed("maintenance-unknown","data/report.txt","b".repeat(64))));
        clock=Clock.fixed(Instant.ofEpochMilli(clock.millis()+2000),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        for(int n=0;n<3;n++)assertEquals(1,store.transaction(gateway.tenant(),true,
                tx->tx.enterprise().tombstoneExpiredBusinessKeys(clock.millis(),1)).intValue());
        assertEquals(0,store.transaction(gateway.tenant(),true,
                tx->tx.enterprise().tombstoneExpiredBusinessKeys(clock.millis(),1)).intValue());
        var expired=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","8".repeat(64)));
        assertEquals(clock.millis(),expired.tombstonedAt());
        assertEquals(409,assertThrows(Failure.class,()->keyed("maintenance-replay","data/report.txt","8".repeat(64))).status());
        clock=Clock.fixed(Instant.ofEpochMilli(unknown.expiresAtUnixMs()+1),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        operations.sweep(gateway.tenant());
        var unresolved=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","b".repeat(64)));
        assertNull(unresolved.tombstonedAt());
        assertEquals("OUTCOME_UNKNOWN",unresolved.outcome());
    }
    @Test void resolvedWindowReplacementArchivesExactlyOneImmutableGeneration() {
        var original=submit("window-store-original");
        long created=clock.millis();
        var old=new EnterpriseStore.BusinessKey("tool","AGENT","agent","c".repeat(64),"sha256:"+original.requestDigest(),
                "agent","alice",original.id(),created+10000,null,"DEDUPE_WINDOW",created+5000);
        store.transaction(gateway.tenant(),true,tx->{tx.enterprise().saveBusinessKey(old,created);return null;});
        operations.cancel(new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice"),original.id(),original.revision());
        clock=Clock.fixed(Instant.ofEpochMilli(created+6000),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        var next=submit("window-store-next");
        var previous=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","c".repeat(64)));
        var replacement=new EnterpriseStore.BusinessKey(previous.tool(),previous.namespace(),previous.identity(),previous.hash(),
                previous.fingerprint(),previous.agent(),previous.user(),next.id(),clock.millis()+10000,null,"DEDUPE_WINDOW",clock.millis()+5000);
        store.transaction(gateway.tenant(),true,tx->{tx.enterprise().replaceBusinessKey(previous,replacement,clock.millis());return null;});
        var history=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory("tool","AGENT","agent","c".repeat(64),100));
        assertEquals(List.of(previous),history);
        assertEquals(next.id(),store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","c".repeat(64))).invocation());
        assertEquals(409,assertThrows(Failure.class,()->store.transaction(gateway.tenant(),true,tx->{
            tx.enterprise().replaceBusinessKey(previous,replacement,clock.millis());return null;
        })).status());
        assertEquals(history,store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory("tool","AGENT","agent","c".repeat(64),100)));
    }
    @Test void unresolvedWindowCannotBeReplacedDespiteElapsedTime() {
        var original=consume(reserve(submit("window-store-ambiguous")));
        long created=clock.millis();
        var old=new EnterpriseStore.BusinessKey("tool","AGENT","agent","d".repeat(64),"sha256:"+original.requestDigest(),
                "agent","alice",original.id(),created+10000,null,"DEDUPE_WINDOW",created+5000);
        store.transaction(gateway.tenant(),true,tx->{tx.enterprise().saveBusinessKey(old,created);return null;});
        clock=Clock.fixed(Instant.ofEpochMilli(created+6000),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        var next=submit("window-store-denied-next");
        var replacement=new EnterpriseStore.BusinessKey(old.tool(),old.namespace(),old.identity(),old.hash(),old.fingerprint(),
                old.agent(),old.user(),next.id(),clock.millis()+10000,null,"DEDUPE_WINDOW",clock.millis()+5000);
        assertEquals(409,assertThrows(Failure.class,()->store.transaction(gateway.tenant(),true,tx->{
            tx.enterprise().replaceBusinessKey(old,replacement,clock.millis());return null;
        })).status());
        assertTrue(store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory("tool","AGENT","agent","d".repeat(64),100)).isEmpty());
        assertEquals(original.id(),store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKey("tool","AGENT","agent","d".repeat(64))).invocation());
    }


    void windowPolicy() { businessPolicy(new BusinessKeyPolicy(false,BusinessKeyNamespace.AGENT,null,5000L,10000L,null)); }
    void advanceKeyClock(long millis) {
        clock=Clock.fixed(Instant.ofEpochMilli(clock.millis()+millis),ZoneOffset.UTC);
        operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
    }
    @Test void optionalWindowCoalescesNewRequestIdsWithoutBecomingAnExplicitKey() {
        windowPolicy();
        var original=submit("optional-window-a");
        assertEquals(original,submit("optional-window-b"));
        var key=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(original.id()));
        assertEquals("DEDUPE_WINDOW",key.source());
        assertEquals(clock.millis()+5000,key.dedupeExpiresAt());
        assertEquals(409,assertThrows(Failure.class,()->keyed("borrow-window-hash","data/report.txt",key.hash())).status());
        assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
    }
    @Test void elapsedWindowCannotReplaceAStillRunningOperation() {
        windowPolicy();
        var original=submit("window-still-running");
        advanceKeyClock(6000);
        assertEquals(409,assertThrows(Failure.class,()->submit("window-running-retry")).status());
        assertEquals(original.id(),store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).getFirst().id()));
    }
    @Test void elapsedWindowCreatesANewOperationOnlyAfterPositiveResolution() {
        windowPolicy();
        var original=submit("window-positive-a");
        var owner=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        operations.cancel(owner,original.id(),original.revision());
        var previous=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(original.id()));
        advanceKeyClock(6000);
        var next=submit("window-positive-b");
        assertNotEquals(original.id(),next.id());
        assertEquals(next,submit("window-positive-c"));
        assertEquals(EnterpriseInvocationState.CANCELLED,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocation(original.id())).state());
        assertEquals(List.of(previous),store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory(
                previous.tool(),previous.namespace(),previous.identity(),previous.hash(),100)));
        assertEquals(2,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
    }
    @Test void tombstonedWindowHistorySurvivesANewResolvedGeneration() {
        windowPolicy();
        var original=submit("window-tombstone-a");
        operations.cancel(new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice"),original.id(),original.revision());
        advanceKeyClock(11000);
        operations.sweep(gateway.tenant());
        var previous=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(original.id()));
        assertNotNull(previous.tombstonedAt());
        var next=submit("window-tombstone-b");
        assertNotEquals(original.id(),next.id());
        var history=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory(
                previous.tool(),previous.namespace(),previous.identity(),previous.hash(),100));
        assertEquals(List.of(previous),history);
        assertNotNull(history.getFirst().tombstonedAt());
        assertNull(store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(next.id())).tombstonedAt());
    }
    @Test void ambiguousWindowRemainsFencedAfterItsDedupeDeadline() {
        windowPolicy();
        var executing=consume(reserve(submit("window-unknown-a")));
        var unknown=new EnterpriseInvocation(executing.id(),executing.requestDigest(),executing.evaluation(),
                EnterpriseInvocationState.OUTCOME_UNKNOWN,executing.revision()+1,executing.authorizationEpoch(),
                executing.reservedNonce(),executing.expiresAtUnixMs(),List.of(),executing.downstreamIdempotencyKey(),
                null,executing.diagnosticId(),executing.reconciliation());
        store.transaction(gateway.tenant(),true,tx->{tx.enterprise().saveInvocation(unknown,clock.millis(),executing.revision());return null;});
        var key=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(executing.id()));
        advanceKeyClock(6000);
        assertEquals(409,assertThrows(Failure.class,()->submit("window-unknown-b")).status());
        assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
        assertTrue(store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyHistory(
                key.tool(),key.namespace(),key.identity(),key.hash(),100)).isEmpty());
    }
    @Test void concurrentWindowSubmissionsFromTwoServiceInstancesShareOneOperation() throws Exception {
        windowPolicy();
        var other=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
        var pool=Executors.newFixedThreadPool(8);
        try {
            var calls=new ArrayList<Callable<String>>();
            for(int n=0;n<8;n++) {
                int index=n;
                calls.add(()->{
                    var service=index%2==0?operations:other;
                    return graph.codec.model(service.submit(gateway,graph.codec.json(request("window-parallel-"+index,"data/report.txt"))).body(),EnterpriseInvocation.class).id();
                });
            }
            var ids=new HashSet<String>();for(var result:pool.invokeAll(calls))ids.add(result.get());
            assertEquals(1,ids.size());
            assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
        } finally {pool.shutdownNow();}
    }
    @Test void requiredKeyNeverFallsBackToItsConfiguredWindow() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,5000L,10000L,null));
        assertEquals(400,assertThrows(Failure.class,()->submit("required-key-window-fallback")).status());
        assertTrue(store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100)).isEmpty());
    }
    @FunctionalInterface interface KeyPolicyScenario { void run() throws Exception; }
    @Test void windowAndRetentionScenariosHoldUnderPostgresRuntimeRole() throws Exception {
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL fixture");
        var scenarios=List.<KeyPolicyScenario>of(
                this::optionalWindowCoalescesNewRequestIdsWithoutBecomingAnExplicitKey,
                this::elapsedWindowCannotReplaceAStillRunningOperation,
                this::elapsedWindowCreatesANewOperationOnlyAfterPositiveResolution,
                this::tombstonedWindowHistorySurvivesANewResolvedGeneration,
                this::ambiguousWindowRemainsFencedAfterItsDedupeDeadline,
                this::concurrentWindowSubmissionsFromTwoServiceInstancesShareOneOperation,
                this::requiredKeyNeverFallsBackToItsConfiguredWindow,
                this::businessKeyMaintenanceIsBoundedAndNeverTombstonesAmbiguousWork,
                this::resolvedWindowReplacementArchivesExactlyOneImmutableGeneration,
                this::unresolvedWindowCannotBeReplacedDespiteElapsedTime,
                this::tenantKeysNeverDiscloseAnotherOwnersResultForMatchingOrChangedInputs,
                this::humanKeysUseTheVerifiedUserNamespaceAndCannotUseAnAbsentAgent,
                this::invalidKeyPolicyCannotPassConfigurationReviewValidation,
                this::databaseGuardsRejectRetentionRewritesHistoryDeletionAndForgedArchives,
                this::catalogUsesCurrentControlKeyPolicyInsteadOfDeviceHints,
                this::directHumanPolicyCallsUseCanonicalIdsAndOneDurableLocalDispatch);
        for(var scenario:scenarios) {
            graph=new EnterpriseConformanceTest();graph.graph();
            clock=Clock.fixed(Instant.ofEpochMilli(EnterpriseConformanceTest.NOW),ZoneOffset.UTC);
            String database="windowpolicy_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=java.sql.DriverManager.getConnection(base,"control_migrator",password);var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+database);}
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).cleanDisabled(true).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            keyPolicySource=source;store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),graph.codec);
            store.transaction(gateway.tenant(),true,tx->{tx.save(tx.load(),graph.directory());return null;});
            EnterpriseConformanceTest.online(store,graph.codec,"device");
            signer=new RsaEffectSigner(key,"permit-key",graph.codec);
            operations=new EnterpriseOperations(store,graph.codec,signer,clock,"control","toolgate-client",60000,100);
            scenario.run();
        }
    }


    @Test void tenantKeysNeverDiscloseAnotherOwnersResultForMatchingOrChangedInputs() {
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.TENANT,null,null,null,null));
        var original=keyed("tenant-owner-a","data/report.txt","e".repeat(64));
        for(String path:List.of("data/report.txt","data/other.txt")) {
            var r=request("tenant-owner-other",path);var c=r.context();
            var other=new RequestContext(c.requestId(),c.tenantId(),c.mode(),c.credentialKind(),c.issuer(),"admin",
                    c.agentId(),c.workloadBindingId(),c.chain(),c.sessionEpoch(),c.credentialEpoch(),
                    c.delegatedSessionEpoch(),c.expiresAtUnixMs(),c.bindingId(),c.credentialSha256());
            var supplied=new EnterpriseInvocationRequest(other,r.request(),r.toolDigest(),r.packageDigest(),null,null,"e".repeat(64));
            assertEquals(409,assertThrows(Failure.class,()->operations.submit(gateway,graph.codec.json(supplied))).status());
        }
        assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().invocations("",100).size()).intValue());
        assertEquals(original.id(),store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(original.id())).invocation());
    }
    @Test void humanKeysUseTheVerifiedUserNamespaceAndCannotUseAnAbsentAgent() {
        var alice=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,null,null));
        var denied=request("human-agent-key","data/report.txt");var c=denied.context();
        var human=new RequestContext(c.requestId(),c.tenantId(),RequestMode.HUMAN,CredentialKind.STATIC_TOKEN,
                null,"alice",null,null,List.of(),1L,null,null,null,c.bindingId(),null);
        var supplied=new EnterpriseInvocationRequest(human,denied.request(),denied.toolDigest(),denied.packageDigest(),null,null,"f".repeat(64));
        assertEquals(403,assertThrows(Failure.class,()->operations.submitHuman(alice,supplied,"device")).status());
        businessPolicy(new BusinessKeyPolicy(true,BusinessKeyNamespace.DELEGATED_USER,null,null,null,null));
        var allowed=request("human-user-key","data/report.txt");
        var keyRequest=new EnterpriseInvocationRequest(human,allowed.request(),allowed.toolDigest(),allowed.packageDigest(),null,null,"f".repeat(64));
        var invocation=graph.codec.model(operations.submitHuman(alice,keyRequest,"device").body(),EnterpriseInvocation.class);
        var key=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(invocation.id()));
        assertEquals("DELEGATED_USER",key.namespace());assertEquals("alice",key.identity());assertEquals("",key.agent());
        assertEquals("alice",key.user());
    }
    @Test void invalidKeyPolicyCannotPassConfigurationReviewValidation() {
        var original=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var d=original.definition();
        for(var invalid:List.of(
                new BusinessKeyPolicy(true,BusinessKeyNamespace.AGENT,null,null,1000L,2000L),
                new BusinessKeyPolicy(false,BusinessKeyNamespace.AGENT,null,5000L,1000L,null))) {
            var definition=new ToolDefinition(d.id(),d.name(),d.description(),d.actions(),d.inputSchema(),d.outputSchema(),d.reconciliation(),invalid);
            graph.add(Kind.TOOL,new ControlTool(original.id(),original.name(),original.enabled(),original.revision()+1,
                    definition,original.extractorId(),original.version(),original.packageDigest()));
            assertThrows(IllegalArgumentException.class,()->graph.codec.validatePolicies(graph.directory()));
        }
    }
    void deniedKeySql(String table,String invocation,String sql) throws Exception {
        try(var connection=keyPolicySource.getConnection()) {
            connection.setAutoCommit(false);
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                try(var scope=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                    scope.setString(1,gateway.tenant().value());scope.execute();
                }
            try(var check=connection.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND invocation_id=?")){
                check.setString(1,gateway.tenant().value());check.setString(2,invocation);
                try(var rows=check.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            }
            try(var statement=connection.prepareStatement(sql)){
                statement.setString(1,gateway.tenant().value());statement.setString(2,invocation);
                assertThrows(java.sql.SQLException.class,statement::executeUpdate);
            }
            connection.rollback();
        }
    }
    @Test void databaseGuardsRejectRetentionRewritesHistoryDeletionAndForgedArchives() throws Exception {
        resolvedWindowReplacementArchivesExactlyOneImmutableGeneration();
        for(String update:List.of("retain_until=retain_until+1","dedupe_expires_at=dedupe_expires_at+1","namespace_identity='forged'"))
            deniedKeySql("control_business_keys","window-store-next",
                    "UPDATE control_business_keys SET "+update+" WHERE tenant_id=? AND invocation_id=?");
        deniedKeySql("control_business_key_history","window-store-original",
                "DELETE FROM control_business_key_history WHERE tenant_id=? AND invocation_id=?");
        deniedKeySql("control_business_key_history","window-store-original",
                "UPDATE control_business_key_history SET fingerprint='sha256:"+"0".repeat(64)+"' WHERE tenant_id=? AND invocation_id=?");
        deniedKeySql("control_business_keys","window-store-next",
                "INSERT INTO control_business_key_history(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id,fingerprint,caller_agent_id,caller_user_id,created_at,dedupe_expires_at,retain_until,outcome,tombstoned_at,archived_at) SELECT tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id,fingerprint,caller_agent_id,caller_user_id,created_at,dedupe_expires_at,retain_until,'FAILED',tombstoned_at,dedupe_expires_at+1 FROM control_business_keys WHERE tenant_id=? AND invocation_id=?");
        try(var connection=keyPolicySource.getConnection()){
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")){
                connection.setAutoCommit(false);
                try(var scope=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                    scope.setString(1,io.ololabs.toolgate.control.fixtures.Tenants.id("other").value());scope.execute();
                }
                try(var query=connection.prepareStatement("SELECT count(*) FROM control_business_key_history WHERE tenant_id=?")){
                    query.setString(1,gateway.tenant().value());
                    try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                }
                connection.rollback();
            }
        }
    }


    @Test void catalogUsesCurrentControlKeyPolicyInsteadOfDeviceHints() {
        var policy=new BusinessKeyPolicy(true,BusinessKeyNamespace.TENANT,null,null,10000L,null);
        businessPolicy(policy);
        var tool=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var hint=new LocalToolInfo("tool","read","Device hint",true,tool.definition().inputSchema(),
                new EnterpriseEvaluator(graph.codec).toolDigest(graph.directory(),tool),tool.packageDigest(),
                new BusinessKeyPolicy(false,BusinessKeyNamespace.AGENT,null,null,null,null));
        store.transaction(gateway.tenant(),true,tx->{
            tx.saveEndpointConfiguration(new Store.EndpointConfiguration("device",tx.load().revision(),"{}",null,
                    graph.codec.json(new LocalToolCatalog(List.of(hint)))));
            return null;
        });
        var endpoints=new EndpointService(store,graph.codec,null,clock,true,gateway.tenant().value(),
                "server","Organization","https://control.example.test","https://gateway.example.test");
        var service=new McpService(store,graph.codec,endpoints,operations,clock,gateway.tenant(),"server");
        String body=graph.codec.json(request("policy-catalog","data/report.txt").context());
        var catalog=graph.codec.model(service.catalog(gateway,true,body).body(),LocalToolCatalog.class);
        assertEquals(1,catalog.tools().size());
        assertEquals(policy,catalog.tools().getFirst().businessKeyPolicy());
        graph.remove(Kind.GRANT,"capability");saveGraph();
        assertTrue(graph.codec.model(service.catalog(gateway,true,body).body(),LocalToolCatalog.class).tools().isEmpty());
    }


    @Test void directHumanPolicyCallsUseCanonicalIdsAndOneDurableLocalDispatch() {
        var policy=new BusinessKeyPolicy(true,BusinessKeyNamespace.DELEGATED_USER,null,null,10000L,null);
        businessPolicy(policy);
        var tool=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var hint=new LocalToolInfo("tool","read","Human local tool",true,tool.definition().inputSchema(),
                new EnterpriseEvaluator(graph.codec).toolDigest(graph.directory(),tool),tool.packageDigest());
        store.transaction(gateway.tenant(),true,tx->{
            tx.saveEndpointConfiguration(new Store.EndpointConfiguration("device",tx.load().revision(),"{}",null,
                    graph.codec.json(new LocalToolCatalog(List.of(hint)))));return null;
        });
        var endpoints=new EndpointService(store,graph.codec,null,clock,true,gateway.tenant().value(),
                "server","Organization","https://control.example.test","https://gateway.example.test").operations(operations);
        var service=new McpService(store,graph.codec,endpoints,operations,clock,gateway.tenant(),"server");
        var runtime=new HumanRuntime(store,graph.codec,operations,service,new ServerPackages(store,graph.codec,operations,clock));
        var actor=new DirectoryService.Actor(gateway.tenant(),"d".repeat(64),false,false,"alice");
        var request=new EnterpriseHumanRequest("binding-a","device",request("human-local-policy","data/report.txt").request());
        var first=graph.codec.model(runtime.invoke(actor,1,graph.codec.json(request),"human-step-42").body(),EnterpriseHumanOutcome.class);
        assertTrue(first.invocation().id().startsWith("op-"));
        assertEquals(first.invocation().id(),first.dispatch().record().requestId());
        var replay=graph.codec.model(runtime.invoke(actor,1,graph.codec.json(request),"human-step-42").body(),EnterpriseHumanOutcome.class);
        assertEquals(first.invocation().id(),replay.invocation().id());
        assertEquals(first.dispatch().record().requestId(),replay.dispatch().record().requestId());
        var binding=store.transaction(gateway.tenant(),false,tx->tx.enterprise().businessKeyForInvocation(first.invocation().id()));
        assertEquals("DELEGATED_USER",binding.namespace());assertEquals("alice",binding.identity());
        assertEquals(1,store.transaction(gateway.tenant(),false,tx->tx.enterprise().attempts(first.invocation().id(),clock.millis()).size()).intValue());
    }

}
