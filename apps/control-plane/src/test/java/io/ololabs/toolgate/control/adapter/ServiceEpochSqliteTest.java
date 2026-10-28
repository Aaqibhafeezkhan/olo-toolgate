// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.*;
import io.ololabs.toolgate.contracts.ServiceAuthorizationEpoch;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.fixtures.Tenants;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceEpochSqliteTest {
    @TempDir Path temp;

    @Test void epochReadPersistsItsClockAndRefusesClockRollback() throws Exception {
        try (var source = SqliteState.openPooled(temp.resolve("service-epoch.sqlite"))) {
            var codec = new ContractCodec();
            var store = new PostgresStore(Tenants.database(source), codec);
            var tenant = Tenants.id("benchmark");
            var service = new DirectoryService.Actor(tenant, "a".repeat(64), false);
            var operations = new EnterpriseOperations(store, codec, null,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), "control", "executor", 15000, 10);
            var permits = new ServicePermits(store, codec, operations);
            var reply = permits.epoch(service);
            assertEquals(200, reply.status());
            var epoch = codec.model(reply.body(), ServiceAuthorizationEpoch.class);
            assertEquals(tenant.value(), epoch.tenantId());
            assertEquals(1000, epoch.observedAtUnixMs());
            // A later persisted floor must continue to fail closed. The fix must
            // not replace the authoritative clock with an unchecked wall clock.
            store.transaction(tenant, true, tx -> tx.approvalClock(2000));
            assertThrows(Failure.class, () -> permits.epoch(service));
        }
    }
}
