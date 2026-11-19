// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real fresh stores: target witnesses never come from the worker completion report. */
class NativeFileEffectsTest {
    @TempDir Path temp;
    @BeforeAll static void keys()throws Exception{EnterpriseOperationTest.signingKey();}
    private record Run(EnterpriseOperationTest rig,EnterpriseInvocation invocation,RemoteToolTask task,NativeFileEffectBegin begin){}
    private Run run(boolean postgres)throws Exception{
        var factory=new DeviceFileReadAdmissionTest();factory.temp=temp;var rig=factory.rig(postgres,true);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{
            tx.nativeFiles().qualify("qualified-files",new NativeFileQualification("device","devices-a","sha256:"+"9".repeat(64),rig.clock.millis()+86400000,"UNFENCED_FILESYSTEM"));return null;
        });
        var original=rig.request("native-"+UUID.randomUUID(),"data/report.txt");
        var args=new HashMap<>(original.request().arguments());args.put("text",(com.fasterxml.jackson.databind.JsonNode)rig.graph.codec.value("\"summary\""));
        var request=new AuthorizationRequest("tool","write",args);
        var submitted=rig.graph.codec.model(rig.operations.submit(rig.gateway,rig.graph.codec.json(new EnterpriseInvocationRequest(original.context(),request,original.toolDigest(),original.packageDigest(),null,null,null))).body(),EnterpriseInvocation.class);
        var reserved=rig.reserve(submitted);var invocation=rig.consume(reserved);
        var task=new RemoteToolTask(invocation.id(),"lease-files",request,invocation.expiresAtUnixMs(),invocation,reserved.permit(),null,null,false);
        var begin=new NativeFileEffectBegin(invocation.id(),task.leaseId(),invocation.reservedNonce(),1L,new NativeFileIntent(NativeFileOperation.WRITE,"data/report.txt",null,"sha256:"+DirectoryService.digest("summary")));
        return new Run(rig,invocation,task,begin);
    }
    private NativeFileEffectGrant begin(Run r,NativeFileEffectBegin v){return r.rig.store.transaction(r.rig.gateway.tenant(),true,tx->NativeFiles.begin(tx,r.rig.graph.codec,r.task,r.invocation,v,r.rig.clock.millis()));}
    private void receipt(Run r,NativeFileEffectReceipt v){r.rig.store.transaction(r.rig.gateway.tenant(),true,tx->{NativeFiles.receipt(tx,r.rig.graph.codec,r.invocation,v,r.rig.clock.millis());return null;});}
    private void close(Run r){r.rig.store.transaction(r.rig.gateway.tenant(),true,tx->{NativeFiles.close(tx,r.rig.graph.codec,r.invocation,new NativeFileScopeClosed(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce()),r.rig.clock.millis());return null;});}
    private EnterpriseOperationOutcome finish(Run r){
        var report=new EnterpriseEffectReport(r.invocation.id(),r.invocation.revision(),EnterpriseInvocationState.SUCCEEDED,r.invocation.evaluation().resources(),"3".repeat(64));
        r.rig.operations.report(r.rig.gateway.tenant(),"device",r.rig.graph.codec.json(report));
        return r.rig.store.transaction(r.rig.gateway.tenant(),true,tx->new OperationFinalizer(r.rig.graph.codec).refresh(tx,r.invocation.id(),r.rig.clock.millis()));
    }
    private void scenario(boolean pg)throws Exception{
        var r=run(pg);var grant=begin(r,r.begin);assertEquals(grant,begin(r,r.begin));
        var witness=new NativeFileEffectReceipt(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce(),grant.effectId(),null,r.begin.intent().contentSha256());
        receipt(r,witness);receipt(r,witness);close(r);
        var outcome=finish(r);assertEquals(EnterpriseOperationState.SUCCEEDED,outcome.state());assertTrue(outcome.finalized());
        assertEquals(1L,outcome.effectCount());assertEquals(1L,outcome.unfencedEffectCount());assertEquals(0L,outcome.fencedEffectCount());assertFalse(outcome.retryable());
        r.rig.store.transaction(r.rig.gateway.tenant(),false,tx->{assertEquals(List.of(grant.effectId()),tx.enterprise().manifestEffects(r.invocation.id(),r.invocation.reservedNonce()));assertEquals(1,tx.enterprise().effectHistory(r.invocation.id(),0,100).size());return null;});
    }
    @Test void sqliteNativeWriteIsIntentThenWitnessThenClosure()throws Exception{scenario(false);}
    @Test void postgresNativeWriteIsIntentThenWitnessThenClosure()throws Exception{scenario(true);}
    @Test void missingReceiptNeverFinalizesAndCannotClose()throws Exception{var r=run(false);begin(r,r.begin);assertThrows(Failure.class,()->close(r));assertEquals(EnterpriseOperationState.OUTCOME_UNKNOWN,finish(r).state());}
    @Test void workerSuccessWithoutAnyBrokerEffectIsUnknown()throws Exception{var r=run(false);assertThrows(Failure.class,()->close(r));assertEquals(EnterpriseOperationState.OUTCOME_UNKNOWN,finish(r).state());}
    @Test void receiptWithoutClosureDoesNotQualifyTheAttempt()throws Exception{var r=run(false);var g=begin(r,r.begin);receipt(r,new NativeFileEffectReceipt(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce(),g.effectId(),null,r.begin.intent().contentSha256()));assertEquals(EnterpriseOperationState.OUTCOME_UNKNOWN,finish(r).state());}
    @Test void changedArgumentsOrFencesAreDeniedBeforeEffectAdmission()throws Exception{
        var r=run(false);
        assertThrows(Failure.class,()->begin(r,new NativeFileEffectBegin(r.begin.requestId(),r.begin.leaseId(),r.begin.fencingToken(),1L,new NativeFileIntent(NativeFileOperation.WRITE,"data/elsewhere.txt",null,r.begin.intent().contentSha256()))));
        assertThrows(Failure.class,()->begin(r,new NativeFileEffectBegin(r.begin.requestId(),r.begin.leaseId(),"another-fence",1L,r.begin.intent())));
        assertThrows(Failure.class,()->begin(r,new NativeFileEffectBegin(r.begin.requestId(),r.begin.leaseId(),r.begin.fencingToken(),1L,new NativeFileIntent(NativeFileOperation.WRITE,"data/report.txt",null,"sha256:"+"f".repeat(64)))));
        boolean empty=r.rig.store.transaction(r.rig.gateway.tenant(),false,tx->tx.nativeFiles().intents(r.invocation.id()).isEmpty());assertTrue(empty);
    }
    @Test void foreignTenantCannotObserveOrSupplyNativeEvidence()throws Exception{
        var r=run(false);var g=begin(r,r.begin);
        var foreign=io.ololabs.toolgate.control.fixtures.Tenants.id("other");
        r.rig.store.transaction(foreign,true,tx->{
            assertNull(tx.nativeFiles().qualification("qualified-files"));assertNull(tx.nativeFiles().intent(g.effectId()));assertTrue(tx.nativeFiles().intents(r.invocation.id()).isEmpty());
            assertThrows(Failure.class,()->NativeFiles.receipt(tx,r.rig.graph.codec,r.invocation,new NativeFileEffectReceipt(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce(),g.effectId(),null,r.begin.intent().contentSha256()),r.rig.clock.millis()));return null;
        });
    }
    @Test void closureRefusesAdditionalMutationsAndConflictingWitnessesRequireReconciliation()throws Exception{
        var r=run(false);var g=begin(r,r.begin);var witness=new NativeFileEffectReceipt(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce(),g.effectId(),null,r.begin.intent().contentSha256());
        receipt(r,witness);close(r);
        assertThrows(Failure.class,()->begin(r,new NativeFileEffectBegin(r.begin.requestId(),r.begin.leaseId(),r.begin.fencingToken(),2L,r.begin.intent())));
        receipt(r,new NativeFileEffectReceipt(r.invocation.id(),r.task.leaseId(),r.invocation.reservedNonce(),g.effectId(),"sha256:"+"a".repeat(64),r.begin.intent().contentSha256()));
        assertEquals(EnterpriseOperationState.RECONCILIATION_REQUIRED,finish(r).state());
    }
    @Test void revokedQualificationDeniesSubsequentEffects()throws Exception{var r=run(false);r.rig.store.transaction(r.rig.gateway.tenant(),true,tx->{tx.nativeFiles().revoke("qualified-files");return null;});assertThrows(Failure.class,()->begin(r,r.begin));}
}
