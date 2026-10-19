// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit allocation and atomic activation, using distinct runtime/platform credentials. */
final class TenantLifecycleTest {
    @TempDir Path temp;
    private record Fixture(javax.sql.DataSource runtime, javax.sql.DataSource platform, PostgresStore store, TenantRegistry registry, ContractCodec codec) {}
    private Fixture fixture(boolean postgres) throws Exception {
        javax.sql.DataSource runtime, platform;
        if (postgres) {
            var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL")); var password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
            var name = "tenants_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = DriverManager.getConnection(base,"control_migrator",password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
            var url = base.replace("/control?","/" + name + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
            var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password); runtime = app;
            var operator = new org.postgresql.ds.PGSimpleDataSource(); operator.setURL(url); operator.setUser("control_provisioner"); operator.setPassword(password); platform = operator;
        } else { runtime = SqliteState.open(temp.resolve(UUID.randomUUID()+".sqlite")); platform = runtime; }
        var codec = new ContractCodec(); return new Fixture(runtime,platform,new PostgresStore(runtime,codec),new TenantRegistry(platform,codec),codec);
    }
    private void initialize(Fixture f, TenantRegistry.Tenant tenant) {
        var origin="https://"+tenant.alias()+".example.test";
        assertTrue(f.registry.initialize(tenant.id(), "a".repeat(64), "[]",List.of(tenant.alias()+".example.test"),new Store.RuntimeProfile(tenant.id().value(),tenant.name(),origin,origin), unit -> {
            TestSupport.seed(unit,f.codec,tenant.id(),"admin");
            unit.transaction(tenant.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});
        }));
    }
    private void unavailable(Fixture f, Ids.TenantId tenant, int status) {
        for (boolean write : List.of(false,true)) {
            var called = new AtomicBoolean();
            assertEquals(status,assertThrows(Failure.class,()->f.store.transaction(tenant,write,tx->{called.set(true);return null;})).status());
            assertFalse(called.get(),"Unavailable tenants cannot execute application callbacks");
        }
    }
    @Test void sqliteLifecycleAndAtomicInitialization() throws Exception { lifecycle(fixture(false)); }
    @Test void postgresLifecycleAndAtomicInitialization() throws Exception { lifecycle(fixture(true)); }
    @Test void concurrentReplicasInitializeOnlyOnce() throws Exception {
        var f=fixture(true);var tenant=f.registry.allocate("replicas","Replicas");var start=new CountDownLatch(1);
        var applied=new java.util.concurrent.atomic.AtomicInteger();
        try(var threads=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> run=()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));
                return f.registry.initialize(tenant.id(),"c".repeat(64),"[]",unit->{
                    applied.incrementAndGet();TestSupport.seed(unit,f.codec,tenant.id(),"admin");
                    unit.transaction(tenant.id(),true,tx->{tx.protectBootstrapAdmin("admin");return null;});
                });
            };
            var first=threads.submit(run);var second=threads.submit(run);start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,applied.get());assertNull(f.registry.initializationFailure(tenant.id()));
        }
    }
    private void lifecycle(Fixture f) throws Exception {
        var unknown = Ids.TenantId.create(); unavailable(f,unknown,404);
        assertTrue(f.registry.activeAfter("",256).isEmpty());
        var tenant = f.registry.allocate("default","Default organization");
        assertEquals(UUID.fromString(tenant.id().value()).toString(),tenant.id().value());
        assertEquals(tenant.id(),f.registry.allocate("default","Different display input").id());
        unavailable(f,tenant.id(),404);
        assertThrows(IllegalStateException.class,()->f.registry.initialize(tenant.id(),"a".repeat(64),"[]",unit->{
            TestSupport.seed(unit,f.codec,tenant.id(),"admin"); throw new IllegalStateException("abort initialization");
        }));
        assertEquals("PROVISIONING",f.registry.find("default").status()); unavailable(f,tenant.id(),404);
        var diagnostic=f.registry.initializationFailure(tenant.id());
        assertEquals("INITIALIZATION_FAILED",diagnostic.code());assertTrue(diagnostic.failedAt()>0);
        assertEquals(diagnostic,new TenantRegistry(f.platform,f.codec).initializationFailure(tenant.id()),"Diagnostics survive a new registry instance");
        initialize(f,tenant);
        assertNull(f.registry.initializationFailure(tenant.id()),"Successful activation clears the previous failed attempt");
        assertEquals(tenant.id(),f.registry.activeHost("DEFAULT.example.test"));
        assertEquals("https://default.example.test",f.store.transaction(tenant.id(),false,tx->tx.runtimeProfile().controlUrl()));
        assertEquals(List.of(tenant.id()),f.registry.activeAfter("",256));
        long revision = f.store.transaction(tenant.id(),false,tx->tx.load().revision()); assertEquals(1,revision,"Rolled-back seed must not survive");
        assertFalse(f.registry.initialize(tenant.id(),"b".repeat(64),"changed-source",unit->{throw new AssertionError("Never replay bootstrap");}));
        assertEquals(revision,f.store.<Long>transaction(tenant.id(),false,tx->tx.load().revision()).longValue());
        f.registry.suspended(tenant.id(),true); unavailable(f,tenant.id(),404);
        assertNull(f.registry.activeHost("default.example.test"));assertNull(f.registry.activeHost("unknown.example.test"));
        assertTrue(f.registry.activeAfter("",256).isEmpty());
        f.registry.suspended(tenant.id(),false);
        assertEquals(revision,f.store.<Long>transaction(tenant.id(),false,tx->tx.load().revision()).longValue());
        var before = f.store.transaction(tenant.id(),false,Store.Session::load);
        f.store.transaction(tenant.id(),true,tx->{tx.save(before,new Directory(before.revision()+1,before.entries()));return null;});
        assertEquals(409,assertThrows(Failure.class,()->f.store.transaction(tenant.id(),true,tx->{tx.save(before,new Directory(before.revision()+1,before.entries()));return null;})).status());
        try (var probe=f.platform.getConnection();var c=probe.getMetaData().getDatabaseProductName().equals("PostgreSQL")
                ? DriverManager.getConnection(probe.getMetaData().getURL(),"control_migrator",System.getenv("CONTROL_TEST_PASSWORD")) : f.platform.getConnection();var s=c.prepareStatement("DELETE FROM control_tenant_authority WHERE tenant_id=?")) {
            c.setAutoCommit(false);
            if(c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))try(var context=c.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){context.setString(1,tenant.id().value());context.execute();}
            s.setString(1,tenant.id().value());assertEquals(1,s.executeUpdate());c.commit();
        }
        unavailable(f,tenant.id(),503);
    }
    @Test void runtimeCannotRegisterEnumerateOrInsertAuthority() throws Exception {
        var f=fixture(true);var tenant=f.registry.allocate("default","Default");initialize(f,tenant);
        try(var c=f.runtime.getConnection();var s=c.createStatement()){
            for(String sql:List.of("SELECT * FROM control_tenants","INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES('anything','other','Other','PROVISIONING',1,1)","UPDATE control_tenants SET status='ACTIVE'","UPDATE control_tenant_runtime_profiles SET server_id='another-server'","INSERT INTO control_tenant_authority(tenant_id) VALUES('"+Ids.TenantId.create().value()+"')"))
                assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState());
        }
        try(var c=f.platform.getConnection();var s=c.createStatement()){
            assertEquals("23514",assertThrows(SQLException.class,()->s.execute("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES('quickstart','bad','Bad','PROVISIONING',1,1)")).getSQLState());
        }
        assertThrows(IllegalArgumentException.class,()->new Ids.TenantId("quickstart"));
        assertThrows(IllegalArgumentException.class,()->new Ids.TenantId(tenant.id().value().toUpperCase(Locale.ROOT)));
    }
    @Test void suspensionWaitsForAnAdmittedWriterAndThenRejectsNewWork() throws Exception {
        var f=fixture(true);var tenant=f.registry.allocate("default","Default");initialize(f,tenant);
        var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);var suspending=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)){
            var writer=threads.submit(()->f.store.transaction(tenant.id(),true,tx->{admitted.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Writer barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertTrue(admitted.await(5,TimeUnit.SECONDS));
            var suspend=threads.submit(()->{try(var c=f.platform.getConnection();var s=c.prepareStatement("UPDATE control_tenants SET status='SUSPENDED',updated_at=? WHERE tenant_id=?")){s.setLong(1,System.currentTimeMillis());s.setString(2,tenant.id().value());suspending.countDown();return s.executeUpdate();}});
            try{assertTrue(suspending.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->suspend.get(200,TimeUnit.MILLISECONDS));}
            finally{release.countDown();}
            writer.get(5,TimeUnit.SECONDS);assertEquals(1,suspend.get(5,TimeUnit.SECONDS));unavailable(f,tenant.id(),404);
        }
    }
}
