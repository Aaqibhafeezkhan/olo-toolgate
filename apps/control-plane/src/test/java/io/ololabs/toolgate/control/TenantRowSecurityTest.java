// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.adapter.PostgresStore;
import io.ololabs.toolgate.control.domain.Ids;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real runtime-role proof: deliberately omit SQL filters and reuse one pooled connection. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class TenantRowSecurityTest {
    private static final Ids.TenantId A = new Ids.TenantId("rls-a"), B = new Ids.TenantId("rls-b");
    private AgroalDataSource pool;
    private PostgresStore store;
    private String url, password;

    @BeforeAll void initialize() throws Exception {
        String base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Real PostgreSQL required");
        password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        String name = "rls_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        url = base.replace("/control?", "/" + name + "?");
        Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
        pool = AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier().connectionPoolConfiguration(p -> p
            .minSize(1).initialSize(1).maxSize(1).acquisitionTimeout(java.time.Duration.ofSeconds(3))
            .connectionFactoryConfiguration(f -> f.connectionProviderClass(org.postgresql.Driver.class).jdbcUrl(url)
                .principal(new io.agroal.api.security.NamePrincipal("control_app"))
                .credential(new io.agroal.api.security.SimplePassword(password)))));
        var codec = new ContractCodec(); store = new PostgresStore(pool, codec);
        TestSupport.seed(store, codec, A, "a-user"); TestSupport.seed(store, codec, B, "b-user");
        for (var tenant : List.of(A, B)) try (var c = scoped(tenant); var s = c.prepareStatement(
                "INSERT INTO control_secrets(tenant_id,name,tool_group_id,device_group_id,cipher) VALUES (?,'same-name','tools','devices',?)")) {
            s.setString(1, tenant.value()); s.setString(2, tenant.value() + "-cipher"); s.executeUpdate(); c.commit();
        }
    }
    @AfterAll void close() { if (pool != null) pool.close(); }
    private Connection scoped(Ids.TenantId tenant) throws SQLException {
        var c = pool.getConnection(); c.setAutoCommit(false);
        try (var s = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            s.setString(1, tenant.value()); s.execute();
        }
        return c;
    }
    private long count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); }
    }
    private void noContextOnBorrowedConnection() throws SQLException {
        try (var c = pool.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
            assertTrue(r.next()); assertTrue(r.getString(1) == null || r.getString(1).isEmpty());
        }
    }
    @Test void unfilteredReadsAndSecretNamesStayWithinTheSelectedTenant() throws Exception {
        for (var tenant : List.of(A, B)) try (var c = scoped(tenant); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT DISTINCT tenant_id FROM control_records")) {
                assertTrue(r.next()); assertEquals(tenant.value(), r.getString(1)); assertFalse(r.next());
            }
            try (var r = s.executeQuery("SELECT cipher FROM control_secrets")) {
                assertTrue(r.next()); assertEquals(tenant.value() + "-cipher", r.getString(1)); assertFalse(r.next());
            }
            c.rollback();
        }
        try (var c = pool.getConnection()) { assertEquals(0, count(c, "control_records")); assertEquals(0, count(c, "control_secrets")); }
        try (var c = scoped(new Ids.TenantId("unregistered"))) { assertEquals(0, count(c, "control_records")); c.rollback(); }
        try (var c = scoped(new Ids.TenantId("unregistered")); var s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '', true)"); assertEquals(0, count(c, "control_secrets")); c.rollback();
        }
    }
    @Test void crossTenantInsertUpdateAndOwnershipChangeAreDenied() throws Exception {
        try (var c = scoped(A); var s = c.createStatement()) {
            assertEquals(0, s.executeUpdate("UPDATE control_secrets SET cipher='changed' WHERE tenant_id='rls-b'"));
            assertEquals(0, s.executeUpdate("DELETE FROM control_records WHERE tenant_id='rls-b'")); c.rollback();
        }
        for (String sql : List.of(
                "INSERT INTO control_secrets VALUES('rls-b','other','tools','devices','cipher')",
                "UPDATE control_secrets SET tenant_id='rls-b' WHERE name='same-name'")) {
            try (var c = scoped(A); var s = c.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> s.executeUpdate(sql)).getSQLState()); c.rollback();
            }
        }
        try (var c = pool.getConnection(); var s = c.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> s.executeUpdate(
                "INSERT INTO control_secrets VALUES('rls-a','no-context','tools','devices','cipher')")).getSQLState());
        }
    }
    @Test void storeContextClearsOnCommitRollbackAndReadOnlyPoolReuse() throws Exception {
        for (boolean write : List.of(false, true)) for (var tenant : List.of(A, B)) {
            store.transaction(tenant, write, tx -> {
                assertTrue(tx.load().entries().containsKey(Ids.Kind.USER.id(tenant == A ? "a-user" : "b-user"))); return null;
            });
            noContextOnBorrowedConnection();
        }
        assertThrows(IllegalStateException.class, () -> store.transaction(A, true, tx -> {
            tx.enterprise().saveSecret(new io.ololabs.toolgate.control.application.EnterpriseStore.Secret("rolled-back", "tools", "devices", "cipher"));
            throw new IllegalStateException("force rollback");
        }));
        noContextOnBorrowedConnection();
        try (var c = scoped(A)) { assertEquals(1, count(c, "control_secrets")); c.rollback(); }
    }
    @Test void forceRlsAlsoScopesTheOwnerAndAdditionalPermissivePoliciesCannotWidenTheFence() throws Exception {
        try (var c = DriverManager.getConnection(url, "control_migrator", password); var s = c.createStatement()) {
            assertEquals(0, count(c, "control_records"));
            s.execute("CREATE POLICY extra_permissive ON control_secrets USING (true) WITH CHECK (true)");
            try {
                assertEquals(0, count(c, "control_secrets"));
                c.setAutoCommit(false); s.execute("SELECT set_config('app.tenant_id', 'rls-a', true)");
                assertEquals(1, count(c, "control_secrets")); c.rollback(); c.setAutoCommit(true);
            } finally { s.execute("DROP POLICY extra_permissive ON control_secrets"); }
        }
    }
    @Test void deployedCatalogAndRuntimePrivilegesMatchEveryManifestEntry() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode manifest;
        try (var input = getClass().getResourceAsStream("/db/schema-ownership.json")) { manifest = mapper.readTree(input); }
        try (var c = pool.getConnection(); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(r.next()); assertFalse(r.getBoolean(1)); assertFalse(r.getBoolean(2));
            }
            Set<String> expected = new TreeSet<>(List.of("flyway_schema_history")), actual = new TreeSet<>();
            for (var table : manifest.get("tables")) {
                String name = table.get("name").asText(); expected.add(name);
                try (var q = c.prepareStatement("SELECT relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner)=current_user FROM pg_class WHERE oid=?::regclass")) {
                    q.setString(1, name); try (var r = q.executeQuery()) {
                        assertTrue(r.next(), name); boolean forced = table.get("rowSecurity").asText().equals("forced");
                        assertEquals(forced, r.getBoolean(1), name); assertEquals(forced, r.getBoolean(2), name); assertFalse(r.getBoolean(3), name);
                    }
                }
                try (var q = c.prepareStatement("SELECT is_nullable,data_type,character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name='tenant_id'")) {
                    q.setString(1, name); try (var r = q.executeQuery()) {
                        assertTrue(r.next(), name); assertEquals("NO", r.getString(1), name);
                        assertEquals(table.get("postgresTenantType").asText(), r.getString(2).equals("text") ? "text" : "varchar(" + r.getInt(3) + ")", name);
                    }
                }
                var primaryKey = new TreeMap<Short, String>();
                try (var r = c.getMetaData().getPrimaryKeys(null, "public", name)) {
                    while (r.next()) primaryKey.put(r.getShort("KEY_SEQ"), r.getString("COLUMN_NAME"));
                }
                assertEquals(table.get("primaryKey"), mapper.valueToTree(primaryKey.values()), name);
                var references = new LinkedHashMap<String, com.fasterxml.jackson.databind.node.ObjectNode>();
                try (var r = c.getMetaData().getImportedKeys(null, "public", name)) {
                    while (r.next()) {
                        assertEquals("public", r.getString("PKTABLE_SCHEM"), name);
                        var reference = references.get(r.getString("FK_NAME"));
                        if (reference == null) {
                            reference = mapper.createObjectNode(); reference.put("table", r.getString("PKTABLE_NAME"));
                            reference.putArray("columns"); reference.putArray("referencedColumns"); references.put(r.getString("FK_NAME"), reference);
                        }
                        ((com.fasterxml.jackson.databind.node.ArrayNode) reference.get("columns")).add(r.getString("FKCOLUMN_NAME"));
                        ((com.fasterxml.jackson.databind.node.ArrayNode) reference.get("referencedColumns")).add(r.getString("PKCOLUMN_NAME"));
                    }
                }
                var expectedReferences = new HashSet<JsonNode>(); table.get("foreignKeys").forEach(expectedReferences::add);
                assertEquals(expectedReferences, new HashSet<>(references.values()), name);
                var privileges = new HashSet<String>(); table.get("runtimePrivileges").forEach(p -> privileges.add(p.asText()));
                for (var operation : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    try (var q = c.prepareStatement("SELECT has_table_privilege(current_user, ?, ?)")) {
                        q.setString(1, name); q.setString(2, operation); try (var r = q.executeQuery()) {
                            assertTrue(r.next()); assertEquals(privileges.contains(operation), r.getBoolean(1), name + " " + operation);
                        }
                    }
                }
                if (table.get("rowSecurity").asText().equals("forced")) {
                    try (var q = c.prepareStatement("SELECT polpermissive,polcmd,pg_get_expr(polqual,polrelid),pg_get_expr(polwithcheck,polrelid) FROM pg_policy WHERE polrelid=?::regclass AND polname='tenant_fence'")) {
                        q.setString(1, name); try (var r = q.executeQuery()) {
                            assertTrue(r.next(), name); assertFalse(r.getBoolean(1)); assertEquals("*", r.getString(2));
                            for (int column : List.of(3, 4)) { assertTrue(r.getString(column).contains("tenant_id")); assertTrue(r.getString(column).contains("app.tenant_id")); }
                            assertFalse(r.next());
                        }
                    }
                    assertEquals(0, count(c, name), name + " without context");
                }
            }
            try (var r = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public'")) { while (r.next()) actual.add(r.getString(1)); }
            assertEquals(expected, actual);
            assertThrows(SQLException.class, () -> s.execute("TRUNCATE control_records"));
            assertThrows(SQLException.class, () -> s.execute("ALTER TABLE control_records DISABLE ROW LEVEL SECURITY"));
            assertThrows(SQLException.class, () -> s.execute("DROP POLICY tenant_fence ON control_records"));
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM flyway_schema_history"));
        }
    }
}
