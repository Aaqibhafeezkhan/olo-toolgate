// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** First-release initialization and current runtime privilege boundaries. */
@EnabledIfEnvironmentVariable(named="CONTROL_TEST_URL",matches=".+")
class InitialSchemaTest {
    @Test void initializesOnlyV1AndCurrentWritesAdvanceAuthorityAtomically() throws Exception {
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        assertNotNull(password);
        String name="initial_"+java.util.UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){
            s.execute("CREATE DATABASE "+name);
        }
        String url=base.replace("/control?","/"+name+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load().migrate();
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement();
            var rows=s.executeQuery("SELECT version FROM flyway_schema_history")){
            assertTrue(rows.next());assertEquals("1",rows.getString(1));assertFalse(rows.next());
        }
        var source=new PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        var codec=new ContractCodec();var store=new PostgresStore(source,codec);
        var tenant=new Ids.TenantId("initial");
        store.transaction(tenant,true,tx->{
            var before=tx.load();var entries=new java.util.HashMap<>(before.entries());
            GroupGraph.defaults(entries,codec);
            tx.save(before,new io.ololabs.toolgate.control.domain.Directory(before.revision()+1,entries));return null;
        });
        var intake=new IdentityIntake(store,codec);long now=System.currentTimeMillis();
        intake.observe(tenant,"https://identity.example","new",null,null,now-1000,now+60000,now);
        try(var c=source.getConnection();var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT t.revision,t.authorization_epoch,o.authorization_epoch FROM control_tenants t JOIN control_authorization_outbox o USING(tenant_id,revision) WHERE t.tenant_id='initial'")){
                assertTrue(rows.next());assertEquals(rows.getLong(1),rows.getLong(2));assertEquals(rows.getLong(2),rows.getLong(3));
            }
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("DELETE FROM control_audit"));
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_tenants SET authorization_epoch=0 WHERE tenant_id='initial'"));
            for(var table:java.util.List.of("control_access_migration_archive","control_approvals","control_permit_leases")){
                try(var rows=s.executeQuery("SELECT to_regclass('"+table+"')")){assertTrue(rows.next());assertNull(rows.getString(1));}
            }
        }
    }
}
