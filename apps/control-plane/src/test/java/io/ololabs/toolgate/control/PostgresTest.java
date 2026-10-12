// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.adapter.PostgresStore;
import io.ololabs.toolgate.control.application.DirectoryService;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL migration, atomicity, privilege and concurrent replica-boundary tests. */
final class PostgresTest {
    private String env(String name) {
        var value = System.getenv(name);
        assertNotNull(value, "Required real PostgreSQL test environment missing; run make check or tools/control/check.py"); return value;
    }
    @Test void cleanInstallUpgradeAndAtomicMutations() throws Exception {
        var baseUrl = env("CONTROL_TEST_URL"); var password = env("CONTROL_TEST_PASSWORD");
        var database = "test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(baseUrl, "control_migrator", password); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        var url = baseUrl.replace("/control?", "/" + database + "?");
        var migration = Flyway.configure().dataSource(url, "control_migrator", password).target("1").cleanDisabled(true).load();
        migration.migrate();
        var codec = new ContractCodec();
        try (var connection = java.sql.DriverManager.getConnection(url, "control_migrator", password); var statement = connection.prepareStatement(
                "INSERT INTO control_records (tenant_id,kind,record_id,revision,document) VALUES ('upgrade','USER','kept',1,?::jsonb)")) {
            statement.setString(1, DomainTest.user("kept", 1)); statement.executeUpdate();
        }
        var latest = Flyway.configure().dataSource(url, "control_migrator", password).cleanDisabled(true).load();
        assertEquals(19, latest.migrate().migrationsExecuted); latest.validate();
        var source = new PGSimpleDataSource(); source.setURL(url); source.setUser("control_app"); source.setPassword(password);
        var store = new PostgresStore(source, codec); var service = new DirectoryService(store, codec, 512, 1048576);
        var actor = TestSupport.seed(store,codec,new Ids.TenantId("tenant"),"root");
        var upgrade=TestSupport.seed(store,codec,new Ids.TenantId("upgrade"),"root");
        assertEquals(200, service.get(upgrade, Kind.USER, "kept").status());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("DELETE FROM control_audit"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("CREATE TABLE forbidden (id int)"));
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<io.ololabs.toolgate.control.application.Store.Reply>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> service.mutate(actor, Kind.USER, "user", "CREATE", DomainTest.user("user", 1), 0, "same-key", "request")));
            for (var future : futures) assertEquals(201, future.get().status());
        } finally { pool.shutdownNow(); }
        assertEquals(3, auditCount(source, "tenant"));
        assertThrows(Failure.class, () -> service.mutate(actor, Kind.USER, "different", "CREATE", DomainTest.user("different", 1), 0, "same-key", "request"));
        var update = service.mutate(actor, Kind.USER, "user", "UPDATE", DomainTest.user("user", 1).replace("User", "Changed"), 1, "update-key", "request");
        assertEquals(2, update.revision());
        assertThrows(Failure.class, () -> service.mutate(actor, Kind.USER, "user", "UPDATE", DomainTest.user("user", 1), 1, "stale-key", "request"));
        assertThrows(Failure.class, () -> service.get(new DirectoryService.Actor(new Ids.TenantId("other"), "a".repeat(64), true), Kind.USER, "user"));
        var team = "{\"id\":\"team\",\"name\":\"Team\",\"enabled\":true,\"revision\":1,\"userIds\":[\"user\"],\"roleIds\":[]}";
        service.mutate(actor, Kind.TEAM, "team", "CREATE", team, 0, "team-key", "request");
        // Individual deletion removes group memberships atomically; keep this user for the import/replay checks.
        var snapshot = service.export(actor, false);
        var input = "{\"snapshot\":" + snapshot.body() + ",\"mode\":\"REPLACE\",\"dryRun\":true}";
        assertTrue(service.importConfig(actor, input, false, snapshot.revision(), "dry-key", "request").body().contains("\"applied\":false"));
        assertEquals(7, auditCount(source, "tenant"));
        service.importConfig(actor, input.replace("\"dryRun\":true", "\"dryRun\":false"), false, snapshot.revision(), "import-key", "request");
        assertEquals(8, auditCount(source, "tenant"));
        // Inject an audit failure in a dedicated tenant and prove data + idempotency roll back.
        try (var connection = java.sql.DriverManager.getConnection(url, "control_migrator", password); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE control_audit ADD CONSTRAINT reject_rollback CHECK (tenant_id <> 'rollback')");
        }
        var rollback = TestSupport.seed(store,codec,new Ids.TenantId("rollback"),"root");
        assertThrows(Failure.class, () -> service.mutate(rollback, Kind.USER, "user", "CREATE", DomainTest.user("user", 1), 0, "rollback-key", "request"));
        assertThrows(Failure.class, () -> service.get(rollback, Kind.USER, "user"));
        try (var connection = java.sql.DriverManager.getConnection(url, "control_migrator", password); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE control_audit DROP CONSTRAINT reject_rollback");
        }
        assertEquals(201, service.mutate(rollback, Kind.USER, "user", "CREATE", DomainTest.user("user", 1), 0, "rollback-key", "request").status());
        service.mutate(rollback, Kind.USER, "user", "DELETE", null, 1, "delete-retired", "request");
        assertThrows(Failure.class, () -> service.mutate(rollback, Kind.USER, "user", "CREATE", DomainTest.user("user", 1), 0, "reuse-retired", "request"));
    }
    private long auditCount(PGSimpleDataSource source, String tenant) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT count(*) FROM control_audit WHERE tenant_id=?")) {
            statement.setString(1, tenant); try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
}
