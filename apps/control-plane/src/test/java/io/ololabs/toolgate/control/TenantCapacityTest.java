// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TenantCapacityTest {
    @TempDir Path temp;
    private record Fixture(javax.sql.DataSource runtime,javax.sql.DataSource platform,TenantRegistry registry,PostgresStore store){}
    private Fixture fixture(boolean postgres)throws Exception {
        javax.sql.DataSource runtime,platform;
        if(postgres){
            var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=System.getenv("CONTROL_TEST_PASSWORD");
            var name="capacity_"+UUID.randomUUID().toString().replace("-","");
            try(var c=DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
            var url=base.replace("/control?","/"+name+"?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var app=new org.postgresql.ds.PGSimpleDataSource();app.setURL(url);app.setUser("control_app");app.setPassword(password);runtime=app;
            var operator=new org.postgresql.ds.PGSimpleDataSource();operator.setURL(url);operator.setUser("control_provisioner");operator.setPassword(password);platform=operator;
        }else{runtime=SqliteState.open(temp.resolve("capacity.sqlite"));platform=runtime;}
        var codec=new ContractCodec();var registry=new TenantRegistry(platform,codec);
        for(var alias:List.of("a","b")){
            var tenant=registry.allocate(alias,alias);
            registry.initialize(tenant.id(),"a".repeat(64),"[]",unit->{TestSupport.seed(unit,codec,tenant.id(),"admin");unit.transaction(tenant.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});});
        }
        return new Fixture(runtime,platform,registry,new PostgresStore(runtime,codec));
    }
    private void sql(Fixture f,TenantId tenant,String sql,Object...args)throws SQLException {
        sql(f.runtime,tenant,sql,args);
    }
    private void sql(javax.sql.DataSource source,TenantId tenant,String sql,Object...args)throws SQLException {
        try(var c=source.getConnection()){
            c.setAutoCommit(false);
            if(!c.getMetaData().getDatabaseProductName().equals("SQLite"))try(var s=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){s.setString(1,tenant.value());s.execute();}
            try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}
            c.commit();
        }
    }
    @Test void sqliteAtomicQuotaAndIndependentTenants()throws Exception{quotas(fixture(false));}
    @Test void postgresAtomicQuotaAndIndependentTenants()throws Exception{quotas(fixture(true));}
    private void quotas(Fixture f)throws Exception {
        var a=f.registry.find("a").id();var b=f.registry.find("b").id();
        f.registry.capacity(a,4096,2,1);
        String insert="INSERT INTO control_builder(tenant_id,kind,record_id,revision,document) VALUES(?,'TEST',?,1,?)";
        var same="same";var overflow="overflow";
        var payload="{\"state\":\"SUCCEEDED\",\"expiresAtUnixMs\":1,\"padding\":\"\"}";
        payload=payload.replace("\"padding\":\"\"","\"padding\":\""+"x".repeat(4090-payload.length())+"\"");
        sql(f,a,insert,a.value(),same,payload);
        assertThrows(SQLException.class,()->sql(f,a,insert,a.value(),overflow,"{\"a\":1}"));
        assertEquals(4090,f.registry.capacity(a).storedBytes());assertEquals(1,f.registry.capacity(a).storedRows());
        sql(f,b,insert,b.value(),same,"{\"b\":1} ");assertEquals(8,f.registry.capacity(b).storedBytes());
        sql(f,a,"DELETE FROM control_builder WHERE tenant_id=? AND record_id=?",a.value(),same);
        assertEquals(0,f.registry.capacity(a).storedBytes());
        f.registry.capacity(a,33554432,10,1);
        String queue="INSERT INTO control_builder(tenant_id,kind,record_id,revision,document) VALUES(?,'TEST',?,1,?)";
        sql(f,a,queue,a.value(),"one","{\"state\":\"QUEUED\"}");
        assertThrows(SQLException.class,()->sql(f,a,queue,a.value(),"two","{\"state\":\"QUEUED\"}"));
        assertEquals(1,f.registry.capacity(a).queuedRows());
        sql(f,a,"UPDATE control_builder SET revision=revision+1,document=? WHERE tenant_id=? AND record_id=?","{\"state\":\"SUCCEEDED\"}",a.value(),"one");
        sql(f,a,queue,a.value(),"two","{\"state\":\"QUEUED\"}");
        assertEquals(1,f.registry.capacity(a).queuedRows());assertEquals(8,f.registry.capacity(b).storedBytes());
        assertEquals(409,assertThrows(Failure.class,()->f.registry.capacity(a,4096,1,1)).status());
        try(var connection=f.runtime.getConnection()){if(!connection.getMetaData().getDatabaseProductName().equals("SQLite")){
            assertThrows(SQLException.class,()->sql(f,a,"UPDATE control_tenant_capacity SET stored_bytes=0 WHERE tenant_id=?",a.value()));
            assertThrows(SQLException.class,()->sql(f,a,insert,b.value(),overflow,"denied"));
        }}
        var ready=new java.util.concurrent.CountDownLatch(8);var release=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(8)){
            var results=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++)results.add(threads.submit(()->f.store.transaction(a,false,tx->{ready.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Admission release timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}return null;})));
            try{
                assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(429,assertThrows(Failure.class,()->f.store.transaction(a,false,Store.Session::load)).status());
                assertNotNull(f.store.transaction(b,false,Store.Session::load));
            }finally{release.countDown();}
            for(var result:results)result.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertNotNull(f.store.transaction(a,false,Store.Session::load));
    }
}
