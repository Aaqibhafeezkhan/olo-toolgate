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
    JdbcEffectClaims.Acquisition claim(JdbcEffectClaims.Binding b)throws Exception{return tx(c->claims.claim(c,b));}
    JdbcEffectClaims.Snapshot inspect(JdbcEffectClaims.Binding b)throws Exception{return tx(c->claims.inspect(c,b.tenant(),b.effectId()));}
    void replicaRace()throws Exception{
        var b=binding();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        tx(c->{try(var q=c.createStatement()){q.executeUpdate("CREATE TABLE IF NOT EXISTS business_effects(id VARCHAR(128) PRIMARY KEY)");}return null;});
        var pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<JdbcEffectClaims.Acquisition>> results=new ArrayList<>();
            for(int n=0;n<2;n++)results.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                var acquisition=tx(c->new JdbcEffectClaims().claim(c,b));
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
        try(var c=user==null?DriverManager.getConnection(url):DriverManager.getConnection(url,user,password)){assertThrows(IllegalArgumentException.class,()->claims.claim(c,b));}
    }
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
            replicaRace();pausedOwner();applyTakeoverRace();atomicCommitAndRollback();uncertainApplying();bindingConflicts();
        }finally{
            try(var c=DriverManager.getConnection(base,"control_migrator",secret);var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }
}
