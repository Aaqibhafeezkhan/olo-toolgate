// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EffectLedgerTest {
    @TempDir Path temp;EnterpriseOperationTest rig;EnterpriseInvocation invocation;
    static final String DIGEST="sha256:"+"a".repeat(64),RESULT="sha256:"+"b".repeat(64);
    @BeforeAll static void key()throws Exception{EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup(){rig=new EnterpriseOperationTest();rig.temp=temp;rig.setup();start();}
    void start(){invocation=rig.consume(rig.reserve(rig.submit("effect-operation")));}
    long now(){return rig.clock.millis();}
    EffectStore.Binding binding(String nonce){return new EffectStore.Binding("effect-1",invocation.id(),invocation.reservedNonce(),"approved-route",DIGEST,"device","broker-1","handler-1",nonce,now()+10000,now()+60000);}
    EffectStore.Authorization authorize(String nonce){return rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().authorize(binding(nonce),now()));}
    EffectStore.Generation get(String nonce){return rig.store.transaction(rig.gateway.tenant(),false,tx->tx.effects().byNonce(nonce));}
    @Test void releasedGenerationsMayReopenButTheirOldNoncesNeverRegainAuthority(){
        assertTrue(authorize("auth-1").newAuthority());assertFalse(authorize("auth-1").newAuthority());
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().release("auth-1","device","broker-1","handler-1",now()));
        var next=authorize("auth-2");assertTrue(next.newAuthority());assertEquals(2,next.generation().generation());
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().consume("auth-1",DIGEST,"target","claim",now())));
        assertEquals("RELEASED",get("auth-1").state());
    }
    @Test void mismatchedRequestAndDispatchOwnersCannotReuseAnEffect(){
        authorize("auth-1");var b=binding("auth-2");
        var changed=new EffectStore.Binding(b.effectId(),b.operationId(),b.attemptId(),b.routeId(),RESULT,b.deviceId(),b.brokerId(),b.handlerId(),b.authNonce(),b.expiresAt(),b.outcomeDeadline());
        assertEquals(409,assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().authorize(changed,now()))).status());
        assertEquals(403,assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().sending("auth-1","other-device","broker-1","handler-1",now()))).status());
        assertEquals("AUTHORIZED",get("auth-1").state());
    }
    @Test void onlyOneConcurrentSenderCanCrossTheSingleSendPoint()throws Exception{
        authorize("auth-1");var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Callable<Boolean>>();for(int n=0;n<2;n++)tasks.add(()->{try{rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().sending("auth-1","device","broker-1","handler-1",now()));return true;}catch(Failure denied){return false;}});
            int sent=0;for(var future:pool.invokeAll(tasks))if(future.get())sent++;assertEquals(1,sent);
        }finally{pool.shutdownNow();}
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().release("auth-1","device","broker-1","handler-1",now())));
    }
    void consumedOutcomeExercise(){
        authorize("auth-1");
        var consumed=rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().consume("auth-1",DIGEST,"target","claim-1",now()));assertTrue(consumed.newAuthority());
        assertFalse(rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().consume("auth-1",DIGEST,"target","claim-1",now()+100000)).newAuthority());
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().consume("auth-1",DIGEST,"target","other-claim",now())));
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effects().observe("auth-1","COMPLETED",RESULT,now());return null;});assertEquals("CONSUMED",get("auth-1").state());
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().expire(now()+60001));assertEquals("AMBIGUOUS",get("auth-1").state());
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().targetOutcome("auth-1","target","claim-1","COMPLETED",RESULT,now()+70000));assertEquals("COMPLETED",get("auth-1").state());
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().targetOutcome("auth-1","target","claim-1","FAILED",DIGEST,now()+80000));assertEquals("CONFLICT",get("auth-1").state());assertFalse(authorize("another-auth").newAuthority());
    }
    @Test void targetConsumptionIsSeparateFromOutcomeAndLateContradictionsAreRetained(){consumedOutcomeExercise();}
    @Test void expiredAuthorizationWithoutReleaseIsAmbiguousAndCannotBeReissued(){
        authorize("auth-1");rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().expire(now()+10001));
        assertEquals("AMBIGUOUS",get("auth-1").state());assertFalse(authorize("another-auth").newAuthority());
    }
    @Test void invocationTerminationAbandonsOutstandingEffects(){
        authorize("auth-1");rig.operations.report(rig.gateway.tenant(),"device",rig.graph.codec.json(new EnterpriseEffectReport(invocation.id(),invocation.revision(),EnterpriseInvocationState.OUTCOME_UNKNOWN,List.of(),null)));
        assertEquals("AMBIGUOUS",get("auth-1").state());
    }
    @Test void aFailedTransactionNeverLeavesAnAuthorizationOrConsumption(){
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effects().authorize(binding("auth-1"),now());throw Failure.conflict();}));assertNull(get("auth-1"));
        authorize("auth-1");assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effects().consume("auth-1",DIGEST,"target","claim",now());throw Failure.conflict();}));assertEquals("AUTHORIZED",get("auth-1").state());
    }
    @Test void generationAndOutcomeRulesAlsoRunOnThePostgresRuntimeRole()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL");
        String database="effects_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        String url=base.replace("/control?","/"+database+"?");org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        rig.store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
        rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);start();consumedOutcomeExercise();
    }
}
