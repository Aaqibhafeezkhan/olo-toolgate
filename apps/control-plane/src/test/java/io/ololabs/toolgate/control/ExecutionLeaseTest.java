// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionLeaseTest {
    @TempDir Path directory;
    private static final long NOW=1800000000000L;
    private record Rig(PostgresStore store,ContractCodec codec,Ids.TenantId tenant,EnterpriseOperations operations){}
    private Rig rig(long expiry,String state){
        var codec=new ContractCodec();var store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("leases.sqlite"))),codec);
        var tenant=io.ololabs.toolgate.control.fixtures.Tenants.id("tenant");TestSupport.seed(store,codec,tenant,"root");
        var operations=new EnterpriseOperations(store,codec,null,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC),"control","toolgate-client",60000,256);
        String document="""
            {"id":"inv-1","requestDigest":"%s","evaluation":{"context":{"requestId":"inv-1","tenantId":"tenant","mode":"HUMAN","userId":"ada","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"ololabs.hello-world/hello_world.server_greet","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"%s","revision":1,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[],"reservedNonce":"fence-1","diagnosticId":"diag"}
            """.formatted("a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),state,NOW+600000);
        store.transaction(tenant,true,tx->{tx.enterprise().saveInvocation(codec.model(document,EnterpriseInvocation.class),NOW-1000,0);
            tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease("inv-1","fence-1","host-1",expiry));return null;});
        return new Rig(store,codec,tenant,operations);
    }
    private static String heartbeat(String fence){return "{\"invocationId\":\"inv-1\",\"fencingToken\":\""+fence+"\"}";}
    @Test void expiredLeaseCannotBeResurrectedAndIdentityIsBound(){
        var r=rig(NOW-1,"EXECUTING");
        assertEquals(403,assertThrows(Failure.class,()->r.operations.heartbeat(r.tenant,tx->"host-2","inv-1",heartbeat("fence-1"))).status());
        assertEquals(403,assertThrows(Failure.class,()->r.operations.heartbeat(r.tenant,tx->"host-1","inv-1",heartbeat("stale"))).status());
        var reply=r.operations.heartbeat(r.tenant,tx->"host-1","inv-1",heartbeat("fence-1"));
        var lease=r.codec.model(reply.body(),io.ololabs.toolgate.contracts.v2.LeaseRenewal.class);
        assertTrue(lease.cancelRequested());assertTrue(lease.leaseExpiresAtUnixMs()<=NOW);
        assertTrue(r.codec.model(r.operations.heartbeat(r.tenant,tx->"host-1","inv-1",heartbeat("fence-1")).body(),io.ololabs.toolgate.contracts.v2.LeaseRenewal.class).cancelRequested());
    }
    @Test void cancellationClosesLeaseDurablyAndDoesNotRestoreExecution(){
        var r=rig(NOW+30000,"OUTCOME_UNKNOWN");
        var response=r.codec.model(r.operations.heartbeat(r.tenant,tx->"host-1","inv-1",heartbeat("fence-1")).body(),io.ololabs.toolgate.contracts.v2.LeaseRenewal.class);
        assertTrue(response.cancelRequested());
        var reopened=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("leases.sqlite"))),r.codec);
        reopened.transaction(r.tenant,false,tx->{assertEquals(NOW,tx.enterprise().executionLease("inv-1").expiresAt());assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,tx.enterprise().invocation("inv-1").state());return null;});
    }
    @Test void admissionIdentityCannotBeReboundAndEvidenceSurvivesRestart(){
        var r=rig(NOW+30000,"EXECUTING");
        assertThrows(Failure.class,()->r.store.transaction(r.tenant,true,tx->{tx.enterprise().saveExecutionLease(new EnterpriseStore.ExecutionLease("inv-1","other-fence","host-1",NOW+40000));return null;}));
        r.store.transaction(r.tenant,true,tx->{tx.enterprise().saveExecutionEvidence("inv-1","fence-1","a".repeat(64),"{}",NOW);tx.enterprise().saveExecutionEvidence("inv-1","fence-1","a".repeat(64),"{}",NOW);return null;});
    }
    @Test void watchdogSettlesExpiredExecutionBeforeTheInvocationDeadline(){
        var r=rig(NOW-1,"EXECUTING");
        assertEquals(1,r.operations.sweep(r.tenant));
        r.store.transaction(r.tenant,false,tx->{assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,tx.enterprise().invocation("inv-1").state());return null;});
        assertEquals(0,r.operations.sweep(r.tenant));
    }
    @Test void lateEvidenceIsAcceptedWithoutRestoringAuthorityOrOverwritingTheOutcome(){
        var r=rig(NOW-1,"OUTCOME_UNKNOWN");
        r.store.transaction(r.tenant,true,tx->{
            tx.enterprise().reserveExecutionNonce(new EnterpriseStore.Nonce("fence-1","inv-1","a".repeat(64),0,NOW-1000,NOW-500,null));
            tx.enterprise().consumeNonce("fence-1",NOW-900);
            tx.packages().saveToolHost("host-1","{}");return null;
        });
        String report="{\"invocationId\":\"inv-1\",\"fencingToken\":\"fence-1\",\"outcome\":\"OUTCOME_UNKNOWN\"}";
        assertEquals(403,assertThrows(Failure.class,()->r.operations.reportInvocation(r.tenant,tx->"host-2",report,"fence-1")).status());
        assertEquals(200,r.operations.reportInvocation(r.tenant,tx->"host-1",report,"fence-1").status());
        assertEquals(200,r.operations.reportInvocation(r.tenant,tx->"host-1",report,"fence-1").status());
        r.store.transaction(r.tenant,false,tx->{assertEquals(EnterpriseInvocationState.OUTCOME_UNKNOWN,tx.enterprise().invocation("inv-1").state());assertTrue(tx.enterprise().executionLease("inv-1").expiresAt()<NOW);
            var attempts=tx.enterprise().attempts("inv-1",NOW);assertEquals(1,attempts.size());assertEquals("fence-1",attempts.getFirst().id());assertEquals(1,attempts.getFirst().evidenceCount());
            assertEquals("OUTCOME_UNKNOWN",tx.enterprise().operation("inv-1").state());return null;});
    }
}
