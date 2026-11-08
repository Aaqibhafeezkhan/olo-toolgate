// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual fresh SQL and runtime-role checks. Crypto authority is qualified separately. */
class KillStorageTest {
    @TempDir Path temp;
    final ContractCodec codec=new ContractCodec();
    static final String SIGNATURE="e30.e30.c2ln";
    long advance(Store.Session tx){
        var d=tx.load();tx.save(d,new Directory(d.revision()+1,d.entries()));return tx.enterprise().authorizationEpoch();
    }
    KillStore.Event kill(Store.Session tx,Ids.TenantId tenant,String issuer,KillMode mode){
        long n=tx.kills().nextCounter(issuer),epoch=advance(tx);String id=issuer+":"+n;
        var model=new KillEvent(id,KillIssuer.valueOf(issuer.replace('-','_')),new KillScope(KillScopeKind.TENANT,null),
            mode,"Emergency test",1000L,SIGNATURE,tenant.value(),epoch);
        return new KillStore.Event(id,issuer,n,"KILL","TENANT",null,mode.name(),epoch,1000,codec.json(model));
    }
    void matrix(Store store,javax.sql.DataSource source,boolean postgres)throws Exception{
        var tenant=io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-a");
        var other=io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-b");
        store.transaction(tenant,false,tx->tx.load());store.transaction(other,false,tx->tx.load());
        long before=store.transaction(tenant,false,tx->tx.enterprise().authorizationEpoch());
        var event=store.transaction(tenant,true,tx->{var e=kill(tx,tenant,"control",KillMode.TERMINATE);tx.kills().append(e);return e;});
        assertEquals(event,store.transaction(tenant,false,tx->tx.kills().byId(event.id())));
        assertNull(store.transaction(other,false,tx->tx.kills().byId(event.id())));
        assertEquals(List.of(event),store.transaction(tenant,false,tx->tx.kills().effective()));
        assertTrue(store.<Boolean>transaction(tenant,false,tx->tx.kills().killOnlySince(before,event.epoch())));
        assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{tx.kills().append(event);return null;}));
        assertThrows(Failure.class,()->store.transaction(other,true,tx->{tx.kills().append(event);return null;}));
        assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{tx.kills().archive(event.id(),2000);return null;}));
        var second=store.transaction(tenant,true,tx->{var e=kill(tx,tenant,"control-breakglass",KillMode.STOP_NEW);tx.kills().append(e);return e;});
        assertEquals(1,second.counter());
        assertTrue(store.<Boolean>transaction(tenant,false,tx->tx.kills().killOnlySince(before,second.epoch())));
        assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{tx.kills().supersede(event.id(),second.id());return null;}));
        var recovery=store.transaction(tenant,true,tx->{
            long n=tx.kills().nextCounter("control"),epoch=advance(tx);String id="control:"+n;
            var model=new RecoveryEvent(id,"control",List.of(event.id()),List.of("reviewer-1","reviewer-2"),
                2000L,SIGNATURE,tenant.value(),epoch);
            var e=new KillStore.Event(id,"control",n,"RECOVERY",null,null,null,epoch,2000,codec.json(model));
            tx.kills().append(e);tx.kills().supersede(event.id(),id);return e;
        });
        assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{tx.kills().supersede(second.id(),recovery.id());return null;}));
        assertEquals(List.of(second),store.transaction(tenant,false,tx->tx.kills().effective()));
        store.transaction(tenant,true,tx->{tx.kills().archive(event.id(),3000);tx.kills().archive(recovery.id(),3000);return null;});
        assertEquals(List.of(second),store.transaction(tenant,false,tx->tx.kills().visible()));
        assertEquals(3L,store.<Long>transaction(tenant,false,tx->tx.kills().nextCounter("control")));
        // Archived rows remain immutable and still account for their original epochs.
        assertEquals(event,store.transaction(tenant,false,tx->tx.kills().byId(event.id())));
        assertTrue(store.<Boolean>transaction(tenant,false,tx->tx.kills().killOnlySince(before,recovery.epoch())));
        long changed=store.transaction(tenant,true,this::advance);
        assertFalse(store.<Boolean>transaction(tenant,false,tx->tx.kills().killOnlySince(before,changed)));
        assertFalse(store.<Boolean>transaction(tenant,false,tx->tx.kills().killOnlySince(changed,before)));
        var ack=new KillStore.Acknowledgement("TOOL_HOST","host",changed,3000,"POLL");
        store.transaction(tenant,true,tx->{tx.kills().acknowledge(ack);return null;});
        assertEquals(List.of(ack),store.transaction(tenant,false,tx->tx.kills().acknowledgements()));
        for(var invalid:List.of(new KillStore.Acknowledgement("TOOL_HOST","host",changed-1,4000,"PUSH"),
                new KillStore.Acknowledgement("TOOL_HOST","host",changed,2999,"PUSH"),
                new KillStore.Acknowledgement("ADMIN","host",changed,4000,"POLL"))){
            assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{tx.kills().acknowledge(invalid);return null;}));
        }
        // Exercise SQL guards directly, independently of the adapter's monotonic precheck.
        for(String mutation:List.of("component_id='forged'","component_kind='ENDPOINT'","acked_at=acked_at-1","acked_epoch=acked_epoch-1")){
            try(var connection=source.getConnection()){
                connection.setAutoCommit(false);
                if(postgres)try(var scope=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){
                    scope.setString(1,tenant.value());scope.execute();
                }
                try(var update=connection.prepareStatement("UPDATE control_kill_acks SET "+mutation+" WHERE tenant_id=?")){
                    update.setString(1,tenant.value());assertThrows(java.sql.SQLException.class,update::executeUpdate);
                }
                connection.rollback();
            }
        }
        assertTrue(store.transaction(other,false,tx->tx.kills().acknowledgements()).isEmpty());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->store.transaction(tenant,true,tx->{var e=kill(tx,tenant,"control",KillMode.TERMINATE);tx.kills().append(e);return e;}));
            var b=pool.submit(()->store.transaction(tenant,true,tx->{var e=kill(tx,tenant,"control",KillMode.TERMINATE);tx.kills().append(e);return e;}));
            var left=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var right=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(left.counter(),right.counter());assertNotEquals(left.epoch(),right.epoch());
        }finally{pool.shutdownNow();}
        if(postgres)try(var c=source.getConnection()){
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){q.setString(1,tenant.value());q.execute();}
            try(var q=c.prepareStatement("SELECT COUNT(*) FROM control_kill_events WHERE tenant_id<>?")){q.setString(1,tenant.value());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getLong(1));}}
            assertThrows(java.sql.SQLException.class,()->{try(var q=c.createStatement()){q.executeUpdate("UPDATE control_kill_events SET mode='STOP_NEW'");}});
            c.rollback();
        }
    }
    static void dropDatabase(String base,String password,String database)throws Exception{
        // Closing a JDBC socket can precede the server backend's exit. Wait for owned
        // connections to close; the migration role must not gain process-kill privilege.
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){
            while(true)try{q.execute("DROP DATABASE "+database);return;}
            catch(java.sql.SQLException busy){
                if(!"55006".equals(busy.getSQLState())||System.nanoTime()>=deadline)throw busy;
                Thread.sleep(50);
            }
        }
    }
    @Test void sqliteJournalIsGrowOnlyTenantBoundAndConcurrent()throws Exception{
        var source=SqliteState.open(temp.resolve("kills.sqlite"));
        matrix(new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),codec),source,false);
    }
    @Test void postgresRuntimeRolesEnforceTheSameJournal()throws Exception{
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        Assumptions.assumeTrue(base!=null&&password!=null,"Requires owned PostgreSQL");
        String database="kill_store_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var q=c.createStatement()){q.execute("CREATE DATABASE "+database);}
        try{
            String url=base.replace("/control?","/"+database+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var source=new org.postgresql.ds.PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
            matrix(new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source),codec),source,true);
        }finally{dropDatabase(base,password,database);}
    }
}
