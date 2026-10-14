// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Focused real PostgreSQL upgrade and runtime-custody boundary for the setup user. */
final class PostgresBootstrapAdministratorTest {
    @Test void upgradeKeepsProfileAndPersistsImmutableAdminProtection() throws Exception {
        var baseUrl=System.getenv("CONTROL_TEST_URL");var password=System.getenv("CONTROL_TEST_PASSWORD");
        assertNotNull(baseUrl,"Isolated PostgreSQL test environment required");assertNotNull(password);
        var database="bootstrap_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(baseUrl,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        var url=baseUrl.replace("/control?","/"+database+"?");
        Flyway.configure().dataSource(url,"control_migrator",password).target("18").load().migrate();
        var codec=new ContractCodec();var user=new ControlUser("admin","Existing owner",true,1L,"existing@example.com",null,null);
        var binding=new ControlIdentityBinding("installed-admin","Reviewed identity",true,1L,"admin","https://quickstart.local","admin",1L,1L,1L,1L,"REVIEWED_INSTALLATION",0L);
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var s=c.prepareStatement("INSERT INTO control_records(tenant_id,kind,record_id,revision,document) VALUES('upgrade',?,?,1,?::jsonb)")) {
            s.setString(1,"USER");s.setString(2,"admin");s.setString(3,codec.json(user));s.executeUpdate();
            s.setString(1,"IDENTITY_BINDING");s.setString(2,"installed-admin");s.setString(3,codec.json(binding));s.executeUpdate();
        }
        var latest=Flyway.configure().dataSource(url,"control_migrator",password).load();assertEquals(3,latest.migrate().migrationsExecuted);latest.validate();
        var source=new PGSimpleDataSource();source.setURL(url);source.setUser("control_app");source.setPassword(password);
        var store=new PostgresStore(source,codec);var tenant=new Ids.TenantId("upgrade");
        store.transaction(tenant,false,tx->{assertEquals("admin",tx.bootstrapAdminUserId());assertEquals(user,codec.model(tx.load().entries().get(Kind.USER.id("admin")).document(),ControlUser.class));return null;});
        for(boolean remove:List.of(false,true))assertEquals(409,assertThrows(Failure.class,()->store.transaction(tenant,true,tx->{var before=tx.load();var entries=new HashMap<>(before.entries());if(remove)entries.remove(Kind.USER.id("admin"));else entries.put(Kind.USER.id("admin"),codec.entry(Kind.USER,codec.json(new ControlUser("admin",user.name(),false,2L,user.email(),null,null))));tx.save(before,new Directory(before.revision()+1,entries));return null;})).status());
        try(var c=source.getConnection();var s=c.createStatement()) {
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE control_bootstrap_admins SET user_id='replacement'"));
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("DELETE FROM control_bootstrap_admins"));
        }
        var fresh=new Ids.TenantId("fresh");store.transaction(fresh,true,tx->{tx.protectBootstrapAdmin("first");tx.protectBootstrapAdmin("first");assertThrows(Failure.class,()->tx.protectBootstrapAdmin("replacement"));return null;});
        assertEquals("first",new PostgresStore(source,codec).transaction(fresh,false,tx->tx.bootstrapAdminUserId()));
    }
}
