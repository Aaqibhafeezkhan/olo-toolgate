// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Trigger exports (ADR 058): Control accepts them with the CLI's rules once the triggers module is on. */
final class TriggerPackageTest {
    private static final String ID = "acme.watch";

    private static void refused(byte[] data, String fragment) {
        var refused = assertThrows(PackageRejected.class, () -> PackageArchive.validate(data, true));
        assertTrue(refused.getMessage().contains(fragment), refused.getMessage());
    }

    private static String with(String descriptor, String member, String json) {
        var d = Json.parse(descriptor);
        var members = new java.util.LinkedHashMap<String, Json>();
        for (String f : d.fieldNames()) members.put(f, d.get(f));
        members.put(member, Json.parse(json));
        return new String(Canonical.bytes(Json.object(members)), StandardCharsets.UTF_8);
    }

    @Test
    void acceptsTriggerExportsAndCoversThemInTheSetDigest() {
        byte[] data = TriggerPackages.build(ID, "1.0.0", List.of(TriggerPackages.clientDescriptor(ID, "new-file"), TriggerPackages.clientDescriptor(ID, "b-file")));
        var verified = PackageArchive.validate(data, true);
        assertEquals(List.of("acme.watch/b-file", "acme.watch/new-file"), List.copyOf(verified.triggerDigests().keySet()));
        assertTrue(verified.toolDigests().isEmpty());
        assertEquals(Canonical.sha256(verified.files().get("triggers/new-file.json")), verified.triggerDigests().get("acme.watch/new-file"));
        var refused = assertThrows(PackageRejected.class, () -> PackageArchive.validate(data));
        assertTrue(refused.getMessage().contains("trigger exports are not accepted"), refused.getMessage());
    }

    @Test
    void refusesWhatTheCliRefuses() {
        String client = TriggerPackages.clientDescriptor(ID, "x");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(TriggerPackages.clientDescriptor("other.pkg", "x"))), "is not in package");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(client, client)), "two triggers share a triggerId");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(client), Map.of("triggers/stray.json", "{}".getBytes(StandardCharsets.UTF_8))), "matches no trigger");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "executors", "[\"CLIENT\",\"SERVER\"]"))), "CLIENT executors only");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "permissions", "[\"DEVICE_FILES_WRITE\"]"))), "may only read the device files area");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "routes", "[\"r\"]"))), "no routes, TCP destinations or secrets");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "overflow", "\"DROP_OLDEST\""))), "lossTolerant");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "mode", "\"PUSH\""))), "a PUSH trigger has schedule.resyncIntervalMs");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(client, "limits",
                "{\"maxBrokerCallsPerMinute\":120,\"maxCheckpointBytes\":131072,\"maxEventBytes\":16384,\"maxEventsPerHour\":600,\"memoryMiB\":256,\"stopGraceMs\":10000}"))),
                "maxCheckpointBytes above 16384");
        String server = with(with(client, "kind", "\"SERVER_TRIGGER\""), "permissions", "[]");
        refused(TriggerPackages.build(ID, "1.0.0", List.of(with(with(server, "executors", "[\"CLIENT\",\"SERVER\"]"), "secrets", "[\"s\"]"))), "cannot run on CLIENT executors");
        PackageArchive.validate(TriggerPackages.build(ID, "1.0.0", List.of(with(server, "executors", "[\"CLIENT\",\"SERVER\"]"))), true);
    }

    /** Parity with the CLI: a package `toolgate pack` built (the inbox-watch sample) passes, when one is given. */
    @Test
    void acceptsAPackageTheCliBuilt() throws Exception {
        String path = System.getProperty("toolgate.triggerPackage", "");
        assumeTrue(!path.isEmpty() && Files.isRegularFile(Path.of(path)), "a CLI-built trigger package is given (-Dtoolgate.triggerPackage)");
        var verified = PackageArchive.validate(Files.readAllBytes(Path.of(path)), true);
        assertEquals(1, verified.triggerDigests().size());
    }
}
