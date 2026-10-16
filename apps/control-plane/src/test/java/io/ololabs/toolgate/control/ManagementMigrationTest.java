// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ManagementMigrationTest {
    @Test void postgresUpgradesStoredRulesOnceWithoutBroadeningPrivateDeviceReads() throws Exception {
        var base=Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"));var password=Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        var database="management_migration_"+UUID.randomUUID().toString().replace("-","");
        try(var c=java.sql.DriverManager.getConnection(base,"control_migrator",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        var url=base.replace("/control?","/"+database+"?");
        org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).target("23").load().migrate();
        var codec=new ContractCodec();var conditions=codec.json(new EnterpriseConditions(0L,0L,List.of(),List.of(),List.of(),List.of(),false,false,null,null));
        var document="""
            {"id":"visitor","name":"Visitor","enabled":true,"revision":2,"portalRole":"ADMINISTRATOR","roleType":"MANAGEMENT","managementRules":[
             {"actions":["read"],"groupType":"DEVICE_GROUP","groups":{"ids":[],"all":true},"grantableScopes":[],"conditions":CONDITIONS},
             {"actions":["approve-device"],"groupType":"DEVICE_GROUP","groups":{"ids":["private"],"all":false},"grantableScopes":[],"conditions":CONDITIONS,"ownDevices":true}]}
            """.replace("CONDITIONS",conditions);
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement()){
            s.execute("INSERT INTO control_tenants(tenant_id,revision) VALUES('legacy',7)");
            try(var insert=c.prepareStatement("INSERT INTO control_records(tenant_id,kind,record_id,revision,document) VALUES('legacy','ROLE','visitor',2,?::jsonb)")){insert.setString(1,document);insert.executeUpdate();}
        }
        var migration=org.flywaydb.core.Flyway.configure().dataSource(url,"control_migrator",password).load();assertEquals(2,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);
        try(var c=java.sql.DriverManager.getConnection(url,"control_migrator",password);var s=c.createStatement();var r=s.executeQuery("SELECT document,revision FROM control_records WHERE record_id='visitor'")){
            assertTrue(r.next());var role=codec.model(r.getString(1),ControlRole.class);assertEquals(3,r.getInt(2));assertEquals(3L,role.revision());
            assertFalse(role.managementRules().getFirst().actions().contains("device.read"));assertTrue(role.managementRules().getFirst().actions().contains("user.read"));
            var own=role.managementRules().get(1);assertEquals("OWNED",own.deviceScope());assertEquals(List.of("private"),own.groups().ids());assertEquals(List.of("device.approve","device.enroll","device.read"),own.actions());
        }
    }
}
