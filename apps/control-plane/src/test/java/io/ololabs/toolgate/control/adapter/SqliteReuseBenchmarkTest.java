// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Small repeatable comparison, not a capacity claim. Both modes retain WAL and synchronous=FULL. */
class SqliteReuseBenchmarkTest {
    @TempDir Path temp;
    @Test void compareDurableTransactionsWithAndWithoutConnectionReuse() throws Exception {
        var codec = new ContractCodec(); var tenant = new TenantId("benchmark");
        for (boolean pooled : new boolean[] {false, true}) {
            var source = SqliteState.open(temp.resolve(pooled ? "pooled.sqlite" : "unpooled.sqlite"), pooled);
            try {
                var store = new PostgresStore(source, codec);
                for (int i = 0; i < 10; i++) store.transaction(tenant, true, tx -> tx.approvalClock(1));
                long start = System.nanoTime();
                for (int i = 0; i < 200; i++) { final long value = i + 2; long observed = store.transaction(tenant, true, tx -> tx.approvalClock(value)); assertEquals(value, observed); }
                double millis = (System.nanoTime() - start) / 1_000_000.0;
                System.out.printf(java.util.Locale.ROOT, "SQLITE_REUSE pooled=%s commits=200 elapsedMs=%.2f msPerCommit=%.3f%n", pooled, millis, millis / 200);
                try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("PRAGMA synchronous")) {
                    assertTrue(r.next()); assertEquals(2, r.getInt(1));
                }
            } finally { if (source instanceof io.agroal.api.AgroalDataSource pool) pool.close(); }
        }
    }
}
