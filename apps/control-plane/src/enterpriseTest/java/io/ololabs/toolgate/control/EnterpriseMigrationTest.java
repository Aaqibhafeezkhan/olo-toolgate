// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Production migration proof; the Control check harness supplies an isolated PostgreSQL instance. */
@EnabledIfEnvironmentVariable(named="CONTROL_TEST_URL",matches=".+")
class EnterpriseMigrationTest {
    @Test void upgradesArchiveLegacyAuthorityAndRuntimeCannotModifyIt() throws Exception {
        String base=System.getenv("CONTROL_TEST_URL"),password=System.getenv("CONTROL_TEST_PASSWORD");
        assertNotNull(password);String name="enterprise_"+java.util.UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+name);}
        String url=base.replace("/control?","/"+name+"?");
        Flyway.configure().dataSource(url,"control_migrator",password).target("12").load().migrate();
        String old="{\"id\":\"kept\",\"name\":\"Kept\",\"enabled\":true,\"revision\":1,\"access\":{\"role\":\"SUPER_ADMIN\",\"roleIds\":[]}}";
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO control_tenants(tenant_id,revision) VALUES('upgrade',1)");
            try(var insert=c.prepareStatement("INSERT INTO control_records(tenant_id,kind,record_id,revision,document) VALUES('upgrade','USER','kept',1,?::jsonb)")){insert.setString(1,old);insert.executeUpdate();}
        }
        var latest=Flyway.configure().dataSource(url,"control_migrator",password).load();int pending=latest.info().pending().length;assertTrue(pending>0);assertEquals(pending,latest.migrate().migrationsExecuted);latest.validate();
        var source=new PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        var codec=new ContractCodec();var store=new PostgresStore(source,codec);
        store.transaction(new Ids.TenantId("upgrade"),false,tx->{var d=tx.load();codec.validatePolicies(d);assertTrue(d.entries().get(Kind.USER.id("kept")).enabled());assertFalse(d.entries().get(Kind.USER.id("kept")).document().contains("access"));assertEquals(java.util.List.of("team-default"),GroupGraph.memberships(d,codec,Kind.USER,"kept",false));return null;});
        try(var c=source.getConnection();var s=c.createStatement()) {
            try(var row=s.executeQuery("SELECT document FROM control_access_migration_archive WHERE tenant_id='upgrade' AND record_id='kept'")){assertTrue(row.next());assertTrue(row.getString(1).contains("SUPER_ADMIN"));}
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_access_migration_archive SET document='{}'"));
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("DELETE FROM control_audit"));
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_tenants SET authorization_epoch=0 WHERE tenant_id='upgrade'"));
        }
        // Current writes atomically advance authority and emit an invalidation event.
        var intake=new IdentityIntake(store,codec);long now=System.currentTimeMillis();intake.observe(new Ids.TenantId("upgrade"),"https://identity.example","new",null,null,now-1000,now+60000,now);
        try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT t.revision,t.authorization_epoch,o.authorization_epoch FROM control_tenants t JOIN control_authorization_outbox o USING(tenant_id,revision) WHERE t.tenant_id='upgrade'")){assertTrue(rows.next());assertEquals(rows.getLong(1),rows.getLong(2));assertEquals(rows.getLong(2),rows.getLong(3));}
    }
}
