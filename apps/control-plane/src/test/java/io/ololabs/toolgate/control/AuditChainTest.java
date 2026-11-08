// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.adapter.PostgresStore;
import io.ololabs.toolgate.control.adapter.SqliteState;
import io.ololabs.toolgate.control.application.AuditChain;
import io.ololabs.toolgate.control.application.AuditChain.Defect;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.control.fixtures.Tenants;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 061 per-tenant audit chains on both SQL dialects: linearity, integrity and tamper detection. */
final class AuditChainTest {
    @TempDir Path temp;
    private final ContractCodec codec = new ContractCodec();
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    @Test void canonicalVectorsMatchTheQuickstartWriter() {
        // Same vectors as tests/quickstart/test_audit_chain.py; the encodings must remain byte-identical.
        assertEquals("0bc0edd363a0ff0de494ae37349282c65e9330a035ad8a9b2690e6c7cfcdd16f",
            AuditChain.digest(TENANT, 1, AuditChain.GENESIS, "a".repeat(64), "CREATE", "user:admin", 1, "request-1", "b".repeat(64), null, 1762560000123L));
        assertEquals("d9024af06ea7b3dd8900b93e2678761353f8bb760b00dae6ddb963f221ef3999",
            AuditChain.digest(TENANT, 2, "c".repeat(64), "a".repeat(64), "CREATE", "user:admin", 2, "request-2", "b".repeat(64), "{\"k\":\"é\"}", 1762560000123L));
        assertEquals("2025-11-08T00:00:00.123Z", AuditChain.sqliteTime(1762560000123L));
        assertEquals(1762560000123L, AuditChain.sqliteMillis("2025-11-08T00:00:00.123Z"));
        assertEquals(AuditChain.MALFORMED_TIME, AuditChain.sqliteMillis("2025-11-08T00:00:00.12Z"));
        assertEquals(AuditChain.MALFORMED_TIME, AuditChain.instantMillis(java.time.Instant.ofEpochSecond(1, 1000)));
        // Absent and empty evidence, and field boundaries, are distinct encodings.
        assertNotEquals(AuditChain.digest(TENANT, 1, AuditChain.GENESIS, "a", "B", "c", 1, "r", "d", null, 1),
            AuditChain.digest(TENANT, 1, AuditChain.GENESIS, "a", "B", "c", 1, "r", "d", "", 1));
        assertNotEquals(AuditChain.digest(TENANT, 1, AuditChain.GENESIS, "ab", "C", "c", 1, "r", "d", null, 1),
            AuditChain.digest(TENANT, 1, AuditChain.GENESIS, "a", "BC", "c", 1, "r", "d", null, 1));
        assertThrows(Failure.class, () -> AuditChain.digest(TENANT, 0, AuditChain.GENESIS, "a", "B", "c", 1, "r", "d", null, 1));
        assertThrows(Failure.class, () -> new AuditChain.Head(0, "a".repeat(64)));
    }

    @Test void pureVerifierReportsEveryDefectClass() {
        var rows = new ArrayList<AuditChain.Entry>(); var head = AuditChain.Head.EMPTY;
        for (int i = 1; i <= 4; i++) { var e = entry(TENANT, i, head.digest(), "target-" + i); rows.add(e); head = e.head(); }
        assertEquals(new AuditChain.Result(Defect.NONE, 4, head), AuditChain.verify(TENANT, AuditChain.Head.EMPTY, rows, head));
        var edited = new ArrayList<>(rows); var r = rows.get(1);
        edited.set(1, new AuditChain.Entry(r.sequence(), r.tenantId(), r.chainIndex(), r.previousDigest(), r.actorId(), r.operation(), "tampered",
            r.revision(), r.requestId(), r.requestDigest(), r.evidence(), r.occurredAtMillis(), r.rowDigest()));
        assertEquals(Defect.EDITED, AuditChain.verify(TENANT, AuditChain.Head.EMPTY, edited, null).defect());
        var missing = new ArrayList<>(rows); missing.remove(1);
        assertEquals(Defect.MISSING_OR_REORDERED, AuditChain.verify(TENANT, AuditChain.Head.EMPTY, missing, null).defect());
        var foreign = new ArrayList<>(rows); var f = rows.get(0);
        foreign.set(0, new AuditChain.Entry(f.sequence(), "22222222-2222-4222-8222-222222222222", 1, f.previousDigest(), f.actorId(), f.operation(), f.target(),
            f.revision(), f.requestId(), f.requestDigest(), f.evidence(), f.occurredAtMillis(), f.rowDigest()));
        assertEquals(Defect.FOREIGN_TENANT, AuditChain.verify(TENANT, AuditChain.Head.EMPTY, foreign, null).defect());
        var truncated = rows.subList(0, 3);
        assertTrue(AuditChain.verify(TENANT, AuditChain.Head.EMPTY, truncated, null).valid(), "a chain alone cannot see tail truncation");
        assertEquals(Defect.TRUNCATED, AuditChain.verify(TENANT, AuditChain.Head.EMPTY, truncated, head).defect());
        assertEquals(Defect.HEAD_MISMATCH, AuditChain.verify(TENANT, AuditChain.Head.EMPTY, rows, new AuditChain.Head(4, "e".repeat(64))).defect());
        // Segments continue from a verified head.
        assertTrue(AuditChain.verify(TENANT, rows.get(1).head(), rows.subList(2, 4), head).valid());
        assertEquals(Defect.MISSING_OR_REORDERED, AuditChain.verify(TENANT, rows.get(0).head(), rows.subList(2, 4), null).defect());
        assertEquals(Defect.BROKEN_LINK, AuditChain.verify(TENANT, new AuditChain.Head(2, "e".repeat(64)), rows.subList(2, 4), null).defect());
    }

    private static AuditChain.Entry entry(String tenant, long index, String previous, String target) {
        long at = 1762560000000L + index;
        String digest = AuditChain.digest(tenant, index, previous, "a".repeat(64), "CREATE", target, index, "request-" + index, "b".repeat(64), null, at);
        return new AuditChain.Entry(index, tenant, index, previous, "a".repeat(64), "CREATE", target, index, "request-" + index, "b".repeat(64), null, at, digest);
    }

    @Test void sqliteChainsAreLinearAndTamperEvident() throws Exception { matrix(false); }
    @Test void postgresChainsAreLinearAndTamperEvident() throws Exception { matrix(true); }

    private record Fixture(DataSource runtime, DataSource second, DataSource owner, boolean postgres) {}

    private Fixture fixture(boolean postgres) throws Exception {
        if (!postgres) {
            var path = temp.resolve(UUID.randomUUID() + ".sqlite");
            // Two independent pools on one file stand in for two Control replicas.
            return new Fixture(Tenants.database(SqliteState.open(path)), SqliteState.open(path), SqliteState.open(path), false);
        }
        var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Required real PostgreSQL test environment missing; run tools/control/check.py");
        var password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        var name = "audit_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
        var url = base.replace("/control?", "/" + name + "?");
        org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).cleanDisabled(true).load().migrate();
        var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password);
        var replica = new org.postgresql.ds.PGSimpleDataSource(); replica.setURL(url); replica.setUser("control_app"); replica.setPassword(password);
        // Fixture-only database owner simulates the A6 attacker who can rewrite stored rows.
        var owner = new org.postgresql.ds.PGSimpleDataSource(); owner.setURL(url); owner.setUser("postgres"); owner.setPassword(password);
        return new Fixture(Tenants.database(app), replica, owner, true);
    }

    private void matrix(boolean postgres) throws Exception {
        var f = fixture(postgres);
        var first = new PostgresStore(f.runtime(), codec); var second = new PostgresStore(f.second(), codec);
        var a = Tenants.id("tenant-a"); var b = Tenants.id("tenant-b");
        // Concurrent appenders on two replica stores and two tenants still produce one linear chain per tenant.
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int w = worker; var store = w % 2 == 0 ? first : second; var tenant = w % 4 < 2 ? a : b;
                futures.add(pool.submit(() -> { for (int i = 0; i < 5; i++) { int n = i;
                    store.transaction(tenant, true, tx -> { tx.audit("a".repeat(64), "CREATE", "worker-" + w + "-" + n, n, "request-" + w + "-" + n, "b".repeat(64),
                        n == 0 ? "{\"worker\":" + w + "}" : null); return null; }); } }));
            }
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        // Short actor identities are stored and hashed exactly as written.
        first.transaction(a, true, tx -> { tx.audit("control", "ATTEMPTS_SETTLED", "short-actor", 1, "short", "c".repeat(64)); return null; });
        var headA = first.transaction(a, false, tx -> tx.auditHead()); var headB = first.transaction(b, false, tx -> tx.auditHead());
        assertEquals(21, headA.chainIndex()); assertEquals(20, headB.chainIndex());
        assertValid(first, a, headA); assertValid(second, b, headB);
        var rowsA = chain(first, a); var rowsB = chain(first, b);
        for (int i = 1; i < rowsA.size(); i++) assertTrue(rowsA.get(i).sequence() > rowsA.get(i - 1).sequence(), "chain order follows commit order");
        assertTrue(Collections.disjoint(rowsA.stream().map(AuditChain.Entry::rowDigest).toList(), rowsB.stream().map(AuditChain.Entry::rowDigest).toList()));
        assertEquals("control", rowsA.get(20).actorId());

        // Runtime writers cannot fork, rewrite or delete the chain.
        try (var c = f.runtime().getConnection()) {
            c.setAutoCommit(false); scope(c, f, a);
            var stale = rowsA.get(19);
            assertThrows(SQLException.class, () -> insert(c, a.value(), stale.chainIndex() + 1, stale.previousDigest()));
            c.rollback(); scope(c, f, a);
            assertThrows(SQLException.class, () -> insert(c, a.value(), headA.chainIndex() + 2, headA.digest()));
            c.rollback(); scope(c, f, a);
            assertThrows(SQLException.class, () -> exec(c, "UPDATE control_audit SET target='x' WHERE tenant_id=?", a.value()));
            c.rollback(); scope(c, f, a);
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM control_audit WHERE tenant_id=?", a.value()));
            c.rollback();
        }
        assertValid(first, a, headA);

        // A6 database-owner rewrites. Each is detected, then reverted to prove the baseline.
        var row3 = rowsA.get(2);
        owner(f, "UPDATE control_audit SET target='tampered' WHERE tenant_id=? AND chain_index=3", a.value());
        assertDefect(first, a, headA, Defect.EDITED, 3);
        owner(f, "UPDATE control_audit SET target=? WHERE tenant_id=? AND chain_index=3", row3.target(), a.value());
        assertValid(first, a, headA);

        // Recomputing the edited row's own digest still breaks the next link.
        var forged = AuditChain.digest(a.value(), 3, row3.previousDigest(), row3.actorId(), row3.operation(), "forged", row3.revision(), row3.requestId(),
            row3.requestDigest(), row3.evidence(), row3.occurredAtMillis());
        owner(f, "UPDATE control_audit SET target='forged',row_digest=? WHERE tenant_id=? AND chain_index=3", forged, a.value());
        assertDefect(first, a, headA, Defect.BROKEN_LINK, 4);
        owner(f, "UPDATE control_audit SET target=?,row_digest=? WHERE tenant_id=? AND chain_index=3", row3.target(), row3.rowDigest(), a.value());

        // Reordering two rows.
        owner(f, "UPDATE control_audit SET chain_index=1000000 WHERE tenant_id=? AND chain_index=5", a.value());
        owner(f, "UPDATE control_audit SET chain_index=5 WHERE tenant_id=? AND chain_index=6", a.value());
        owner(f, "UPDATE control_audit SET chain_index=6 WHERE tenant_id=? AND chain_index=1000000", a.value());
        assertDefect(first, a, headA, Defect.BROKEN_LINK, 5);
        owner(f, "UPDATE control_audit SET chain_index=1000000 WHERE tenant_id=? AND chain_index=6", a.value());
        owner(f, "UPDATE control_audit SET chain_index=6 WHERE tenant_id=? AND chain_index=5", a.value());
        owner(f, "UPDATE control_audit SET chain_index=5 WHERE tenant_id=? AND chain_index=1000000", a.value());
        assertValid(first, a, headA);

        // Cross-tenant splicing: tenant B's first row content and digest placed into tenant A.
        var b1 = rowsB.get(0); var a1 = rowsA.get(0);
        owner(f, "UPDATE control_audit SET actor_id=?,operation=?,target=?,revision=?,request_id=?,request_digest=?,evidence=?,row_digest=? WHERE tenant_id=? AND chain_index=1",
            b1.actorId(), b1.operation(), b1.target(), b1.revision(), b1.requestId(), b1.requestDigest(), b1.evidence(), b1.rowDigest(), a.value());
        owner(f, "UPDATE control_audit SET occurred_at=(SELECT occurred_at FROM control_audit WHERE tenant_id=? AND chain_index=1) WHERE tenant_id=? AND chain_index=1", b.value(), a.value());
        assertDefect(first, a, headA, Defect.EDITED, 1);
        owner(f, "UPDATE control_audit SET actor_id=?,operation=?,target=?,revision=?,request_id=?,request_digest=?,evidence=?,row_digest=? WHERE tenant_id=? AND chain_index=1",
            a1.actorId(), a1.operation(), a1.target(), a1.revision(), a1.requestId(), a1.requestDigest(), a1.evidence(), a1.rowDigest(), a.value());
        owner(f, "UPDATE control_audit SET occurred_at=? WHERE tenant_id=? AND chain_index=1",
            f.postgres() ? java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(a1.occurredAtMillis()), java.time.ZoneOffset.UTC) : AuditChain.sqliteTime(a1.occurredAtMillis()), a.value());
        assertValid(first, a, headA);

        // Deleting a middle row.
        owner(f, "DELETE FROM control_audit WHERE tenant_id=? AND chain_index=7", a.value());
        assertDefect(first, a, headA, Defect.MISSING_OR_REORDERED, 8);

        // Truncating tenant B's tail is invisible to the chain alone, but not to a retained head.
        owner(f, "DELETE FROM control_audit WHERE tenant_id=? AND chain_index>=19", b.value());
        var shortened = chain(first, b);
        assertTrue(AuditChain.verify(b.value(), AuditChain.Head.EMPTY, shortened, null).valid());
        assertDefect(first, b, headB, Defect.TRUNCATED, 19);
        assertEquals(headA.chainIndex(), first.transaction(a, false, tx -> tx.auditHead()).chainIndex(), "tenant chains are independent");
    }

    private static List<AuditChain.Entry> chain(PostgresStore store, TenantId tenant) {
        return store.transaction(tenant, false, tx -> tx.auditChain(0, 10000));
    }
    private static void assertValid(PostgresStore store, TenantId tenant, AuditChain.Head head) {
        var result = AuditChain.verify(tenant.value(), AuditChain.Head.EMPTY, chain(store, tenant), head);
        assertTrue(result.valid(), () -> "unexpected " + result);
        assertEquals(head, result.verifiedHead());
    }
    private static void assertDefect(PostgresStore store, TenantId tenant, AuditChain.Head head, Defect defect, long at) {
        var result = AuditChain.verify(tenant.value(), AuditChain.Head.EMPTY, chain(store, tenant), head);
        assertEquals(defect, result.defect(), () -> "unexpected " + result); assertEquals(at, result.chainIndex());
    }
    private static void scope(Connection c, Fixture f, TenantId tenant) throws SQLException {
        if (f.postgres()) exec(c, "SELECT set_config('app.tenant_id',?,true)", tenant.value());
    }
    private static void insert(Connection c, String tenant, long index, String previous) throws SQLException {
        boolean pg = !c.getMetaData().getDatabaseProductName().equals("SQLite"); long now = System.currentTimeMillis();
        var digest = AuditChain.digest(tenant, index, previous, "a".repeat(64), "CREATE", "fork", 1, "fork", "b".repeat(64), null, now);
        exec(c, "INSERT INTO control_audit(tenant_id,chain_index,previous_digest,actor_id,operation,target,revision,request_id,request_digest,occurred_at,row_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            tenant, index, previous, "a".repeat(64), "CREATE", "fork", 1L, "fork", "b".repeat(64),
            pg ? java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC) : AuditChain.sqliteTime(now), digest);
    }
    private static void exec(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); s.execute(); }
    }
    /** Simulated owner rewrite with the append-only/link triggers bypassed. */
    private static void owner(Fixture f, String sql, Object... values) throws SQLException {
        try (var c = f.owner().getConnection(); var s = c.createStatement()) {
            if (f.postgres()) {
                c.setAutoCommit(false);
                s.execute("ALTER TABLE control_audit DISABLE TRIGGER USER");
                exec(c, sql, values);
                s.execute("ALTER TABLE control_audit ENABLE TRIGGER USER");
                c.commit();
            } else {
                for (var trigger : List.of("control_audit_update", "control_audit_delete", "control_audit_tenant_immutable", "control_audit_chain")) s.execute("DROP TRIGGER IF EXISTS " + trigger);
                exec(c, sql, values);
            }
        }
    }
}
