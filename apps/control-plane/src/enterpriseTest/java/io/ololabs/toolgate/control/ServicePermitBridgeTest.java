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
        rig.graph.entries.forEach((id,e)->entries.put(id,rig.graph.codec.entry(id.kind(),e.document()
            .replace("\"read\"","\"memory.append\"").replace("\"write\"","\"memory.read\"")
            .replace("\"FILE\"","\"CUSTOM\"").replace("\"data\"","\""+prefix+"\""))));
        rig.graph.entries=entries;rig.saveGraph();permits=new ServicePermits(rig.store,rig.graph.codec,rig.operations);
    }
    EnterpriseInvocationRequest request(String id){
        var original=rig.request(id,"x");
        var body=rig.graph.codec.model("{\"toolId\":\"tool\",\"action\":\"memory.append\",\"arguments\":{\"path\":\"olo://"+rig.gateway.tenant().value()+"/conversation/c1\"}}",AuthorizationRequest.class);
        var c=original.context();
        var bound=new RequestContext(c.requestId(),c.tenantId(),RequestMode.HUMAN,CredentialKind.STATIC_TOKEN,null,c.userId(),null,null,List.of(),c.sessionEpoch(),null,null,EnterpriseConformanceTest.NOW+15000,c.bindingId(),null);
        return new EnterpriseInvocationRequest(bound,body,original.toolDigest(),original.packageDigest(),null,null);
    }
    Store.Reply submit(EnterpriseInvocationRequest request){return rig.operations.submitHuman(new DirectoryService.Actor(rig.gateway.tenant(),"d".repeat(64),false,false,"alice"),request,"device");}
    EnterpriseInvocation start(String id){
        var i=rig.graph.codec.model(submit(request(id)).body(),EnterpriseInvocation.class);
        return rig.consume(rig.reserve(i));
    }
    ServicePermit issue(EnterpriseInvocation i){return rig.graph.codec.model(permits.issue(rig.gateway.tenant(),tx->"device",KEY,
        rig.graph.codec.json(new ServicePermitIssue(i.id(),(i.reservedNonce()==null?"not-admitted":i.reservedNonce()),ServiceAudience.olo_memory))).body(),ServicePermit.class);}
    ServicePermitConsumption consumption(ServicePermit p){var c=rig.signer.verifyService(p);return new ServicePermitConsumption(p,c.operation(),c.resources(),c.requestFingerprint(),KEY);}
    @Test void admittedIdentityScopeAndBodyReachTheServiceAndTheNonceIsSingleUse(){
        var i=start("service-write");var p=issue(i);assertEquals(p,issue(i));var c=rig.signer.verifyService(p);
        assertEquals("alice",c.sub());assertNull(c.act());assertEquals(rig.gateway.tenant().value(),c.tenantId());
        assertEquals(i.evaluation().resources(),c.resources());assertEquals(i.evaluation().argumentsDigest(),c.requestFingerprint());
        assertEquals(15000,c.exp()-c.iat());
        permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p)));
        assertThrows(Failure.class,()->issue(i));
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
    }
    @Test void revocationAfterIssuanceStopsTheStorageEffect(){
        var p=issue(start("service-revoked"));rig.graph.remove(Ids.Kind.GRANT,"human");rig.saveGraph();
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(consumption(p))));
    }
    @Test void forgedBodyAudienceExecutorAndScopeAreRefused(){
        var p=issue(start("service-forgery"));var good=consumption(p);
        for(var bad:List.of(new ServicePermitConsumption(p,good.operation(),good.resources(),"e".repeat(64),KEY),
            new ServicePermitConsumption(p,good.operation(),good.resources(),good.requestFingerprint(),"e".repeat(64)),
            new ServicePermitConsumption(p,"memory.delete",good.resources(),good.requestFingerprint(),KEY))){
            assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_memory,rig.graph.codec.json(bad)));
        }
        assertThrows(Failure.class,()->permits.consume(rig.gateway,ServiceAudience.olo_rag,rig.graph.codec.json(good)));
        assertThrows(Failure.class,()->permits.issue(rig.gateway.tenant(),tx->"other",KEY,rig.graph.codec.json(new ServicePermitIssue("service-forgery","wrong-fence",ServiceAudience.olo_memory))));
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
        var p=rig.graph.codec.model(bridge.issue(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory))).body(),ServicePermit.class);
        var c=rig.signer.verifyService(p);
        assertEquals(EnterpriseConformanceTest.NOW+15000,c.exp());assertEquals(100,c.exp()-c.iat());
        var expired=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(EnterpriseConformanceTest.NOW+15001),java.time.ZoneOffset.UTC),"control","toolgate-client",60000,100);
        var stopped=new ServicePermits(rig.store,rig.graph.codec,expired);
        assertThrows(Failure.class,()->stopped.issue(rig.gateway.tenant(),tx->"device",KEY,
            rig.graph.codec.json(new ServicePermitIssue(i.id(),i.reservedNonce(),ServiceAudience.olo_memory))));
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
