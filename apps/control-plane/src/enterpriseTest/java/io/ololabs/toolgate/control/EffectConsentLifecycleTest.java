// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authenticated admission, immutable preview, approval, actual effect grant and revocation on real Control SQL. */
class EffectConsentLifecycleTest {
    @TempDir Path temp;EffectGrantTest fixture;EnterpriseOperationTest rig;DirectoryService.Actor owner;
    @BeforeAll static void key()throws Exception{EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup()throws Exception{
        fixture=new EffectGrantTest();fixture.temp=temp;fixture.classification="FINANCIAL";fixture.setup();rig=fixture.base.rig;
        owner=new DirectoryService.Actor(rig.gateway.tenant(),"d".repeat(64),false,false,"alice");
    }
    EnterpriseInvocation submit(String id){return rig.graph.codec.model(fixture.base.submit(fixture.base.request(id)).body(),EnterpriseInvocation.class);}
    EffectConsentView view(String id){return rig.graph.codec.model(rig.operations.effectConsent(owner,id).body(),EffectConsentView.class);}
    EffectConsentView approve(String id){
        var v=view(id);return rig.graph.codec.model(rig.operations.decideEffectConsent(rig.reviewer,id,
            rig.graph.codec.json(new EffectConsentApprovalRequest(v.previewDigest(),v.revision())),false).body(),EffectConsentView.class);
    }
    EnterpriseInvocation invocation(String id){return rig.graph.codec.model(rig.operations.get(owner,id).body(),EnterpriseInvocation.class);}
    EffectGrantIssue request(EnterpriseInvocation i)throws Exception{
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.enterprise().saveExecutionLease(
            new EnterpriseStore.ExecutionLease(i.id(),i.reservedNonce(),"device",i.expiresAtUnixMs()));return null;});
        var original=fixture.issue;var claims=(ObjectNode)EffectGrantTest.JSON.readTree(rig.graph.codec.json(rig.signer.verifyInvocation(original.invocationPermit())));
        claims.put("invocationId",i.id()).put("nonce",i.reservedNonce()).put("deadlineUnixMs",i.expiresAtUnixMs());
        var v=view(i.id());claims.set("effectConsent",EffectGrantTest.JSON.valueToTree(new EffectConsentBinding(v.preview().consentId(),v.previewDigest())));
        var request=new EffectGrantIssue(i.id(),i.reservedNonce(),rig.signer.signInvocation(rig.graph.codec.model(claims.toString(),InvocationPermitClaims.class)),
            original.brokerInstanceId(),original.handlerId(),original.arguments(),original.httpRequestDigest(),original.httpRequestManifest(), original.effectKey());
        return request;
    }
    EffectGrant grant(EnterpriseInvocation i)throws Exception{
        return rig.graph.codec.model(fixture.grants.issue(rig.gateway.tenant(),tx->"device","a".repeat(64),rig.graph.codec.json(request(i))).body(),EffectGrant.class);
    }
    @Test void changingEffectKeyCannotSpendTheSameConsentSlotTwice()throws Exception{
        var i=submit("distinct-effect-consent");approve(i.id());
        var running=rig.consume(rig.reserve(invocation(i.id())));
        var first=grant(running);
        var document=(ObjectNode)EffectGrantTest.JSON.readTree(rig.graph.codec.json(request(running)));
        document.put("effectKey","extra-charge");
        var failure=assertThrows(Failure.class,()->fixture.grants.issue(rig.gateway.tenant(),tx->"device","a".repeat(64),document.toString()));
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_EXHAUSTED,failure.detail());
        assertEquals(200,fixture.send(first,"handler").status());
        String extra=EffectIdentity.of(rig.graph.codec,rig.gateway.tenant().value(),i.id(),"write","extra-charge");
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().current(extra)));
    }
    @Test void mandatoryConsentWaitsBeforeDispatchAndAuthorizesOnlyThePreviewedMappedEffect()throws Exception{
        var i=submit("consented");
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,i.state());
        assertThrows(Failure.class,()->rig.reserve(i));
        var v=view(i.id());assertEquals(EffectConsentState.PENDING,v.state());
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,submit("consented").state());
        assertEquals(v.previewDigest(),view(i.id()).previewDigest());
        assertEquals(v.preview().expiresAtUnixMs(),view(i.id()).preview().expiresAtUnixMs());
        assertEquals(EffectClassification.FINANCIAL,v.preview().slots().getFirst().request().classification());
        assertEquals("olo://"+rig.gateway.tenant().value()+"/conversation/c1",v.preview().slots().getFirst().request().body().path("path").asText());
        assertEquals(EffectConsentState.APPROVED,approve(i.id()).state());
        var queued=invocation(i.id());assertEquals(EnterpriseInvocationState.QUEUED,queued.state());
        var running=rig.consume(rig.reserve(queued));var grant=grant(running);
        assertEquals(200,fixture.send(grant,"handler").status());
        assertThrows(Failure.class,()->grant(running));
    }
    @Test void consentRevocationBetweenGrantAndSendPreventsTheSend()throws Exception{
        var i=submit("revoked-consent");approve(i.id());var running=rig.consume(rig.reserve(invocation(i.id())));
        var grant=grant(running);var v=view(i.id());
        rig.operations.decideEffectConsent(rig.reviewer,i.id(),rig.graph.codec.json(new EffectConsentApprovalRequest(v.previewDigest(),v.revision())),true);
        assertThrows(Failure.class,()->fixture.send(grant,"handler"));
        assertEquals(EffectConsentState.REVOKED,view(i.id()).state());
    }
    @Test void gatewayAndStaleOrChangedPreviewCannotApproveExactConsent(){
        var i=submit("invalid-consent");var v=view(i.id());
        var request=rig.graph.codec.json(new EffectConsentApprovalRequest(v.previewDigest(),v.revision()));
        assertThrows(Failure.class,()->rig.operations.decideEffectConsent(rig.gateway,i.id(),request,false));
        assertEquals(409,assertThrows(Failure.class,()->rig.operations.decideEffectConsent(rig.reviewer,i.id(),
            rig.graph.codec.json(new EffectConsentApprovalRequest(EffectLedgerTest.RESULT,v.revision())),false)).status());
        approve(i.id());assertEquals(409,assertThrows(Failure.class,()->rig.operations.decideEffectConsent(rig.reviewer,i.id(),request,false)).status());
    }
    @Test void approvalNeverExtendsTheOriginalCredentialWindow(){
        var i=submit("bounded-consent");var v=view(i.id());long original=v.preview().expiresAtUnixMs();
        rig.advanceKeyClock(1000);approve(i.id());
        assertTrue(invocation(i.id()).expiresAtUnixMs()<=original);
        assertEquals(original,view(i.id()).preview().expiresAtUnixMs());
    }
    void ask(){
        rig.graph.policy("ask",Decision.ASK);
        var id=io.ololabs.toolgate.control.domain.Ids.Kind.POLICY.id("ask");
        String document=rig.graph.entries.get(id).document().replace("\"read\"","\"memory.append\"")
            .replace("\"FILE\"","\"CUSTOM\"").replace("\"data\"","\"olo://"+rig.gateway.tenant().value()+"/conversation/\"");
        rig.graph.entries.put(id,rig.graph.codec.entry(io.ololabs.toolgate.control.domain.Ids.Kind.POLICY,document));rig.saveGraph();
    }
    @Test void exactConsentDoesNotSatisfyAnIndependentPolicyApproval(){
        ask();
        var i=submit("consent-first");approve(i.id());
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,invocation(i.id()).state());
        assertThrows(Failure.class,()->rig.reserve(invocation(i.id())));
        rig.approve(rig.approval(i),"ask");
        assertEquals(EnterpriseInvocationState.QUEUED,invocation(i.id()).state());
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.consume(rig.reserve(invocation(i.id()))).state());
    }
    @Test void ordinaryPolicyApprovalDoesNotSatisfyExactEffectConsent(){
        ask();
        var i=submit("policy-first");rig.approve(rig.approval(i),"ask");
        assertEquals(EffectConsentState.PENDING,view(i.id()).state());
        assertEquals(EnterpriseInvocationState.PENDING_APPROVAL,invocation(i.id()).state());
        assertThrows(Failure.class,()->rig.reserve(invocation(i.id())));
        approve(i.id());
        assertEquals(EnterpriseInvocationState.EXECUTING,rig.consume(rig.reserve(invocation(i.id()))).state());
    }

    @Test void historicalHighRiskAdmissionWithoutAPreviewCannotRenewItsLease(){
        var old=fixture.invocation;
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effectConsents().forOperation(old.id())));
        var renewal=rig.graph.codec.model(rig.operations.heartbeat(rig.gateway.tenant(),tx->"device",old.id(),
            rig.graph.codec.json(new LeaseHeartbeat(old.id(),old.reservedNonce(),null,null))).body(),LeaseRenewal.class);
        assertTrue(renewal.cancelRequested());
        assertTrue(renewal.leaseExpiresAtUnixMs()<=rig.clock.millis());
    }

    @Test void changedMappedRequestIsTypedAuditedAndDoesNotSpendConsent()throws Exception{
        var i=submit("audited-mismatch");approve(i.id());var running=rig.consume(rig.reserve(invocation(i.id())));
        var changed=(ObjectNode)EffectGrantTest.JSON.readTree(rig.graph.codec.json(request(running)));
        ((ObjectNode)changed.path("httpRequestManifest")).put("bodyDigest",EffectLedgerTest.RESULT);
        changed.put("httpRequestDigest",Canonical.arguments(changed.path("httpRequestManifest")));
        var failure=assertThrows(Failure.class,()->fixture.grants.issue(rig.gateway.tenant(),tx->"device","a".repeat(64),changed.toString()));
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,failure.detail());
        var events=rig.store.transaction(rig.gateway.tenant(),false,tx->(com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value(tx.auditPage(0,500))).path("items");
        assertTrue(java.util.stream.StreamSupport.stream(events.spliterator(),false).anyMatch(event->
            event.path("operation").asText().equals("EFFECT_CONSENT_MISMATCH")&&event.path("target").asText().equals("invocation:"+i.id())));
        assertEquals(200,fixture.send(grant(running),"handler").status());
    }

    @Test void unsignedCallerAuthenticationCannotReplaceIndependentExactReview(){
        var i=submit("independent-consent");var v=view(i.id());
        assertEquals(403,assertThrows(Failure.class,()->rig.operations.decideEffectConsent(owner,i.id(),
            rig.graph.codec.json(new EffectConsentApprovalRequest(v.previewDigest(),v.revision())),false)).status());
        assertEquals(EffectConsentState.PENDING,view(i.id()).state());
        assertEquals(EffectConsentState.APPROVED,approve(i.id()).state());
    }

    @Test void scopedAgentOwnerStillNeedsAnIndependentExactReviewer()throws Exception{
        rig.graph.add(io.ololabs.toolgate.control.domain.Ids.Kind.AGENT,new ControlAgent("agent","Agent",null,true,2L,"admin"));
        rig.graph.add(io.ololabs.toolgate.control.domain.Ids.Kind.TEAM,new ControlTeam("team-b","Reviewers",true,2L,
            java.util.List.of("alice","admin","bob"),java.util.List.of("reviewer-role")));
        rig.saveGraph();
        var document=(ObjectNode)EffectGrantTest.JSON.readTree(rig.graph.codec.json(fixture.base.request("agent-owned-consent")));
        var context=(ObjectNode)document.path("context");
        context.put("mode","DELEGATED").put("agentId","agent").put("workloadBindingId","workload")
            .put("credentialEpoch",1).put("credentialSha256",EnterpriseConformanceTest.PROOF);
        var i=rig.graph.codec.model(rig.operations.submit(rig.gateway,document.toString()).body(),EnterpriseInvocation.class);
        var v=view(i.id());String request=rig.graph.codec.json(new EffectConsentApprovalRequest(v.previewDigest(),v.revision()));
        assertEquals(403,assertThrows(Failure.class,()->rig.operations.decideEffectConsent(rig.reviewer,i.id(),request,false)).status());
        var independent=new DirectoryService.Actor(rig.gateway.tenant(),"e".repeat(64),true,false,"bob");
        assertEquals(200,rig.operations.decideEffectConsent(independent,i.id(),request,false).status());
        assertEquals(EnterpriseInvocationState.QUEUED,invocation(i.id()).state());
    }

    @Test void operationMetadataReadDoesNotRevealTheFullEffectPayload(){
        var rules=java.util.List.of(
            new EnterpriseManagementRule(java.util.List.of("operation.read"),EnterpriseGroupType.TOOL_GROUP,new GroupSelection(java.util.List.of("tools-a"),false),java.util.List.of(),rig.graph.conditions(),null),
            new EnterpriseManagementRule(java.util.List.of("operation.read"),EnterpriseGroupType.DEVICE_GROUP,new GroupSelection(java.util.List.of("devices-a"),false),java.util.List.of(),rig.graph.conditions(),null));
        rig.graph.add(io.ololabs.toolgate.control.domain.Ids.Kind.ROLE,new ControlRole("preview-reader","Metadata reader",true,1L,UserRole.ADMINISTRATOR,EnterpriseRoleType.MANAGEMENT,rules));
        rig.graph.add(io.ololabs.toolgate.control.domain.Ids.Kind.TEAM,new ControlTeam("preview-readers","Metadata readers",true,1L,java.util.List.of("bob"),java.util.List.of("preview-reader")));
        rig.saveGraph();var i=submit("private-effect-preview");
        var reader=new DirectoryService.Actor(rig.gateway.tenant(),"e".repeat(64),true,false,"bob");
        assertEquals(200,rig.operations.get(reader,i.id()).status());
        assertEquals(403,assertThrows(Failure.class,()->rig.operations.effectConsent(reader,i.id())).status());
        assertEquals(200,rig.operations.effectConsent(owner,i.id()).status());
        assertEquals(200,rig.operations.effectConsent(rig.reviewer,i.id()).status());
    }

}
