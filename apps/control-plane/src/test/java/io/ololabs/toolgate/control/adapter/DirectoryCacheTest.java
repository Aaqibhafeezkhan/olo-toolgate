// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ControlUser;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryCacheTest {
    @TempDir Path temp;
    private static final class CountingCodec extends ContractCodec {
        int decoded, snapshots;
        @Override public Directory.Entry entry(Ids.Kind kind, String document) { decoded++; return super.entry(kind, document); }
        @Override public String snapshot(Ids.TenantId tenant, Directory directory, boolean yaml) { snapshots++; return super.snapshot(tenant, directory, yaml); }
    }
    private static final Ids.TenantId A = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-a"), B = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-b");
    private void update(PostgresStore store, ContractCodec codec, Ids.TenantId tenant, String name) {
        store.transaction(tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            var id = Ids.Kind.USER.id("same-id"); var old = entries.get(id);
            entries.put(id, codec.entry(Ids.Kind.USER, codec.json(new ControlUser(id.value(), name, true, old == null ? 1L : old.revision() + 1))));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
    }
    private Directory read(PostgresStore store, Ids.TenantId tenant) { return store.transaction(tenant, false, tx -> tx.load()); }
    private String name(Directory directory) { return directory.entries().get(Ids.Kind.USER.id("same-id")).document(); }

    @Test void firstAccessCachesPerTenantAndExpiresSixtyMinutesAfterPopulation() throws Exception {
        var now = new AtomicLong(); var codec = new CountingCodec();
        try (var source = SqliteState.openPooled(temp.resolve("ttl.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec, new DirectoryCache(now::get));
            update(store, codec, A, "Alpha"); update(store, codec, B, "Beta");
            var first = read(store, A); int decoded = codec.decoded;
            assertSame(first, read(store, A)); assertEquals(decoded, codec.decoded);
            assertTrue(name(read(store, B)).contains("Beta")); assertTrue(name(read(store, A)).contains("Alpha"));
            assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
            now.set(DirectoryCache.TTL_NANOS - 1); assertSame(first, read(store, A));
            now.incrementAndGet(); assertNotSame(first, read(store, A));
            assertEquals(first, read(store, A));
        }
    }

    @Test void snapshotDigestIsReusedButNeverAcrossTenants() throws Exception {
        var codec = new CountingCodec();
        try (var source = SqliteState.openPooled(temp.resolve("digest.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); update(store, codec, A, "Alpha"); update(store, codec, B, "Beta");
            var digest = store.transaction(A, false, tx -> tx.adoptionDigest(A, codec));
            assertEquals(digest, store.transaction(A, false, tx -> tx.adoptionDigest(A, codec)));
            assertEquals(1, codec.snapshots);
            assertNotEquals(digest, store.transaction(B, false, tx -> tx.adoptionDigest(B, codec)));
            assertThrows(io.ololabs.toolgate.control.application.Failure.class, () -> store.transaction(A, false, tx -> tx.adoptionDigest(B, codec)));
            update(store, codec, A, "Changed");
            assertNotEquals(digest, store.transaction(A, false, tx -> tx.adoptionDigest(A, codec)));
        }
    }

    @Test void anotherReplicaAndSameRevisionRecordUpdatesInvalidateImmediately() throws Exception {
        var codec = new CountingCodec(); var path = temp.resolve("replica.sqlite");
        try (var source = SqliteState.openPooled(path);
             var otherSource = SqliteState.openPooled(path)) {
            var first = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); var second = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(otherSource), codec);
            update(first, codec, A, "Alpha"); var original = read(first, A);
            update(second, codec, A, "Replica update"); assertTrue(name(read(first, A)).contains("Replica update"));
            var before = read(first, A);
            // Identity observations use this same UPDATE path without advancing control_tenants.revision.
            try (var c = otherSource.getConnection(); var s = c.prepareStatement("UPDATE control_records SET revision=revision+1,document=? WHERE tenant_id=? AND record_id='same-id'")) {
                s.setString(1, codec.json(new ControlUser("same-id", "Client update", false, 3L))); s.setString(2, A.value()); assertEquals(1, s.executeUpdate());
            }
            var after = read(first, A); assertEquals(before.revision(), after.revision());
            assertFalse(after.entries().get(Ids.Kind.USER.id("same-id")).enabled());
            assertTrue(name(after).contains("Client update")); assertTrue(original.entries().get(Ids.Kind.USER.id("same-id")).enabled());
            try (var c = otherSource.getConnection(); var s = c.createStatement()) { s.executeUpdate("DELETE FROM control_records WHERE tenant_id='62f6460d-dcd3-3902-b833-29a47da9f5ae'"); }
            assertTrue(read(first, A).entries().isEmpty());
        }
    }

    @Test void rollbackNeverPublishesAndReadYourWritesSurvivesMemoization() throws Exception {
        var codec = new CountingCodec();
        try (var source = SqliteState.openPooled(temp.resolve("rollback.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); update(store, codec, A, "Committed"); read(store, A);
            assertThrows(IllegalStateException.class, () -> store.transaction(A, true, tx -> {
                var before = tx.load(); var entries = new HashMap<>(before.entries());
                entries.put(Ids.Kind.USER.id("same-id"), codec.entry(Ids.Kind.USER, codec.json(new ControlUser("same-id", "Rolled back", false, 2L))));
                tx.save(before, new Directory(before.revision() + 1, entries));
                assertFalse(tx.load().entries().get(Ids.Kind.USER.id("same-id")).enabled());
                tx.adoptionDigest(A, codec); throw new IllegalStateException("rollback");
            }));
            assertTrue(name(read(store, A)).contains("Committed"));
            update(store, codec, A, "Next commit"); assertTrue(name(read(store, A)).contains("Next commit"));
        }
    }

    @Test void settingsMutationInvalidatesAndDatabaseFailureCannotUseCachedAuthority() throws Exception {
        var codec = new CountingCodec(); var source = SqliteState.openPooled(temp.resolve("settings.sqlite"));
        try {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); update(store, codec, A, "Alpha"); var before = read(store, A);
            store.transaction(A, true, tx -> { tx.saveServerSettings(1, "{}"); return null; });
            assertNotSame(before, read(store, A));
            source.close();
            assertThrows(io.ololabs.toolgate.control.application.Failure.class, () -> read(store, A));
        } finally { source.close(); }
    }

    @Test void poolResetsReadOnlyStateAndRollsBackFailedWriters() throws Exception {
        var codec = new CountingCodec();
        try (var source = SqliteState.openPooled(temp.resolve("pool.sqlite"))) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); update(store, codec, A, "Initial");
            for (int i = 0; i < 8; i++) {
                read(store, A); update(store, codec, A, "Write " + i);
                assertThrows(IllegalStateException.class, () -> store.transaction(A, true, tx -> { tx.saveServerSettings(1, "{}"); throw new IllegalStateException(); }));
                assertNull(store.transaction(A, false, tx -> tx.serverSettings()));
                try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("PRAGMA query_only")) {
                    assertTrue(c.getAutoCommit()); assertTrue(r.next()); assertEquals(0, r.getInt(1));
                    assertEquals(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE, c.unwrap(org.sqlite.SQLiteConnection.class).getConnectionConfig().getTransactionMode());
                }
            }
        }
    }

    @Test void olderReadSnapshotCannotReplaceNewerCommittedTenantData() throws Exception {
        var codec = new CountingCodec();
        try (var source = SqliteState.openPooled(temp.resolve("concurrent.sqlite"));
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec); update(store, codec, A, "Old");
            var loaded = new java.util.concurrent.CountDownLatch(1); var committed = new java.util.concurrent.CountDownLatch(1);
            var reader = executor.submit(() -> store.transaction(A, false, tx -> {
                var snapshot = tx.load(); loaded.countDown();
                try { if (!committed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("writer timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                assertSame(snapshot, tx.load()); return snapshot;
            }));
            assertTrue(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try { update(store, codec, A, "New"); assertTrue(name(read(store, A)).contains("New")); }
            finally { committed.countDown(); }
            assertTrue(name(reader.get(5, java.util.concurrent.TimeUnit.SECONDS)).contains("Old"));
            assertTrue(name(read(store, A)).contains("New"));
        }
    }

    @Test void tenantCacheEvictsLeastRecentlyUsedEntriesAtItsBound() {
        var cache = new DirectoryCache(); var version = new DirectoryCache.Version(1, 1);
        for (int i = 0; i < 128; i++) cache.put("tenant-" + i, cache.entry(version, new Directory(1, java.util.Map.of())));
        assertNotNull(cache.get("tenant-0", version));
        cache.put("tenant-128", cache.entry(version, new Directory(1, java.util.Map.of())));
        assertNull(cache.get("tenant-1", version)); assertNotNull(cache.get("tenant-0", version));
        assertNull(cache.get("tenant-0", new DirectoryCache.Version(1, 2)));
    }
}
