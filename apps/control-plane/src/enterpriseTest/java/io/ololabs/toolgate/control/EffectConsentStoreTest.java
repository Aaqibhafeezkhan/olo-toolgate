// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.contracts.ControlErrorDetailCode;
import io.ololabs.toolgate.contracts.v2.ServiceProfile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EffectConsentStoreTest {
    @TempDir Path temp;
    EffectLedgerTest ledger;EnterpriseOperationTest rig;EffectConsentStore.Pending pending;String mapped;
    long now(){return rig.clock.millis();}
    @BeforeAll static void key()throws Exception{EnterpriseOperationTest.signingKey();}
    @BeforeEach void setup(){ledger=new EffectLedgerTest();ledger.temp=temp;ledger.setup();rig=ledger.rig;}
    void preview(long count)throws Exception{
        var f=new EffectIntentValidationTest().fixture();
        ((ObjectNode)f.profile().path("routes").get(0)).put("id","approved-route");
        ((ObjectNode)f.profile().path("effectReviews").get(0).path("policy")).put("routeId","approved-route");
        var scope=new EffectConsentPreviews.Scope(rig.gateway.tenant().value(),ledger.invocation.id(),"run","example.payments/pay","pay",
            Canonical.arguments(f.args()),List.of(),now()+30000,null);
        var snapshot=EffectConsentPreviews.generic(rig.graph.codec,scope,EffectIntentValidationTest.JSON.treeToValue(f.profile(),ServiceProfile.class),
            f.args(),Map.of("approved-route",count),now());
        var request=snapshot.preview(rig.graph.codec).slots().getFirst().request();
        mapped=Canonical.arguments((JsonNode)rig.graph.codec.value(rig.graph.codec.json(request)));
        pending=rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effectConsents().save(snapshot);return tx.effectConsents().forOperation(ledger.invocation.id());});
    }
    void approve(){rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effectConsents().approve(pending.consentId(),pending.digest(),"admin",tx.enterprise().authorizationEpoch(),now());return null;});}
    EffectStore.Binding binding(String effect,String nonce){
        var b=ledger.binding(nonce);
        return new EffectStore.Binding(effect,b.operationId(),b.attemptId(),b.routeId(),b.requestDigest(),b.deviceId(),b.brokerId(),b.handlerId(),b.authNonce(),b.expiresAt(),b.outcomeDeadline());
    }
    EffectConsentStore.Reservation reserve(Store.Session tx,String effect,String nonce,String digest){
        tx.effects().authorize(binding(effect,nonce),now());
        return tx.effectConsents().reserve(pending.consentId(),0,ledger.invocation.id(),"approved-route",digest,effect,EffectLedgerTest.DIGEST,tx.enterprise().authorizationEpoch(),now());
    }
    EffectConsentStore.Reservation reserve(String effect,String nonce){return rig.store.transaction(rig.gateway.tenant(),true,tx->reserve(tx,effect,nonce,mapped));}
    void rollbackAndReuseExercise()throws Exception{
        preview(1);
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,assertThrows(Failure.class,()->reserve("effect-1","unapproved")).detail());
        assertNull(ledger.get("unapproved"));approve();
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{reserve(tx,"effect-1","rollback",mapped);throw Failure.conflict();}));
        assertNull(ledger.get("rollback"));
        var first=reserve("effect-1","auth-1");
        rig.store.transaction(rig.gateway.tenant(),true,tx->tx.effects().release("auth-1","device","broker-1","handler-1",now()));
        assertEquals(first,reserve("effect-1","auth-2"));
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_EXHAUSTED,assertThrows(Failure.class,()->reserve("effect-2","exhausted")).detail());
        assertNull(ledger.get("exhausted"));
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_MISMATCH,assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->reserve(tx,"effect-2","wrong-intent",EffectLedgerTest.RESULT))).detail());
        assertNull(ledger.get("wrong-intent"));
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effectConsents().revoke(pending.consentId(),"admin",now());return null;});
        assertThrows(Failure.class,()->reserve("effect-1","auth-2"));
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effectConsents().approve(pending.consentId(),pending.digest(),"admin",tx.enterprise().authorizationEpoch(),now());return null;}));
    }
    @Test void consentAndEffectRollbackTogetherAndGenerationsReuseTheirOriginalSlot()throws Exception{rollbackAndReuseExercise();}
    @Test void explicitLargerAllowanceIsBoundedAcrossDifferentEffectIdentities()throws Exception{
        preview(2);approve();reserve("effect-1","auth-1");reserve("effect-2","auth-2");
        assertEquals(ControlErrorDetailCode.EFFECT_CONSENT_EXHAUSTED,assertThrows(Failure.class,()->reserve("effect-3","auth-3")).detail());
        assertNull(ledger.get("auth-3"));
    }
    @Test void wrongPreviewDigestAndExpiredConsentCannotGrantAuthority()throws Exception{
        preview(1);
        assertThrows(Failure.class,()->rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.effectConsents().approve(pending.consentId(),EffectLedgerTest.RESULT,"admin",tx.enterprise().authorizationEpoch(),now());return null;}));
        approve();rig.advanceKeyClock(30000);
        assertThrows(Failure.class,()->reserve("effect-1","expired"));assertNull(ledger.get("expired"));
    }
    @Test void twoConcurrentEffectsCannotSpendTheSameFinalSlot()throws Exception{
        preview(1);approve();var pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=new ArrayList<Callable<Boolean>>();
            for(int n=0;n<2;n++){int index=n;tasks.add(()->{try{reserve("effect-"+index,"auth-"+index);return true;}catch(Failure refused){return false;}});}
            int successes=0;for(var result:pool.invokeAll(tasks))if(result.get())successes++;
            assertEquals(1,successes);
        }finally{pool.shutdownNow();}
    }
    @Test void reservationsAndGenerationReuseAlsoRunOnThePostgresRuntimeRole()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires isolated PostgreSQL");
        String database="consents_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        String url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        rig.store=new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),rig.graph.codec);
        rig.store.transaction(rig.gateway.tenant(),true,tx->{tx.save(tx.load(),rig.graph.directory());return null;});
        EnterpriseConformanceTest.online(rig.store,rig.graph.codec,"device");
        rig.operations=new EnterpriseOperations(rig.store,rig.graph.codec,rig.signer,rig.clock,"control","toolgate-client",60000,100);
        ledger.start();rollbackAndReuseExercise();
    }
}
