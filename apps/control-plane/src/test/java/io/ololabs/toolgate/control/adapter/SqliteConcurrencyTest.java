// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConcurrencyTest {
    @TempDir Path temp;
    @Test void queuedWritersLeaveConnectionsAvailableForWalReaders() throws Exception {
        try (var source = SqliteState.openPooled(temp.resolve("reader.sqlite"));
             var executor = Executors.newFixedThreadPool(5)) {
            var borrowed = new java.util.concurrent.atomic.AtomicInteger();
            var observed = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    try {
                        var result = method.invoke(source, args);
                        if (method.getName().equals("getConnection")) borrowed.incrementAndGet();
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(observed), new ContractCodec());
            var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("benchmark");
            store.transaction(tenant, true, tx -> tx.approvalClock(1));
            borrowed.set(0);
            var held = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writer = executor.submit(() -> store.transaction(tenant, true, tx -> {
                held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return tx.approvalClock(2);
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var writers = new java.util.ArrayList<Future<?>>();
            var started = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) writers.add(executor.submit(() -> {
                started.countDown(); return store.transaction(tenant, true, tx -> tx.approvalClock(2));
            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                // Queued writers must wait outside the pool, rather than consume all
                // four connections while SQLite can admit only one writer.
                var reader = executor.submit(() -> store.transaction(tenant, false, tx -> tx.load().revision()));
                assertNotNull(reader.get(2, TimeUnit.SECONDS));
                assertTrue(borrowed.get() <= 2, "Waiting writers must not borrow reader connections");
            } finally { release.countDown(); }
            assertEquals(2L, writer.get(5, TimeUnit.SECONDS));
            for (var pending : writers) assertNotNull(pending.get(5, TimeUnit.SECONDS));
        }
    }
    @Test void concurrentReadersAndWritersReuseThePool() throws Exception {
        try (var source = SqliteState.openPooled(temp.resolve("concurrent.sqlite"));
             var executor = Executors.newFixedThreadPool(7)) {
            var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), new ContractCodec());
            var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("benchmark");
            store.transaction(tenant, true, tx -> tx.approvalClock(1));
            var start = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int worker = 0; worker < 7; worker++) {
                final boolean write = worker < 3;
                jobs.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 150; i++) store.transaction(tenant, write, tx -> write ? tx.approvalClock(1) : tx.load().revision());
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) assertDoesNotThrow(() -> job.get(30, TimeUnit.SECONDS));
        }
    }
}
