// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ControlUser;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresDirectoryCacheTest {
    @Test void databaseTriggersInvalidateReplicasAndKeepTenantsSeparate() throws Exception {
        String base = System.getenv("CONTROL_TEST_URL"), password = System.getenv("CONTROL_TEST_PASSWORD");
        org.junit.jupiter.api.Assumptions.assumeTrue(base != null && password != null, "Requires isolated PostgreSQL");
        String database = "cache_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = java.sql.DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + database); }
        String url = base.replace("/control?", "/" + database + "?");
        org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
        var source = new org.postgresql.ds.PGSimpleDataSource(); source.setURL(url); source.setUser("control_app"); source.setPassword(password);
        var codec = new ContractCodec(); var a = new Ids.TenantId("a"); var b = new Ids.TenantId("b");
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO control_records(tenant_id,kind,record_id,revision,document) VALUES(?,'USER','same',1,?::jsonb)")) {
            for (String tenant : new String[] {"a", "b"}) { s.setString(1, tenant); s.setString(2, codec.json(new ControlUser("same", tenant, true, 1L))); s.executeUpdate(); }
        }
        var first = new PostgresStore(source, codec); var replica = new PostgresStore(source, codec);
        var old = first.transaction(a, false, tx -> tx.load());
        assertNotEquals(old, first.transaction(b, false, tx -> tx.load()));
        var oldDigest = first.transaction(a, false, tx -> tx.adoptionDigest(a, codec));
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE control_records SET revision=2,document=?::jsonb WHERE tenant_id='a'")) {
            s.setString(1, codec.json(new ControlUser("same", "Revoked", false, 2L))); s.executeUpdate();
        }
        var current = first.transaction(a, false, tx -> tx.load());
        assertEquals(old.revision(), current.revision()); assertFalse(current.entries().get(Ids.Kind.USER.id("same")).enabled());
        assertEquals(current, replica.transaction(a, false, tx -> tx.load()));
        assertTrue(first.transaction(b, false, tx -> tx.load()).entries().get(Ids.Kind.USER.id("same")).enabled());
        assertNotEquals(oldDigest, first.transaction(a, false, tx -> tx.adoptionDigest(a, codec)));
        replica.transaction(a, true, tx -> { tx.saveServerSettings(1, "{}"); return null; });
        assertNotSame(current, first.transaction(a, false, tx -> tx.load()));
        try (var c = source.getConnection(); var s = c.createStatement()) { s.executeUpdate("DELETE FROM control_records WHERE tenant_id='a'"); }
        assertTrue(first.transaction(a, false, tx -> tx.load()).entries().isEmpty());
    }
}
