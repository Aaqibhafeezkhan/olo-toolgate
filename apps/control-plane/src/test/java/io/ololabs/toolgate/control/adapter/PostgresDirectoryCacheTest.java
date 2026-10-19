// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ControlUser;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresDirectoryCacheTest {
    private static void scope(java.sql.Connection c, String tenant) throws java.sql.SQLException {
        c.setAutoCommit(false);
        try (var s = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) { s.setString(1, tenant); s.execute(); }
    }
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
            for (String tenant : new String[] {"a", "b"}) { scope(c, tenant); s.setString(1, tenant); s.setString(2, codec.json(new ControlUser("same", tenant, true, 1L))); s.executeUpdate(); c.commit(); }
        }
        String cachePort = System.getenv("CONTROL_TEST_CACHE_PORT");
        var shared = cachePort == null ? null : new SharedDirectoryCache(
            new RedisCacheClient("localhost", Integer.parseInt(cachePort), "default",
                System.getenv("CONTROL_TEST_CACHE_PASSWORD").getBytes(java.nio.charset.StandardCharsets.UTF_8), null, 1000),
            codec, database, new byte[32], 60);
        var first = new PostgresStore(source, codec, new DirectoryCache(shared));
        var replica = new PostgresStore(source, codec, new DirectoryCache(shared));
        var old = first.transaction(a, false, tx -> tx.load());
        assertNotEquals(old, first.transaction(b, false, tx -> tx.load()));
        var oldDigest = first.transaction(a, false, tx -> tx.adoptionDigest(a, codec));
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE control_records SET revision=2,document=?::jsonb WHERE tenant_id='a'")) {
            scope(c, "a"); s.setString(1, codec.json(new ControlUser("same", "Revoked", false, 2L))); s.executeUpdate(); c.commit();
        }
        var current = first.transaction(a, false, tx -> tx.load());
        assertEquals(old.revision(), current.revision()); assertFalse(current.entries().get(Ids.Kind.USER.id("same")).enabled());
        assertEquals(current, replica.transaction(a, false, tx -> tx.load()));
        assertTrue(first.transaction(b, false, tx -> tx.load()).entries().get(Ids.Kind.USER.id("same")).enabled());
        assertNotEquals(oldDigest, first.transaction(a, false, tx -> tx.adoptionDigest(a, codec)));
        replica.transaction(a, true, tx -> { tx.saveServerSettings(1, "{}"); return null; });
        assertNotSame(current, first.transaction(a, false, tx -> tx.load()));
        try (var c = source.getConnection(); var s = c.createStatement()) { scope(c, "a"); s.executeUpdate("DELETE FROM control_records WHERE tenant_id='a'"); c.commit(); }
        assertTrue(first.transaction(a, false, tx -> tx.load()).entries().isEmpty());
    }
}
