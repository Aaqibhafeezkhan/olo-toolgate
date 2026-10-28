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
    @BeforeAll static void signingKey()throws Exception{var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=(java.security.interfaces.RSAPrivateCrtKey)generator.generateKeyPair().getPrivate();}
    @BeforeEach void setup(){
        graph=new EnterpriseConformanceTest();graph.graph();clock=Clock.fixed(Instant.ofEpochMilli(EnterpriseConformanceTest.NOW),ZoneOffset.UTC);
        var rules=List.of(new EnterpriseManagementRule(List.of("operation.approve","operation.read","operation.cancel"),EnterpriseGroupType.TOOL_GROUP,new GroupSelection(List.of("tools-a"),false),List.of(),graph.conditions(),null),new EnterpriseManagementRule(List.of("operation.approve","operation.read","operation.cancel"),EnterpriseGroupType.DEVICE_GROUP,new GroupSelection(List.of("devices-a"),false),List.of(),graph.conditions(),null));
        graph.add(Kind.ROLE,new ControlRole("reviewer-role","Reviewer",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));
        graph.add(Kind.TEAM,new ControlTeam("team-b","Reviewers",true,1L,List.of("alice","admin"),List.of("reviewer-role")));
        store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(temp.resolve("operations.sqlite"))),graph.codec);store.transaction(EnterpriseConformanceTest.TENANT,true,tx->{tx.save(tx.load(),graph.directory());return null;});EnterpriseConformanceTest.online(store,graph.codec,"device");
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
    /** M5.4: an unknown outcome of a DOWNSTREAM_IDEMPOTENCY tool is resent by replaying its key; the target deduplicates. */
    @Test void downstreamIdempotentToolsAreResentWhenTheirKeyIsReplayed(){
        var old=graph.codec.model(graph.entries.get(Kind.TOOL.id("tool")).document(),ControlTool.class);
        var definition=graph.codec.model(graph.codec.json(old.definition()).replaceFirst("[{]","{\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\","),ToolDefinition.class);
        graph.add(Kind.TOOL,new ControlTool(old.id(),old.name(),old.enabled(),old.revision()+1,definition,old.extractorId(),old.version(),old.packageDigest()));saveGraph();
        var i=consume(reserve(submit("resend")));
        assertEquals(ReconciliationMethod.DOWNSTREAM_IDEMPOTENCY,i.reconciliation());
        var report=new EnterpriseEffectReport(i.id(),i.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null);
        operations.report(EnterpriseConformanceTest.TENANT,"device",graph.codec.json(report));
        var resent=submit("resend");
        assertEquals("resend",resent.id());assertEquals(EnterpriseInvocationState.QUEUED,resent.state());
        assertEquals(i.requestDigest(),resent.requestDigest());
        assertEquals(EnterpriseInvocationState.RESERVED,reserve(resent).invocation().state());}
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

}
