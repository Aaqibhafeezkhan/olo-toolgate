// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.adapter.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Admission and immutable complete-scope settlement on fresh supported stores; no platform attestation claim. */
final class DeviceFileReadAdmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE = "sha256:" + "9".repeat(64);
    @TempDir Path temp;
    @BeforeAll static void key() throws Exception { EnterpriseOperationTest.signingKey(); }

    private EnterpriseOperationTest rig(boolean postgres) throws Exception {
        var rig = new EnterpriseOperationTest();
        rig.temp = Files.createDirectory(temp.resolve("rig-" + UUID.randomUUID()));
        rig.setup();
        if (postgres) {
            String base = System.getenv("CONTROL_TEST_URL"), password = System.getenv("CONTROL_TEST_PASSWORD");
            Assumptions.assumeTrue(base != null, "isolated PostgreSQL required");
            String name = "device_read_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = java.sql.DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) {
                s.execute("CREATE DATABASE " + name);
            }
            String url = base.replace("/control?", "/" + name + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var source = new org.postgresql.ds.PGSimpleDataSource(); source.setURL(url); source.setUser("control_app"); source.setPassword(password);
            rig.store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), rig.graph.codec);
            rig.store.transaction(rig.gateway.tenant(), true, tx -> { tx.save(tx.load(), rig.graph.directory()); return null; });
            EnterpriseConformanceTest.online(rig.store, rig.graph.codec, "device");
            rig.operations = new EnterpriseOperations(rig.store, rig.graph.codec, rig.signer, rig.clock, "control", "toolgate-client", 60000, 100);
        }
        var request = rig.request("template", "data/report.txt");
        var tool = rig.graph.codec.model(rig.graph.entries.get(io.ololabs.toolgate.control.domain.Ids.Kind.TOOL.id("tool")).document(), ControlTool.class);
        String key = Canonical.digest(JSON.valueToTree(List.of("device-file-read-qualification/v1", "tool", tool.packageDigest())));
        var proof = JSON.createObjectNode().put("format", "device-file-read-qualification/v1").put("toolId", "tool")
            .put("managedDigest", tool.packageDigest()).put("imageDigest", IMAGE)
            .put("runtimeDigest", "sha256:" + "1".repeat(64)).put("descriptorDigest", "sha256:" + "2".repeat(64));
        rig.store.transaction(rig.gateway.tenant(), true, tx -> {
            tx.packages().saveDocument(key, proof.toString());
            var local = new LocalToolInfo("tool", "read", "Read", true, tool.definition().inputSchema(), request.toolDigest(), tool.packageDigest(), null);
            tx.saveEndpointConfiguration(new Store.EndpointConfiguration("device", 1, "{}", null, rig.graph.codec.json(new LocalToolCatalog(List.of(local)))));
            return null;
        });
        return rig;
    }

    private void scenario(boolean postgres) throws Exception {
        for (String pin : List.of("", "device@devices-a@" + IMAGE, "other@devices-a@" + IMAGE,
                                 "device@other@" + IMAGE, "device@devices-a@sha256:" + "0".repeat(64))) {
            var rig = rig(postgres);
            rig.operations.deviceFileReaders(pin.isEmpty() ? Set.of() : Set.of(pin));
            var reservation = rig.reserve(rig.submit("read-" + UUID.randomUUID()));
            boolean qualified = pin.equals("device@devices-a@" + IMAGE);
            assertEquals(qualified, rig.store.transaction(rig.gateway.tenant(), false,
                tx -> tx.enterprise().recoveryScope(reservation.invocation().reservedNonce()) != null));
            var running = rig.consume(reservation);
            String nonce = running.reservedNonce();
            var report = new EnterpriseEffectReport(running.id(), running.revision(), EnterpriseInvocationState.SUCCEEDED,
                running.evaluation().resources(), "3".repeat(64));
            rig.operations.report(rig.gateway.tenant(), "device", rig.graph.codec.json(report));
            var outcome = rig.store.transaction(rig.gateway.tenant(), true,
                tx -> new OperationFinalizer(rig.graph.codec).refresh(tx, running.id(), rig.clock.millis()));
            assertEquals(qualified ? EnterpriseOperationState.SUCCEEDED : EnterpriseOperationState.OUTCOME_UNKNOWN, outcome.state());
            assertEquals(qualified, outcome.finalized());
            assertFalse(outcome.retryable());
        }
    }
    @Test void sqliteCurrentPinsAreRequiredBeforeConsumption() throws Exception { scenario(false); }
    @Test void postgresCurrentPinsAreRequiredBeforeConsumption() throws Exception { scenario(true); }
}
