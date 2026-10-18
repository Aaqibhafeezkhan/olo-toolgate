// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PackageProvisioningTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    private record Rig(PostgresStore store, ContractCodec codec, Ids.TenantId tenant, DirectoryService.Actor admin, ServerPackages packages, PackageProvisioning provisioning, String digest) {}

    /** The mixed package (a ServerTool and a ClientTool) as the sealed core lock's only artifact. */
    private Rig rig() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(SqliteState.open(directory.resolve("profiles.sqlite")), codec);
        var tenant = new Ids.TenantId("tenant"); var admin = TestSupport.seed(store, codec, tenant, "root");
        store.transaction(tenant, true, tx -> { var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.TOOL_GROUP, new ControlToolGroup("orders-tools", "Orders", true, 1L, List.of()));
            TestSupport.put(entries, codec, Ids.Kind.BINDING, new ControlExecutionBinding("orders-binding", "Orders", true, 1L, "orders-tools", "default-devices", List.of(), List.of(), false, false));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null; });
        byte[] bytes = ServerPackagesTest.zip(ServerPackagesTest.mixed());
        String digest = PackageArchive.validate(bytes, document -> (JsonNode) codec.value(document)).packageDigest();
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        PackageProvisioning.CoreArtifacts core = (id, version) -> id.equals("acme.orders") && version.equals("0.1.0") ? new PackageProvisioning.Core(digest, () -> bytes) : null;
        return new Rig(store, codec, tenant, admin, packages, new PackageProvisioning(store, codec, packages, null, core, null, null, java.time.Clock.systemUTC()), digest);
    }

    private static String profile(String packages) { return "{\"profileVersion\":1,\"profileId\":\"orders\",\"packages\":[" + packages + "]}"; }
    private static final String ORDERS = "{\"packageId\":\"acme.orders\",\"source\":\"PREBUILT_CORE\",\"version\":\"0.1.0\",\"required\":true,\"hostIds\":[\"host-1\"],"
        + "\"globals\":{\"ItemsApiHost\":\"items.example.com\"},\"tools\":[{\"toolId\":\"acme.orders/items.get\",\"toolGroupId\":\"orders-tools\"}],\"bindingIds\":[\"orders-binding\"]}";
    private static JsonNode node(Store.Reply reply) throws Exception { return JSON.readTree(reply.body()); }
    private static JsonNode deployment(Rig rig) throws Exception { String raw = rig.store.<String>transaction(rig.tenant, false, tx -> tx.packages().deployment("acme.orders")); return JSON.readTree(raw); }
    private void register(Rig rig) {
        var host = new EndpointDeviceRecord("host-1", "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
        rig.packages.register(rig.tenant, tx -> host, "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "register");
    }

    @Test void invalidProfilesChangeNothing() throws Exception {
        var rig = rig();
        for (var invalid : List.of(
                profile(ORDERS + "," + ORDERS),
                profile("{\"packageId\":\"acme.orders\",\"source\":\"MARKETPLACE\",\"version\":\"0.1.0\",\"required\":true}"),
                profile("{\"packageId\":\"acme.orders\",\"source\":\"PREBUILT_CORE\",\"version\":\"0.2.0\",\"required\":true}"),
                profile("{\"packageId\":\"acme.orders\",\"source\":\"PREBUILT_CORE\",\"version\":\"0.1.0\",\"packageDigest\":\"sha256:" + "0".repeat(64) + "\",\"required\":true}"),
                profile(ORDERS.replace("acme.orders/items.get", "acme.other/items.get")),
                profile(ORDERS.replace("\"hostIds\":[\"host-1\"],", "")),
                "{\"profileVersion\":2,\"profileId\":\"orders\",\"packages\":[]}"))
            assertEquals(400, assertThrows(Failure.class, () -> rig.provisioning.apply(rig.admin, invalid), invalid).status(), invalid);
        assertTrue(rig.store.transaction(rig.tenant, false, tx -> tx.packages().versions(10)).isEmpty());
        assertEquals(404, assertThrows(Failure.class, () -> rig.provisioning.status(rig.admin, "orders")).status());
        assertEquals("{\"items\":[]}", rig.provisioning.statuses(rig.admin).body());
    }

    @Test void aProfileImportsWaitsForItsHostThenDeploysActivatesAndRepeatsAsANoOp() throws Exception {
        var rig = rig(); String hex = rig.digest.substring(7);
        // The Tool Host has not registered: imported, not deployed, PENDING.
        var pending = node(rig.provisioning.apply(rig.admin, profile(ORDERS)));
        assertEquals("PENDING", pending.get("state").asText()); assertEquals(1, pending.get("generation").asLong());
        assertEquals("DEPLOY", pending.get("packages").get(0).get("step").asText());
        assertTrue(pending.get("packages").get(0).get("reason").asText().contains("host-1"));
        assertNotNull(rig.store.transaction(rig.tenant, false, tx -> tx.packages().version(rig.digest)));
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.packages().deployment("acme.orders")));

        register(rig);
        var ready = node(rig.provisioning.apply(rig.admin, profile(ORDERS)));
        assertEquals("READY", ready.get("state").asText()); assertEquals(2, ready.get("generation").asLong());
        // Only the ServerTool reaches the Tool Host; the ClientTool beside it is never deployed there.
        assertEquals("[\"acme.orders/items.get\"]", ready.get("packages").get(0).get("toolIds").toString());
        var deployment = deployment(rig);
        assertEquals("[\"host-1\"]", deployment.get("hostIds").toString());
        var tool = rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get")));
        assertTrue(tool.enabled());
        List<String> groups = rig.store.transaction(rig.tenant, false, tx -> GroupGraph.memberships(tx.load(), rig.codec, Ids.Kind.TOOL, "acme.orders/items.get", false));
        assertEquals(List.of("orders-tools"), groups);
        var binding = rig.codec.model(rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.BINDING.id("orders-binding")).document()), ControlExecutionBinding.class);
        assertEquals(List.of(hex), binding.allowedPackageDigests());
        // Installation grants nothing: no access grant exists.
        Boolean noGrant = rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().keySet().stream().noneMatch(id -> id.kind() == Ids.Kind.GRANT));
        assertTrue(noGrant);

        // Repeating the identical deployment changes nothing.
        long directoryRevision = rig.store.transaction(rig.tenant, false, tx -> tx.load().revision());
        var again = node(rig.provisioning.apply(rig.admin, profile(ORDERS)));
        assertEquals(2, again.get("generation").asLong(), () -> ready + " | " + again);
        assertEquals(directoryRevision, (long) rig.store.transaction(rig.tenant, false, tx -> tx.load().revision()));
        assertEquals(deployment.get("revision"), deployment(rig).get("revision"));
        assertEquals("READY", node(rig.provisioning.status(rig.admin, "orders")).get("state").asText());
        var listed = node(rig.provisioning.statuses(rig.admin)).get("items");
        assertEquals(1, listed.size()); assertEquals("orders", listed.get(0).get("profileId").asText()); assertEquals(2, listed.get(0).get("generation").asLong());

        // Removing the entry never uninstalls: the deployment and enabled tool stay.
        var emptied = node(rig.provisioning.apply(rig.admin, profile("")));
        assertEquals("READY", emptied.get("state").asText()); assertEquals(rig.digest.length(), emptied.get("previousProfileDigest").asText().length());
        // The change is recorded for review: the package left the profile and is still installed.
        var removed = emptied.get("changes").get(0);
        assertEquals("acme.orders", removed.get("packageId").asText()); assertEquals("REMOVED", removed.get("change").asText());
        assertEquals("0.1.0", removed.get("previousVersion").asText()); assertEquals(rig.digest, removed.get("previousPackageDigest").asText());
        // Adding it back is recorded as an addition of the same pin.
        var restored = node(rig.provisioning.apply(rig.admin, profile(ORDERS)));
        assertEquals("ADDED", restored.get("changes").get(0).get("change").asText());
        assertEquals(rig.digest, restored.get("changes").get(0).get("packageDigest").asText());
        assertNotNull(rig.store.transaction(rig.tenant, false, tx -> tx.packages().deployment("acme.orders")));
        assertTrue(rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get"))).enabled());
    }

    @Test void aToolHostThatCannotSpeakProtocolTwoIsRefusedAtRegistration() throws Exception {
        var rig = rig();
        var host = new EndpointDeviceRecord("host-1", "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
        var old = "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.9.0\",\"protocol\":{\"min\":1,\"max\":1},\"launcherVersion\":\"0.9.0\",\"tiers\":[\"HOST_NATIVE\"]}";
        assertEquals(409, assertThrows(Failure.class, () -> rig.packages.register(rig.tenant, tx -> host, old, "register-old")).status());
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.packages().toolHost("host-1")));
    }

    @Test void unknownExportsAndUnavailableSourcesFailWithoutSubstitutes() throws Exception {
        var rig = rig(); register(rig);
        // The ClientTool is not an installed export on a Tool Host deployment: the reference blocks; nothing is created.
        var client = node(rig.provisioning.apply(rig.admin, profile(ORDERS.replace("acme.orders/items.get", "acme.orders/items.local"))));
        assertEquals("FAILED", client.get("state").asText());
        assertEquals("ACTIVATE", client.get("packages").get(0).get("step").asText());
        assertTrue(client.get("packages").get(0).get("reason").asText().contains("not an installed export"));
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.local"))));

        // A Marketplace package with no Marketplace configured fails; an optional one does not block readiness.
        String market = "{\"packageId\":\"examples.loans\",\"source\":\"MARKETPLACE\",\"version\":\"0.1.0\",\"packageDigest\":\"sha256:" + "1".repeat(64) + "\",\"required\":%s}";
        var required = node(rig.provisioning.apply(rig.admin, profile(ORDERS + "," + market.formatted("true"))));
        assertEquals("FAILED", required.get("state").asText()); assertEquals("IMPORT", required.get("packages").get(1).get("step").asText());
        var optional = node(rig.provisioning.apply(rig.admin, profile(ORDERS + "," + market.formatted("false"))));
        assertEquals("READY", optional.get("state").asText()); assertEquals("FAILED", optional.get("packages").get(1).get("state").asText());

        // A binding that does not exist is reported, never created.
        var missing = node(rig.provisioning.apply(rig.admin, profile(ORDERS.replace("orders-binding", "nowhere"))));
        assertEquals("FAILED", missing.get("state").asText());
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.BINDING.id("nowhere"))));
    }

    @Test void aRestoredInstallationReconcilesItsExactRecordedPackagesAsANoOp() throws Exception {
        var rig = rig(); register(rig);
        var applied = node(rig.provisioning.apply(rig.admin, profile(ORDERS)));
        assertEquals("READY", applied.get("state").asText());
        long revision = deployment(rig).get("revision").asLong();
        // A backup is the database file. The restored installation runs the same image, so the same sealed core lock;
        // its recorded package is already installed, so the artifact is never read again.
        var restoredPath = directory.resolve("restored.sqlite");
        java.nio.file.Files.copy(directory.resolve("profiles.sqlite"), restoredPath);
        var restored = new PostgresStore(SqliteState.open(restoredPath), rig.codec);
        var packages = new ServerPackages(restored, rig.codec, null, java.time.Clock.systemUTC());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        PackageProvisioning.CoreArtifacts core = (id, version) -> id.equals("acme.orders") && version.equals("0.1.0")
                ? new PackageProvisioning.Core(rig.digest, () -> { reads.incrementAndGet(); throw new AssertionError("re-imported"); }) : null;
        var reconcile = new PackageProvisioning(restored, rig.codec, packages, null, core, null, null, java.time.Clock.systemUTC());
        var again = node(reconcile.apply(rig.admin, profile(ORDERS)));
        // The exact recorded package is installed: nothing is imported, redeployed, substituted or granted.
        assertEquals("READY", again.get("state").asText(), again::toString);
        assertEquals(applied.get("generation").asLong(), again.get("generation").asLong());
        assertEquals(rig.digest, again.get("packages").get(0).get("packageDigest").asText());
        String raw = restored.<String>transaction(rig.tenant, false, tx -> tx.packages().deployment("acme.orders"));
        assertEquals(revision, JSON.readTree(raw).get("revision").asLong());
        Boolean noGrant = restored.transaction(rig.tenant, false, tx -> tx.load().entries().keySet().stream().noneMatch(id -> id.kind() == Ids.Kind.GRANT));
        assertTrue(noGrant);
        assertEquals(0, reads.get());
    }

    @Test void readersNeedPackageRead() throws Exception {
        var rig = rig(); rig.provisioning.apply(rig.admin, profile(ORDERS));
        var reader = new DirectoryService.Actor(rig.tenant, "b".repeat(64), true, false, "mallory");
        assertThrows(Failure.class, () -> rig.provisioning.status(reader, "orders"));
        assertThrows(Failure.class, () -> rig.provisioning.statuses(reader));
    }
}
