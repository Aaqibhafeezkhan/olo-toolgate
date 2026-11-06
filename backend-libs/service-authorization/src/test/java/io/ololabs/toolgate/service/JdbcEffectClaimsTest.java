// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JdbcEffectClaimsTest {
    @TempDir Path temp;
    String url,user,password;
    final JdbcEffectClaims claims=new JdbcEffectClaims();
    static final String DIGEST="sha256:"+"a".repeat(64),RESULT="sha256:"+"b".repeat(64);
    @BeforeEach void setup()throws Exception{url="jdbc:sqlite:"+temp.resolve("claims.db");initialize();}
    Connection connection()throws SQLException{
        var c=user==null?DriverManager.getConnection(url):DriverManager.getConnection(url,user,password);
        if(url.startsWith("jdbc:sqlite:"))try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=10000");}
        c.setAutoCommit(false);return c;
    }
    void initialize()throws Exception{try(var c=connection()){JdbcEffectClaims.initialize(c);c.commit();}}
    @FunctionalInterface interface Work<T>{T run(Connection c)throws Exception;}
    <T>T tx(Work<T> work)throws Exception{
        try(var c=connection()){try{T result=work.run(c);c.commit();return result;}catch(Exception failure){c.rollback();throw failure;}}
    }
    JdbcEffectClaims.Binding binding(){String id=UUID.randomUUID().toString();return new JdbcEffectClaims.Binding("tenant",id,1,"auth-"+id,DIGEST);}
    JdbcEffectClaims.Acquisition claim(JdbcEffectClaims.Binding b)throws Exception{return tx(c->claims.claim(c,b, 1001));}
    JdbcEffectClaims.Snapshot inspect(JdbcEffectClaims.Binding b)throws Exception{return tx(c->claims.inspect(c,b.tenant(),b.effectId()));}
    void replicaRace()throws Exception{
        var b=binding();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        tx(c->{try(var q=c.createStatement()){q.executeUpdate("CREATE TABLE IF NOT EXISTS business_effects(id VARCHAR(128) PRIMARY KEY)");}return null;});
        var pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<JdbcEffectClaims.Acquisition>> results=new ArrayList<>();
            for(int n=0;n<2;n++)results.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                var acquisition=tx(c->new JdbcEffectClaims().claim(c,b, 1001));
                if(acquisition.acquired())tx(c->{
                    assertTrue(claims.beginApply(c,acquisition.owner()));
                    try(var q=c.prepareStatement("INSERT INTO business_effects VALUES(?)")){q.setString(1,b.effectId());q.executeUpdate();}
                    assertTrue(claims.finish(c,acquisition.owner(),true,RESULT));return null;
                });
                return acquisition;
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int winners=0;
            for(var f:results){var a=f.get(20,TimeUnit.SECONDS);if(a.acquired())winners++;else assertNull(a.owner());}
            assertEquals(1,winners);assertEquals(JdbcEffectClaims.State.DONE,inspect(b).state());
            assertEquals(1,tx(c->{try(var q=c.prepareStatement("SELECT COUNT(*) FROM business_effects WHERE id=?")){
                q.setString(1,b.effectId());try(var rows=q.executeQuery()){rows.next();return rows.getInt(1);}
            }}).intValue());
        }finally{pool.shutdownNow();}
    }
    void pausedOwner()throws Exception{
        var b=binding();var a=claim(b);
        var recovery=inspect(b);assertTrue(this.<Boolean>tx(c->claims.abandon(c,recovery)));
        assertFalse(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        assertFalse(this.<Boolean>tx(c->claims.finish(c,a.owner(),true,RESULT)));
        var abandoned=inspect(b);assertEquals(JdbcEffectClaims.State.ABANDONED,abandoned.state());
        assertEquals(2,abandoned.ownershipGeneration());assertFalse(claim(b).acquired());
    }
    void applyTakeoverRace()throws Exception{
        var b=binding();var a=claim(b);var expected=inspect(b);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var apply=pool.submit(()->{start.await();return tx(c->claims.beginApply(c,a.owner()));});
            var recover=pool.submit(()->{start.await();return tx(c->claims.abandon(c,expected));});
            start.countDown();assertNotEquals(apply.get(20,TimeUnit.SECONDS),recover.get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        var state=inspect(b).state();assertTrue(Set.of(JdbcEffectClaims.State.APPLYING,JdbcEffectClaims.State.ABANDONED).contains(state));
        assertFalse(claim(b).acquired());
    }
    void atomicCommitAndRollback()throws Exception{
        var b=binding();var a=claim(b);
        tx(c->{try(var s=c.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS business_effects(id VARCHAR(128) PRIMARY KEY)");}return null;});
        try(var c=connection()){
            assertTrue(claims.beginApply(c,a.owner()));
            try(var s=c.prepareStatement("INSERT INTO business_effects VALUES(?)")){s.setString(1,b.effectId());s.executeUpdate();}
            assertTrue(claims.finish(c,a.owner(),true,RESULT));c.rollback();
        }
        assertEquals(JdbcEffectClaims.State.CLAIMED,inspect(b).state());
        tx(c->{
            assertTrue(claims.beginApply(c,a.owner()));
            try(var s=c.prepareStatement("INSERT INTO business_effects VALUES(?)")){s.setString(1,b.effectId());s.executeUpdate();}
            assertTrue(claims.finish(c,a.owner(),true,RESULT));return null;
        });
        var reopened=new JdbcEffectClaims();
        tx(c->{assertEquals(JdbcEffectClaims.State.DONE,reopened.inspect(c,b.tenant(),b.effectId()).state());return null;});
        assertEquals(RESULT,inspect(b).resultDigest());assertFalse(claim(b).acquired());
        assertFalse(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        assertFalse(this.<Boolean>tx(c->claims.finish(c,a.owner(),false,DIGEST)));
        assertEquals(1,tx(c->{try(var s=c.prepareStatement("SELECT COUNT(*) FROM business_effects WHERE id=?")){s.setString(1,b.effectId());try(var rows=s.executeQuery()){rows.next();return rows.getInt(1);}}}).intValue());
    }
    void uncertainApplying()throws Exception{
        var b=binding();var a=claim(b);assertTrue(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        assertFalse(this.<Boolean>tx(c->claims.abandon(c,inspect(b))));
        assertEquals(JdbcEffectClaims.State.APPLYING,inspect(b).state());
        assertFalse(claim(b).acquired());
    }
    void bindingConflicts()throws Exception{
        var b=binding();claim(b);
        var forged=new JdbcEffectClaims.Owner(b,1,"not-the-owner");
        assertFalse(this.<Boolean>tx(c->claims.beginApply(c,forged)));
        assertFalse(this.<Boolean>tx(c->claims.finish(c,forged,true,RESULT)));
        assertThrows(SecurityException.class,()->claim(new JdbcEffectClaims.Binding(b.tenant(),b.effectId(),1,b.authNonce(),RESULT)));
        assertThrows(SecurityException.class,()->claim(new JdbcEffectClaims.Binding(b.tenant(),b.effectId(),2,"another",DIGEST)));
        assertThrows(SecurityException.class,()->claim(new JdbcEffectClaims.Binding(b.tenant(),"other-effect",1,b.authNonce(),DIGEST)));
        assertTrue(claim(new JdbcEffectClaims.Binding("other-tenant",b.effectId(),1,b.authNonce(),DIGEST)).acquired());
        try(var c=user==null?DriverManager.getConnection(url):DriverManager.getConnection(url,user,password)){assertThrows(IllegalArgumentException.class,()->claims.claim(c,b, 1001));}
    }
    void durableOutcomeDelivery()throws Exception{
        var b=binding();var a=tx(c->claims.claim(c,b,100));
        assertTrue(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        assertTrue(this.<Boolean>tx(c->claims.finish(c,a.owner(),true,RESULT)));
        var pending=tx(c->claims.pendingOutcomes(c,b.tenant(),100)).stream().filter(p->p.binding().equals(b)).toList();
        assertEquals(1,pending.size());var report=pending.getFirst();
        assertEquals(report,tx(c->claims.pendingOutcome(c,b)));
        var seen=new HashSet<String>();String cursor=null;
        for(int page=0;page<1000;page++){
            String after=cursor;var batch=tx(c->claims.pendingOutcomes(c,b.tenant(),after,1));
            if(batch.isEmpty())break;
            assertEquals(1,batch.size());cursor=batch.getFirst().binding().effectId();assertTrue(seen.add(cursor));
        }
        assertTrue(seen.contains(b.effectId()));
        assertEquals(a.owner().claimToken(),report.claimToken());assertEquals(RESULT,report.resultDigest());
        assertFalse(a.owner().toString().contains(a.owner().claimToken()));assertFalse(report.toString().contains(report.claimToken()));
        assertTrue(this.<Boolean>tx(c->claims.acknowledgeOutcome(c,report)));
        assertFalse(this.<Boolean>tx(c->claims.acknowledgeOutcome(c,report)));
        assertNull(tx(c->claims.pendingOutcome(c,b)));
        assertTrue(tx(c->claims.pendingOutcomes(c,b.tenant(),100)).stream().noneMatch(p->p.binding().equals(b)));
        assertFalse(claim(b).acquired());assertEquals(JdbcEffectClaims.State.DONE,inspect(b).state());
    }
    void expiredClaimRecovery()throws Exception{
        var active=binding();tx(c->claims.claim(c,active,1001));
        var expired=binding();var owner=tx(c->claims.claim(c,expired,100)).owner();
        var found=tx(c->claims.expiredClaims(c,expired.tenant(),1000,100));
        assertTrue(found.stream().anyMatch(p->p.binding().equals(expired)));
        assertTrue(found.stream().noneMatch(p->p.binding().equals(active)));
        var observed=inspect(expired);
        try(var c=connection()){assertTrue(claims.abandon(c,observed));c.rollback();}
        assertEquals(JdbcEffectClaims.State.CLAIMED,inspect(expired).state());
        assertTrue(tx(c->claims.pendingOutcomes(c,expired.tenant(),100)).stream().noneMatch(p->p.binding().equals(expired)));
        assertTrue(this.<Boolean>tx(c->claims.abandon(c,observed)));
        assertFalse(this.<Boolean>tx(c->claims.beginApply(c,owner)));
        var pending=tx(c->claims.pendingOutcomes(c,expired.tenant(),100)).stream().filter(p->p.binding().equals(expired)).toList();
        assertEquals(1,pending.size());assertEquals("FAILED",pending.getFirst().outcome().name());
        var applying=binding();var applyingOwner=tx(c->claims.claim(c,applying,100)).owner();
        assertTrue(this.<Boolean>tx(c->claims.beginApply(c,applyingOwner)));
        assertTrue(tx(c->claims.expiredClaims(c,applying.tenant(),1000,100)).stream().noneMatch(p->p.binding().equals(applying)));
        assertEquals(JdbcEffectClaims.State.APPLYING,inspect(applying).state());
    }
    void oldClaimSchemasAreRejectedWithoutConversion()throws Exception{
        var b=binding();claim(b);
        tx(c->{try(var q=c.createStatement()){
            q.executeUpdate("DROP TABLE toolgate_target_schema");
            q.executeUpdate("DROP TABLE toolgate_target_outcomes");
            q.executeUpdate("ALTER TABLE toolgate_target_claims DROP COLUMN authorization_expires_at");
        }return null;});
        assertThrows(SQLException.class,this::initialize);
        assertEquals(JdbcEffectClaims.State.CLAIMED,inspect(b).state());
        try(var c=connection();var q=c.createStatement()){
            assertThrows(SQLException.class,()->q.executeQuery("SELECT authorization_expires_at FROM toolgate_target_claims"));
        }
    }
    void externalApplyCrash()throws Exception{
        var b=binding();var a=claim(b);
        assertTrue(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        var externalEffects=new java.util.concurrent.atomic.AtomicInteger();
        externalEffects.incrementAndGet(); // Target receipt exists, but SQL finish/outbox is lost in the crash.
        var reopened=new JdbcEffectClaims();
        assertFalse(tx(c->reopened.claim(c,b,1001)).acquired());
        var observed=inspect(b);
        assertEquals(JdbcEffectClaims.State.APPLYING,observed.state());
        assertFalse(this.<Boolean>tx(c->reopened.abandon(c,observed)));
        assertNull(tx(c->reopened.pendingOutcome(c,b)));
        try(var c=connection()){
            assertTrue(reopened.confirmApplied(c,observed,RESULT));c.rollback();
        }
        assertEquals(JdbcEffectClaims.State.APPLYING,inspect(b).state());
        assertTrue(this.<Boolean>tx(c->reopened.confirmApplied(c,observed,RESULT)));
        assertFalse(this.<Boolean>tx(c->reopened.confirmApplied(c,observed,RESULT)));
        assertFalse(this.<Boolean>tx(c->reopened.finish(c,a.owner(),false,DIGEST)));
        assertEquals(1,externalEffects.get());
        assertEquals(JdbcEffectClaims.State.DONE,inspect(b).state());
        var pending=tx(c->reopened.pendingOutcome(c,b));
        assertEquals("COMPLETED",pending.outcome().name());assertEquals(RESULT,pending.resultDigest());
    }
    @Test void externalActionCrashStaysUncertainUntilPositiveTargetReceiptThenOnlyEvidenceIsRecovered()throws Exception{externalApplyCrash();}
    void killedJvmAfterExternalCommit()throws Exception{
        var b=binding();
        tx(c->{try(var q=c.createStatement()){q.executeUpdate("""
            CREATE TABLE IF NOT EXISTS external_business_receipts(
                tenant_id VARCHAR(128),effect_id VARCHAR(128),request_digest VARCHAR(71),result_digest VARCHAR(71),
                PRIMARY KEY(tenant_id,effect_id))
            """);}return null;});
        String cp=Objects.requireNonNull(System.getProperty("toolgate.claimTestClasspath"));
        String executable=Path.of(System.getProperty("java.home"),"bin",
            System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var builder=new ProcessBuilder(executable,"-cp",cp,ClaimCrashProcess.class.getName(),b.effectId()).redirectErrorStream(true);
        builder.environment().put("CLAIM_TEST_URL",url);
        if(user!=null){builder.environment().put("CLAIM_TEST_USER",user);builder.environment().put("CLAIM_TEST_PASSWORD",password);}
        var process=builder.start();
        try{
            assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Target crash fixture exceeded its deadline");
            assertEquals(47,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }finally{if(process.isAlive())process.destroyForcibly();}
        var reopened=new JdbcEffectClaims();var observed=inspect(b);
        assertEquals(JdbcEffectClaims.State.APPLYING,observed.state());
        assertNull(tx(c->reopened.pendingOutcome(c,b)));
        assertFalse(this.<Boolean>tx(c->reopened.abandon(c,observed)));
        assertFalse(tx(c->reopened.claim(c,b,1001)).acquired());
        String receipt=tx(c->{try(var q=c.prepareStatement("""
            SELECT result_digest FROM external_business_receipts WHERE tenant_id=? AND effect_id=? AND request_digest=?
            """)){
            q.setString(1,b.tenant());q.setString(2,b.effectId());q.setString(3,b.requestDigest());
            try(var rows=q.executeQuery()){assertTrue(rows.next());String value=rows.getString(1);assertFalse(rows.next());return value;}
        }});
        assertEquals(RESULT,receipt);
        assertTrue(this.<Boolean>tx(c->reopened.confirmApplied(c,observed,receipt)));
        assertFalse(this.<Boolean>tx(c->reopened.confirmApplied(c,observed,receipt)));
        var outcome=tx(c->reopened.pendingOutcome(c,b));
        assertEquals("COMPLETED",outcome.outcome().name());assertEquals(RESULT,outcome.resultDigest());
    }
    @Test void aRealJvmHaltRetainsApplyingAndRecoversOnlyIndependentBusinessReceipt()throws Exception{killedJvmAfterExternalCommit();}
    void closedFailureReceiptRecovery()throws Exception{
        var b=binding();var a=claim(b);
        assertTrue(this.<Boolean>tx(c->claims.beginApply(c,a.owner())));
        var observed=inspect(b);
        String notStarted="sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("toolgate-target-not-started-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->tx(c->claims.confirmFailed(c,observed,notStarted)));
        assertEquals(JdbcEffectClaims.State.APPLYING,inspect(b).state());
        assertNull(tx(c->claims.pendingOutcome(c,b)));
        tx(c->{try(var q=c.createStatement()){q.executeUpdate("""
            CREATE TABLE IF NOT EXISTS terminal_provider_receipts(tenant_id VARCHAR(128),effect_id VARCHAR(128),
                request_digest VARCHAR(71),result_digest VARCHAR(71),outcome VARCHAR(16) CHECK(outcome IN ('COMPLETED','FAILED')),
                PRIMARY KEY(tenant_id,effect_id))
            """);}
            try(var q=c.prepareStatement("INSERT INTO terminal_provider_receipts VALUES(?,?,?,?,'FAILED')")){
                q.setString(1,b.tenant());q.setString(2,b.effectId());q.setString(3,b.requestDigest());q.setString(4,RESULT);q.executeUpdate();
            }return null;});
        String receipt=tx(c->{try(var q=c.prepareStatement("SELECT result_digest FROM terminal_provider_receipts WHERE tenant_id=? AND effect_id=? AND request_digest=? AND outcome='FAILED'")){
            q.setString(1,b.tenant());q.setString(2,b.effectId());q.setString(3,b.requestDigest());
            try(var rows=q.executeQuery()){assertTrue(rows.next());return rows.getString(1);}
        }});
        // A later provider apply for this identity is fenced by its durable terminal receipt.
        assertThrows(SQLException.class,()->tx(c->{try(var q=c.prepareStatement(
            "INSERT INTO terminal_provider_receipts VALUES(?,?,?,?,'COMPLETED')")){
            q.setString(1,b.tenant());q.setString(2,b.effectId());q.setString(3,b.requestDigest());q.setString(4,DIGEST);q.executeUpdate();
        }return null;}));
        assertTrue(this.<Boolean>tx(c->claims.confirmFailed(c,observed,receipt)));
        assertFalse(this.<Boolean>tx(c->claims.confirmApplied(c,observed,receipt)));
        assertFalse(this.<Boolean>tx(c->claims.finish(c,a.owner(),true,RESULT)));
        assertFalse(claim(b).acquired());
        var outcome=tx(c->claims.pendingOutcome(c,b));
        assertEquals("FAILED",outcome.outcome().name());assertEquals(RESULT,outcome.resultDigest());
        assertEquals(JdbcEffectClaims.State.FAILED,inspect(b).state());
    }
    @Test void applyingRequiresAClosedFailureReceiptAndCannotBecomeANotStartedClaim()throws Exception{closedFailureReceiptRecovery();}
    @Test void outcomesSurviveReopenAndDeliveryAcknowledgementDoesNotReopenClaims()throws Exception{durableOutcomeDelivery();}
    @Test void expiryRecoveryFencesOnlyClaimedWorkAndCommitsItsEvidenceAtomically()throws Exception{expiredClaimRecovery();}
    @Test void oldSchemasHaveNoAutomaticUpgradePath()throws Exception{oldClaimSchemasAreRejectedWithoutConversion();}
    @Test void twoReplicasHaveOneClaimOwner()throws Exception{replicaRace();}
    @Test void pausedOwnerCannotActAfterRecovery()throws Exception{pausedOwner();}
    @Test void applyAndRecoveryHaveOneWinner()throws Exception{applyTakeoverRace();}
    @Test void businessMutationAndCompletionCommitOrRollbackTogether()throws Exception{atomicCommitAndRollback();}
    @Test void applyingRecoveryNeverProvesNotStarted()throws Exception{uncertainApplying();}
    @Test void bindingsTenantsAndTransactionRequirementsAreEnforced()throws Exception{bindingConflicts();}
    @Test void allScenariosAlsoRunAgainstRealPostgres()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),secret=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&secret!=null,"Requires isolated PostgreSQL");
        String database="target_claims_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,"control_migrator",secret);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        try{
            url=base.replace("/control?","/"+database+"?");user="control_migrator";password=secret;initialize();
            replicaRace();pausedOwner();applyTakeoverRace();atomicCommitAndRollback();uncertainApplying();bindingConflicts();durableOutcomeDelivery();expiredClaimRecovery();externalApplyCrash();killedJvmAfterExternalCommit();closedFailureReceiptRecovery();oldClaimSchemasAreRejectedWithoutConversion();
        }finally{
            try(var c=DriverManager.getConnection(base,"control_migrator",secret);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }
}
