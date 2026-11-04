// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.node.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real scoped Control/review/SQL projection. TLS and DNS are qualified separately by the outbound adapter tests. */
class TargetLookupLifecycleTest {
    @TempDir Path temp;
    TargetResendLifecycleTest fixture;EnterpriseOperationTest rig;
    @BeforeAll static void key()throws Exception{TargetResendLifecycleTest.key();}
    @BeforeEach void setup()throws Exception{
        fixture=new TargetResendLifecycleTest();fixture.temp=temp;fixture.setup();enableLookup();
    }
    void enableLookup()throws Exception{
        var grant=fixture.fixture;grant.statusRouteId="lookup";
        var route=(ObjectNode)grant.profile.path("routes").get(0).deepCopy();
        route.put("id","lookup").put("method","GET").put("path","/effects/{effectKey:tg-[a-f0-9]+}").put("maxResponseBytes",65536);
        route.putArray("query");route.putArray("headers");
        ((ArrayNode)grant.profile.path("routes")).add(route);
        var policy=EffectGrantTest.JSON.createObjectNode().put("routeId","lookup").put("classification","READ")
            .put("consentRequired",false).put("acknowledgedUnfencedRisk",false);
        var intent=policy.putObject("intent").put("path","/effects/{effectKey:tg-[a-f0-9]+}").put("contentType","").put("bodyMode","NONE").putNull("body");
        intent.putObject("query");intent.putObject("headers");
        ((ArrayNode)grant.profile.path("effectReviews")).addObject().put("reviewedBy","admin")
            .put("reviewedAtUnixMs",EnterpriseConformanceTest.NOW).set("policy",policy);
        fixture.configure();rig=fixture.rig;
    }
    DirectoryService.Actor operator(){return new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("operator"),true,true,"admin");}
    EnterpriseInvocation ambiguous()throws Exception{
        fixture.original=fixture.fixture.base.request("lookup-"+UUID.randomUUID());
        var i=fixture.start();var grant=fixture.grant(i,"charge");fixture.send(i,grant);return fixture.unknown(i);
    }
    String effect(EnterpriseInvocation i){return EffectIdentity.of(rig.graph.codec,rig.gateway.tenant().value(),i.id(),"write","charge");}
    TargetEffectStatusReply completed(TargetStatusClient.Request request){return new TargetEffectStatusReply(request.targetKey(),
        TargetEffectStatus.COMPLETED,fixture.fixture.issue.httpRequestManifest().bodyDigest(),"provider-receipt-1","sha256:"+"c".repeat(64));}
    TargetLookups lookups(TargetStatusClient client){return new TargetLookups(rig.store,rig.graph.codec,rig.operations,null,client);}
    EnterpriseOperationOutcome outcome(String id){return rig.store.transaction(rig.gateway.tenant(),false,tx->tx.enterprise().latestOperationOutcome(id).value());}
    void noWitness(String effect){assertEquals(Boolean.FALSE,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().hasTerminalHistory(effect)));}
    @Test void witnessedCompletionResolvesOnlyTheEffectAndDoesNotInventWholeOperationSuccess()throws Exception{
        var i=ambiguous();String effect=effect(i);
        var reply=lookups(this::completed).lookup(operator(),i.id(),effect);
        var result=rig.graph.codec.model(reply.body(),TargetEffectLookupResult.class);
        assertEquals(TargetEffectStatus.COMPLETED,result.state());
        assertEquals(EnterpriseOperationState.PARTIAL,outcome(i.id()).state());
        assertEquals(EnterpriseOutcomeGrade.TARGET_CONFIRMED,outcome(i.id()).grade());
        assertTrue(outcome(i.id()).finalized());assertEquals(0L,outcome(i.id()).unresolvedEffectCount());
        assertEquals("AMBIGUOUS",rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().current(effect).state()));
        var binding=rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().targetBinding(effect));
        assertFalse(reply.body().contains(binding.targetKey()));assertFalse(reply.body().contains("provider-receipt-1"));
        var repeated=rig.graph.codec.model(lookups(this::completed).lookup(operator(),i.id(),effect).body(),TargetEffectLookupResult.class);
        assertEquals(result.outcomeRevision(),repeated.outcomeRevision());
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->TargetResends.plan(tx,rig.graph.codec,
            tx.enterprise().invocation(i.id()),rig.clock.millis())));
    }
    @Test void brokerOnlyApiKeyLookupUsesScopedCustodyAndRotationDuringIoFencesTheReply()throws Exception{
        fixture=new TargetResendLifecycleTest();fixture.temp=temp.resolve("broker-only");
        java.nio.file.Files.createDirectories(fixture.temp);fixture.brokerOnly=true;fixture.setup();enableLookup();
        var keys=new TenantVaultKeys(rig.graph.codec,new LocalVaultWrappingProvider("fixture",Map.of("fixture",new byte[32])));
        rig.store.transaction(rig.gateway.tenant(),true,tx->{keys.provision(rig.gateway.tenant(),tx);return null;});
        var vault=new VaultService(rig.store,rig.graph.codec,keys);
        vault.write(operator(),rig.graph.codec.json(new EnterpriseVaultWrite("authonly","private-target-token","tools-a","devices-a")),"lookup-secret");
        var i=ambiguous();String effect=effect(i);var calls=new AtomicInteger();
        var client=new TargetLookups(rig.store,rig.graph.codec,rig.operations,()->vault,request->{
            calls.incrementAndGet();assertEquals(Map.of("x-api-key","private-target-token"),request.credentials());
            assertFalse(request.toString().contains("private-target-token"));
            return completed(request);
        });
        var reply=client.lookup(operator(),i.id(),effect);assertEquals(1,calls.get());
        assertFalse(reply.body().contains("private-target-token"));
        var rotating=new TargetLookups(rig.store,rig.graph.codec,rig.operations,()->vault,request->{
            vault.write(operator(),rig.graph.codec.json(new EnterpriseVaultWrite("authonly","replacement-token","tools-a","devices-a")),"rotate-lookup-secret");
            return completed(request);
        });
        assertThrows(Failure.class,()->rotating.lookup(operator(),i.id(),effect));
        var before=calls.get();assertThrows(Failure.class,()->client.lookup(operator(),i.id(),effect));
        assertEquals(before,calls.get(),"Changed API-key scope is refused before network I/O");
        assertNull(rig.store.transaction(rig.gateway.tenant(),false,tx->TargetResends.plan(tx,rig.graph.codec,
            tx.enterprise().invocation(i.id()),rig.clock.millis())));
    }
    @Test void pendingAndUnknownResponsesPreserveAmbiguityAndGrantNoAuthority()throws Exception{
        var i=ambiguous();String effect=effect(i);
        for(var state:List.of(TargetEffectStatus.UNKNOWN,TargetEffectStatus.PENDING)){
            var reply=lookups(request->new TargetEffectStatusReply(request.targetKey(),state,null,null,null)).lookup(operator(),i.id(),effect);
            assertEquals(state,rig.graph.codec.model(reply.body(),TargetEffectLookupResult.class).state());
            assertEquals(EnterpriseOperationState.OUTCOME_UNKNOWN,outcome(i.id()).state());noWitness(effect);
        }
    }
    @Test void changedKeyOrBodyCannotBecomePositiveEvidence()throws Exception{
        var i=ambiguous();String effect=effect(i);
        for(boolean key:List.of(true,false)){
            var client=lookups(request->new TargetEffectStatusReply(key?"tg-"+"d".repeat(64):request.targetKey(),
                TargetEffectStatus.COMPLETED,key?fixture.fixture.issue.httpRequestManifest().bodyDigest():"sha256:"+"d".repeat(64),
                "provider-receipt-1","sha256:"+"c".repeat(64)));
            assertThrows(Failure.class,()->client.lookup(operator(),i.id(),effect));noWitness(effect);
        }
    }
    @Test void currentReviewerRevocationDuringNetworkIoRejectsTheLateLookup()throws Exception{
        var i=ambiguous();String effect=effect(i);
        var calls=new AtomicInteger();
        var client=lookups(request->{calls.incrementAndGet();fixture.revokeReviewer();return completed(request);});
        assertThrows(Failure.class,()->client.lookup(operator(),i.id(),effect));assertEquals(1,calls.get());noWitness(effect);
    }
    @Test void aCompetingRecoveryCannotBeOverwrittenByALateLookup()throws Exception{
        var i=ambiguous();String effect=effect(i);
        var client=lookups(request->{assertEquals(EnterpriseInvocationState.QUEUED,fixture.submit().state());return completed(request);});
        assertThrows(Failure.class,()->client.lookup(operator(),i.id(),effect));noWitness(effect);
        assertEquals(EnterpriseInvocationState.QUEUED,rig.store.transaction(rig.gateway.tenant(),false,
            tx->tx.enterprise().invocation(i.id())).state());
    }
    @Test void unscopedCallersAreRefusedBeforeContactingTheTarget()throws Exception{
        var i=ambiguous();var calls=new AtomicInteger();
        var client=lookups(request->{calls.incrementAndGet();return completed(request);});
        var caller=new DirectoryService.Actor(rig.gateway.tenant(),DirectoryService.digest("ordinary"),false,false,"bob");
        assertThrows(Failure.class,()->client.lookup(caller,i.id(),effect(i)));assertEquals(0,calls.get());noWitness(effect(i));
    }
    @Test void contradictingProviderReceiptsForceReconciliationAndCannotReopenResend()throws Exception{
        var i=ambiguous();String effect=effect(i);
        lookups(this::completed).lookup(operator(),i.id(),effect);
        lookups(request->new TargetEffectStatusReply(request.targetKey(),TargetEffectStatus.COMPLETED,
            fixture.fixture.issue.httpRequestManifest().bodyDigest(),"different-business-receipt","sha256:"+"e".repeat(64)))
            .lookup(operator(),i.id(),effect);
        assertEquals(EnterpriseOperationState.RECONCILIATION_REQUIRED,outcome(i.id()).state());
        assertFalse(outcome(i.id()).retryable());
    }
    @Test void everyAmbiguousGenerationSharesOneBusinessReceiptAndTheOutcomeRetainsItsDedupeAssumption()throws Exception{
        var first=ambiguous();String effect=effect(first);
        var second=fixture.consumeQualified(rig.reserve(fixture.submit()));
        var grant=fixture.grant(second,"charge");fixture.send(second,grant);fixture.unknown(second);
        lookups(this::completed).lookup(operator(),second.id(),effect);
        var result=outcome(second.id());
        assertEquals(EnterpriseOperationState.PARTIAL,result.state());assertTrue(result.finalized());
        assertTrue(result.deduplicationAssumed());assertEquals(EnterpriseOutcomeGrade.TARGET_DEDUPLICATION_ASSUMED,result.grade());
        assertEquals(2,rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().generations(effect)).size());
        assertEquals(0L,result.unresolvedEffectCount());assertEquals(0L,result.unsettledAttemptCount());
    }
    @Test void rawStorageCannotForgeALookupWitnessWhileAnExecutorHasCurrentAuthority()throws Exception{
        fixture.original=fixture.fixture.base.request("unclosed-lookup-"+UUID.randomUUID());
        var i=fixture.start();var grant=fixture.grant(i,"charge");fixture.send(i,grant);
        var claims=rig.signer.verifyEffect(grant.grant());
        try(var c=rig.keyPolicySource.getConnection()){
            c.setAutoCommit(false);
            if(!c.getMetaData().getDatabaseProductName().equals("SQLite"))try(var set=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                set.setString(1,rig.gateway.tenant().value());set.execute();
            }
            try(var insert=c.prepareStatement("INSERT INTO control_effect_evidence(tenant_id,effect_id,generation,evidence_digest,source,outcome,result_digest,recorded_at) VALUES(?,?,?,?,?,'COMPLETED',?,?)")){
                insert.setString(1,rig.gateway.tenant().value());insert.setString(2,claims.effectId());insert.setLong(3,claims.generation());
                insert.setString(4,"f".repeat(64));insert.setString(5,EffectStore.LOOKUP_SOURCE_PREFIX+"a".repeat(64));
                insert.setString(6,"sha256:"+"c".repeat(64));insert.setLong(7,rig.clock.millis());
                var refused=assertThrows(java.sql.SQLException.class,insert::executeUpdate);
                assertTrue(refused.getMessage().contains("invalid target lookup witness"),refused.getMessage());
            }finally{c.rollback();}
        }
        noWitness(claims.effectId());
    }
    @Test void actualPostgresLookupProjectionConflictsAndResendFencesMatchSqlite()throws Exception{
        fixture.fixture.statusRouteId=null;fixture.usePostgres();enableLookup();
        witnessedCompletionResolvesOnlyTheEffectAndDoesNotInventWholeOperationSuccess();
        contradictingProviderReceiptsForceReconciliationAndCannotReopenResend();
        everyAmbiguousGenerationSharesOneBusinessReceiptAndTheOutcomeRetainsItsDedupeAssumption();
        rawStorageCannotForgeALookupWitnessWhileAnExecutorHasCurrentAuthority();
    }

}
