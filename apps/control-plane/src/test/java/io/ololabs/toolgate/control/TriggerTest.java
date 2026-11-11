// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.SignedTriggerDesiredDocument;
import io.ololabs.toolgate.contracts.v2.TriggerDesiredDocument;
import io.ololabs.toolgate.contracts.v2.TriggerRevocationReason;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.packagearchive.TriggerPackages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Triggers module 4 (issue #274): package acceptance of trigger exports, definitions, assignment CRUD with ETags,
 * idempotency and dry runs, instance resolution, the signed desired document, client leases, permissions and tenant
 * isolation. Every scenario runs on SQLite, and on PostgreSQL when {@code CONTROL_TEST_URL} names an isolated server.
 */
final class TriggerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);
    private static final String PACKAGE = "acme.watch";
    private final ContractCodec codec = new ContractCodec();

    /** One isolated store per run: SQLite always, PostgreSQL when configured. */
    private List<Map.Entry<String, PostgresStore>> stores() throws Exception {
        var out = new ArrayList<Map.Entry<String, PostgresStore>>();
        var directory = Files.createTempDirectory("toolgate-triggers");
        out.add(Map.entry("sqlite", new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("triggers.sqlite"))), codec)));
        String base = System.getenv("CONTROL_TEST_URL");
        if (base != null) {
            String password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
            String database = "triggers_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = java.sql.DriverManager.getConnection(base, "control_migrator", password); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
            String url = base.replace("/control?", "/" + database + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var source = new org.postgresql.ds.PGSimpleDataSource();
            source.setURL(url);
            source.setUser("control_app");
            source.setPassword(password);
            out.add(Map.entry("postgresql", new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec)));
        }
        return out;
    }

    private RsaFleetCrypto crypto() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var signer = trust("signer", (RSAPublicKey) pair.getPublic());
        var release = trust("release", (RSAPublicKey) generator.generateKeyPair().getPublic());
        return new RsaFleetCrypto(codec, (RSAPrivateCrtKey) pair.getPrivate(), "signer", List.of(release), List.of(signer), List.of());
    }

    private static FleetTrustKey trust(String kid, RSAPublicKey key) {
        var bytes = key.getModulus().toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return new FleetTrustKey(kid, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), "AQAB");
    }

    /** A tenant with a super administrator, devices a-d (d not yet in a group) and Device Group "finance" = {a, b}. */
    private record World(PostgresStore store, Ids.TenantId tenant, DirectoryService.Actor admin, TriggerService triggers, ServerPackages packages,
                         EndpointService endpoints, RsaFleetCrypto crypto) {}

    private World world(PostgresStore store, String label) throws Exception {
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id(label);
        var admin = TestSupport.seed(store, codec, tenant, "root");
        TestSupport.pool(store, codec, tenant, "finance", "device-a", "device-b");
        TestSupport.pool(store, codec, tenant, "spare", "device-c", "device-d");
        for (String id : List.of("device-a", "device-b", "device-c", "device-d")) {
            store.transaction(tenant, true, tx -> {
                tx.saveEndpoint(new Store.EndpointRecord(id, DirectoryService.digest(id), codec.json(new EndpointDeviceRecord(id, tenant.value(), "root", DirectoryService.digest(id),
                        EndpointState.ACTIVE, 1L, 1700000000000L, 1L, null, null, null, null, null, null)), "public", "0".repeat(64), "{}"));
                return null;
            });
        }
        var endpoints = new EndpointService(store, codec, null, CLOCK, true, tenant.value(), "server", "Organization", "https://control.example.test", "https://gateway.example.test");
        var crypto = crypto();
        var triggers = new TriggerService(store, codec, crypto, endpoints, CLOCK, true, tenant.value());
        var packages = new ServerPackages(store, codec, null, CLOCK).acceptTriggers(true);
        return new World(store, tenant, admin, triggers, packages, endpoints, crypto);
    }

    private static String descriptor(String name) { return TriggerPackages.clientDescriptor(PACKAGE, name); }

    private static JsonNode json(String text) throws Exception { return JSON.readTree(text); }

    private static String create(String name, String target, String config, String state) {
        return """
                {"name":"%s","labels":[{"key":"team","value":"finance"}],"spec":{"trigger":"acme.watch/new-file","packageVersion":"1.0.0",
                 "target":%s,"config":%s,"desiredState":"%s"}}""".formatted(name, target, config, state);
    }

    private static final String TARGET = "{\"executor\":\"CLIENT\",\"deviceGroups\":[\"finance\"],\"devices\":[\"device-c\"],\"excludeDevices\":[\"device-b\"]}";

    private EndpointDeviceRecord device(World w, String id) {
        return w.store().transaction(w.tenant(), false, tx -> codec.model(tx.endpoint(id).document(), EndpointDeviceRecord.class));
    }

    private TriggerDesiredDocument desired(World w, String id) {
        var signed = w.store().transaction(w.tenant(), true, tx -> w.triggers().desired(tx, device(w, id)));
        return w.crypto().verify(signed);
    }

    private TriggerService.Leases leases(World w, String id) {
        return w.store().transaction(w.tenant(), true, tx -> w.triggers().leases(tx, device(w, id), "request"));
    }

    private boolean valid(World w, String executor, String id, long epoch, String token) {
        Boolean valid = w.store().transaction(w.tenant(), false, tx -> w.triggers().leaseValid(tx, executor, id, epoch, token));
        return valid;
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 0;
        } catch (Failure failure) {
            return failure.status();
        }
    }

    @Test
    void packageAcceptanceDefinitionsAndSecurityDelta() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a");
            byte[] first = TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor("new-file")));
            var refusing = new ServerPackages(w.store(), codec, null, CLOCK);
            assertEquals(400, status(() -> refusing.upload(w.admin(), first, "request")), "the module flag gates trigger exports");
            assertEquals(201, w.packages().upload(w.admin(), first, "request").status());
            String wider = descriptor("new-file").replace("\"maxEventsPerHour\":600", "\"maxEventsPerHour\":1200");
            assertEquals(201, w.packages().upload(w.admin(), TriggerPackages.build(PACKAGE, "1.1.0", List.of(wider)), "request").status());
            var page = json(w.triggers().definitions(w.admin(), null, null).body());
            assertEquals(2, page.path("items").size(), entry.getKey());
            var byVersion = new HashMap<String, JsonNode>();
            for (var item : page.path("items")) byVersion.put(item.path("packageVersion").asText(), item);
            var initial = byVersion.get("1.0.0").path("securityDelta");
            assertEquals("KIND", initial.path(0).path("kind").asText());
            assertTrue(initial.toString().contains("DEVICE_FILES_READ") && initial.toString().contains("OCI_SANDBOX"), initial.toString());
            var later = byVersion.get("1.1.0").path("securityDelta");
            assertEquals(1, later.size(), later.toString());
            assertTrue(later.path(0).path("summary").asText().contains("maxEventsPerHour to 1200"));
            var one = json(w.triggers().definitions(w.admin(), null, 1).body());
            assertEquals(1, one.path("items").size());
            var rest = json(w.triggers().definitions(w.admin(), one.path("nextPageToken").asText(), 1).body());
            assertNotEquals(one.path("items").path(0).path("packageDigest"), rest.path("items").path(0).path("packageDigest"));
        }
    }

    @Test
    void scopedActionChangesOnlyTheTargetedDesiredDocument() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a");
            w.packages().upload(w.admin(), TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor("new-file"))), "request");
            w.triggers().create(w.admin(), create("inbox", TARGET, "{\"folder\":\"inbox\"}", "Running"), null, false, "request");
            var unaffected = desired(w, "device-c");
            String pause = """
                    {"action":"Pause","scope":{"deviceGroups":["finance"]},"reason":"Maintenance"}
                    """;
            assertEquals(404, status(() -> w.triggers().action(w.admin(), "inbox",
                    pause.replace("\"finance\"", "\"finance\",\"unknown\""), "W/\"1\"", "request")));
            assertEquals("Running", desired(w, "device-a").instances().getFirst().desiredState().name());

            var result = json(w.triggers().action(w.admin(), "inbox", pause, "W/\"1\"", "request").body());
            assertEquals(1, result.path("changes").size());
            assertEquals("Paused", desired(w, "device-a").instances().getFirst().desiredState().name());
            assertEquals(unaffected.generation(), desired(w, "device-c").generation());
            String stop = """
                    {"action":"Stop","scope":{"devices":["device-a"]},"reason":"Maintenance"}
                    """;
            w.triggers().action(w.admin(), "inbox", stop, "W/\"2\"", "request");
            assertEquals("Stopped", desired(w, "device-a").instances().getFirst().desiredState().name());
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"other\"}}}",
                    "W/\"3\"", null, false, "request");
            assertEquals("Stopped", desired(w, "device-a").instances().getFirst().desiredState().name());
            w.triggers().action(w.admin(), "inbox", stop.replace("Stop", "Clear"), "W/\"4\"", "request");
            assertEquals("Paused", desired(w, "device-a").instances().getFirst().desiredState().name());
            assertEquals(412, status(() -> w.triggers().action(w.admin(), "inbox", pause, "W/\"1\"", "request")));
            w.triggers().action(w.admin(), "inbox", pause.replace("Pause", "Clear"), "W/\"5\"", "request");
            assertEquals("Running", desired(w, "device-a").instances().getFirst().desiredState().name());
        }
    }

    @Test
    void assignmentLifecycleDesiredDocumentsAndLeases() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a");
            w.packages().upload(w.admin(), TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor("new-file"))), "request");

            // Validation: config against the signed schema, a real definition, a client target, known groups.
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", TARGET, "{}", "Running"), null, false, "request")));
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", TARGET, "{\"folder\":\"inbox\",\"other\":1}", "Running"), null, false, "request")));
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", TARGET, "{\"folder\":\"inbox\"}", "Running").replace("1.0.0", "9.9.9"), null, false, "request")));
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", TARGET.replace("CLIENT", "SERVER"), "{\"folder\":\"inbox\"}", "Running"), null, false, "request")));
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", TARGET.replace("finance", "nope"), "{\"folder\":\"inbox\"}", "Running"), null, false, "request")));
            assertEquals(400, status(() -> w.triggers().create(w.admin(), create("bad", "{\"executor\":\"CLIENT\",\"devices\":[\"device-a\",\"device-a\"]}", "{\"folder\":\"inbox\"}", "Running"), null, false, "request")));

            // Dry run: the would-be result, nothing written.
            var dry = json(w.triggers().create(w.admin(), create("inbox", TARGET, "{\"folder\":\"inbox\"}", "Running"), null, true, "request").body());
            assertTrue(dry.path("dryRun").asBoolean());
            assertEquals(2, dry.path("changes").size());
            assertEquals(0, json(w.triggers().list(w.admin(), null, null, null).body()).path("items").size());

            // Create, idempotently.
            String body = create("inbox", TARGET, "{\"folder\":\"inbox\"}", "Running");
            var created = w.triggers().create(w.admin(), body, "create-1", false, "request");
            assertEquals(201, created.status());
            assertEquals(created.body(), w.triggers().create(w.admin(), body, "create-1", false, "request").body(), "a replay returns the original response");
            assertEquals(409, status(() -> w.triggers().create(w.admin(), body, "create-2", false, "request")), "names are unique");
            var result = json(created.body());
            assertEquals(List.of("Added", "Added"), List.of(result.path("changes").path(0).path("change").asText(), result.path("changes").path(1).path("change").asText()));
            var assignment = result.path("assignment");
            assertEquals("W/\"1\"", assignment.path("metadata").path("resourceVersion").asText());
            assertEquals(2, assignment.path("status").path("instances").path("desired").asInt());
            assertEquals(2, assignment.path("status").path("instances").path("pending").asInt());
            assertEquals("InstancesPending", assignment.path("status").path("conditions").path(0).path("reason").asText());
            assertEquals(1, w.triggers().get(w.admin(), "inbox").revision());

            // Instances: device-a (group) and device-c (named); device-b is excluded.
            var instances = json(w.triggers().instances(w.admin(), "inbox", null, null, null).body()).path("items");
            assertEquals(2, instances.size());
            var executors = new TreeSet<String>();
            instances.forEach(i -> executors.add(i.path("executorId").asText()));
            assertEquals(Set.of("device-a", "device-c"), executors);
            var firstPage = json(w.triggers().instances(w.admin(), null, null, null, 1).body());
            var secondPage = json(w.triggers().instances(w.admin(), null, null, firstPage.path("nextPageToken").asText(), 1).body());
            assertNotEquals(firstPage.path("items").path(0).path("instanceId"), secondPage.path("items").path(0).path("instanceId"));

            // The signed desired document for each device.
            var doc = desired(w, "device-a");
            assertEquals(w.tenant().value(), doc.tenantId());
            assertEquals("device-a", doc.executorId());
            assertEquals(1, doc.instances().size());
            var instance = doc.instances().get(0);
            assertEquals("acme.watch/new-file", instance.triggerId());
            assertEquals("Running", instance.desiredState().name());
            assertEquals("inbox", instance.config().get("folder").asText());
            assertEquals(1L, instance.configRevision());
            assertEquals(CLOCK.millis() + 24L * 60 * 60 * 1000, doc.expiresAtUnixMs());
            assertEquals(0, desired(w, "device-b").instances().size(), "excluded devices get nothing");
            long generation = doc.generation();
            assertEquals(generation, desired(w, "device-a").generation(), "an unchanged set keeps its generation");

            // Client leases: epoch 1, a 256-bit token stored as a digest, renewal keeps the epoch and rotates the token.
            var lease = leases(w, "device-a").leases().get(0);
            assertEquals(1L, lease.leaseEpoch());
            assertEquals(43, lease.leaseToken().length());
            String id = lease.instanceId();
            assertTrue(valid(w, "device-a", id, 1L, lease.leaseToken()));
            assertFalse(valid(w, "device-c", id, 1L, lease.leaseToken()), "bound to its executor");
            assertFalse(valid(w, "device-a", id, 1L, "A".repeat(43)));
            var renewed = leases(w, "device-a").leases().get(0);
            assertEquals(1L, renewed.leaseEpoch());
            assertNotEquals(lease.leaseToken(), renewed.leaseToken());
            assertFalse(valid(w, "device-a", id, 1L, lease.leaseToken()), "the old token stops working");
            assertTrue(valid(w, "device-a", id, 1L, renewed.leaseToken()));

            // PATCH: If-Match is required, a stale ETag is refused, a config change is a new generation and config revision.
            assertEquals(428, status(() -> w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"in2\"}}}", null, null, false, "request")));
            assertEquals(412, status(() -> w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"in2\"}}}", "W/\"7\"", null, false, "request")));
            assertEquals(400, status(() -> w.triggers().patch(w.admin(), "inbox", "{\"metadata\":{}}", "W/\"1\"", null, false, "request")));
            var patched = json(w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"in2\"}}}", "W/\"1\"", "patch-1", false, "request").body());
            assertEquals(2, patched.path("assignment").path("metadata").path("generation").asInt());
            assertEquals(2, patched.path("assignment").path("metadata").path("configRevision").asInt());
            assertEquals("Updated", patched.path("changes").path(0).path("change").asText());
            var after = desired(w, "device-a");
            assertTrue(after.generation() > generation, "the device's generation advances");
            assertEquals("in2", after.instances().get(0).config().get("folder").asText());
            var second = leases(w, "device-a").leases().get(0);
            assertEquals(2L, second.leaseEpoch(), "a new config revision is a new lease epoch");
            assertFalse(valid(w, "device-a", id, 1L, second.leaseToken()), "a lower epoch is refused");
            assertTrue(valid(w, "device-a", id, 2L, second.leaseToken()));
            var labelsOnly = json(w.triggers().patch(w.admin(), "inbox", "{\"labels\":[{\"key\":\"team\",\"value\":\"ops\"}]}", "W/\"2\"", null, false, "request").body());
            assertEquals(2, labelsOnly.path("assignment").path("metadata").path("generation").asInt(), "labels are metadata, not spec");
            assertEquals(1, json(w.triggers().list(w.admin(), null, null, "team=ops").body()).path("items").size());
            assertEquals(0, json(w.triggers().list(w.admin(), null, null, "team=finance").body()).path("items").size());

            // Stop: the lease is released and the device is told once.
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"desiredState\":\"Stopped\"}}", "W/\"3\"", null, false, "request");
            var stopped = leases(w, "device-a");
            assertTrue(stopped.leases().isEmpty());
            assertEquals(1, stopped.revoked().size());
            assertEquals(TriggerRevocationReason.AssignmentStopped, stopped.revoked().get(0).reason());
            assertTrue(leases(w, "device-a").revoked().isEmpty(), "reported once");
            assertEquals("Stopped", desired(w, "device-a").instances().get(0).desiredState().name());
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"desiredState\":\"Running\"}}", "W/\"4\"", null, false, "request");
            assertEquals(3L, leases(w, "device-a").leases().get(0).leaseEpoch(), "Run after Stop is a new epoch");

            // A device that joins the group later gets the instance at its next desired fetch.
            TestSupport.pool(w.store(), codec, w.tenant(), "finance", "device-a", "device-b", "device-d");
            assertEquals(1, desired(w, "device-d").instances().size());
            assertEquals(3, json(w.triggers().instances(w.admin(), "inbox", null, null, null).body()).path("items").size());

            // DELETE removes it everywhere and revokes leases; the name can be reused.
            long before = desired(w, "device-a").generation();
            var removed = json(w.triggers().delete(w.admin(), "inbox", "W/\"5\"", null, false, "request").body());
            assertEquals(3, removed.path("changes").size());
            removed.path("changes").forEach(c -> assertEquals("Removed", c.path("change").asText()));
            assertEquals(404, status(() -> w.triggers().get(w.admin(), "inbox")));
            var gone = desired(w, "device-a");
            assertEquals(0, gone.instances().size());
            assertTrue(gone.generation() > before);
            assertEquals(TriggerRevocationReason.AssignmentRemoved, leases(w, "device-a").revoked().get(0).reason());
            assertEquals(201, w.triggers().create(w.admin(), body, "create-3", false, "request").status());
            assertNotEquals(result.path("assignment").path("metadata").path("uid"), json(w.triggers().get(w.admin(), "inbox").body()).path("metadata").path("uid"));
        }
    }

    @Test
    void desiredDocumentSignatureIsVerifiedAndTypeSeparated() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a");
            w.packages().upload(w.admin(), TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor("new-file"))), "request");
            w.triggers().create(w.admin(), create("inbox", TARGET, "{\"folder\":\"inbox\"}", "Running"), null, false, "request");
            var signed = w.store().transaction(w.tenant(), true, tx -> w.triggers().desired(tx, device(w, "device-a")));
            assertEquals("device-a", w.crypto().verify(signed).executorId());
            var parts = signed.document().split("\\.");
            var header = new String(Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(header.contains("\"typ\":\"toolgate-trigger-desired+jws\"") && header.contains("\"alg\":\"RS256\""), header);
            // A tampered payload, a fleet document and another organization's key all fail.
            parts[1] = parts[1].substring(0, parts[1].length() - 2) + (parts[1].endsWith("AA") ? "BB" : "AA");
            assertThrows(Failure.class, () -> w.crypto().verify(new SignedTriggerDesiredDocument(String.join(".", parts))));
            assertThrows(Failure.class, () -> w.crypto().grant(new FleetSignedDocument(signed.document())), "a trigger document is not a fleet grant");
            var fleet = w.crypto().desired(new FleetDesiredDocument(1L, w.tenant().value(), "server", "device-a", 1L, 1L, 2L, List.of()));
            assertThrows(Failure.class, () -> w.crypto().verify(new SignedTriggerDesiredDocument(fleet.jws())), "a fleet desired document is not a trigger document");
            var other = crypto();
            assertThrows(Failure.class, () -> other.verify(signed), "another organization key does not verify it");
        }
    }

    @Test
    void crossTenantAndUnknownGiveTheSame404AndPermissionsGate() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a");
            w.packages().upload(w.admin(), TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor("new-file"))), "request");
            w.triggers().create(w.admin(), create("inbox", TARGET, "{\"folder\":\"inbox\"}", "Running"), null, false, "request");
            var b = world(entry.getValue(), "tenant-b");
            var outsider = b.admin();
            Function<Runnable, String> message = call -> {
                try {
                    call.run();
                    return "no failure";
                } catch (Failure failure) {
                    return failure.status() + " " + failure.getMessage();
                }
            };
            String unknown = message.apply(() -> w.triggers().get(w.admin(), "missing"));
            assertEquals("404 Trigger resource not found", unknown);
            assertEquals(unknown, message.apply(() -> w.triggers().get(outsider, "inbox")));
            assertEquals(unknown, message.apply(() -> w.triggers().list(outsider, null, null, null)));
            assertEquals(unknown, message.apply(() -> w.triggers().instances(outsider, "inbox", null, null, null)));
            assertEquals(unknown, message.apply(() -> w.triggers().patch(outsider, "inbox", "{\"spec\":{\"desiredState\":\"Paused\"}}", "W/\"1\"", null, false, "request")));
            assertEquals(unknown, message.apply(() -> w.triggers().delete(outsider, "inbox", "W/\"1\"", null, false, "request")));
            assertEquals(unknown, message.apply(() -> w.triggers().definitions(outsider, null, null)));
            assertEquals(unknown, message.apply(() -> w.triggers().create(outsider, create("x", TARGET, "{\"folder\":\"inbox\"}", "Running"), null, false, "request")));
            // Tenant B's own view holds nothing of tenant A, even with the same names.
            assertEquals(0, json(b.triggers().list(b.admin(), null, null, null).body()).path("items").size());
            assertEquals(0, json(b.triggers().definitions(b.admin(), null, null).body()).path("items").size());
            assertEquals(404, status(() -> b.triggers().get(b.admin(), "inbox")));

            // A limited administrator manages triggers on Device Group "finance" only.
            limited(w, "limited", "finance", List.of("trigger.read", "trigger.manage"));
            var limited = new DirectoryService(w.store(), codec, 512, 1048576).portalActor(w.tenant(), "b".repeat(64), "limited");
            assertEquals(403, status(() -> w.triggers().get(limited, "inbox")), "the assignment also targets device-c, outside the grant");
            assertEquals(0, json(w.triggers().list(limited, null, null, null).body()).path("items").size(), "lists hide what the actor cannot read");
            String financeOnly = create("finance-only", "{\"executor\":\"CLIENT\",\"deviceGroups\":[\"finance\"]}", "{\"folder\":\"inbox\"}", "Paused");
            assertEquals(201, w.triggers().create(limited, financeOnly, null, false, "request").status());
            assertEquals(1, json(w.triggers().list(limited, null, null, null).body()).path("items").size());
            assertEquals(403, status(() -> w.triggers().create(limited, create("c", "{\"executor\":\"CLIENT\",\"devices\":[\"device-c\"]}", "{\"folder\":\"inbox\"}", "Running"), null, false, "request")));
            assertEquals(403, status(() -> w.triggers().patch(limited, "finance-only", "{\"spec\":{\"target\":{\"executor\":\"CLIENT\",\"deviceGroups\":[\"finance\",\"spare\"]}}}", "W/\"1\"", null, false, "request")),
                    "a patch cannot widen past the actor's groups");
            // Paused instances still hold a lease (plan 10.3).
            assertEquals(2, leases(w, "device-a").leases().size());

            // Off by default: every route is unavailable.
            var off = new TriggerService(w.store(), codec, w.crypto(), w.endpoints(), CLOCK, false, w.tenant().value());
            assertEquals(503, status(() -> off.list(w.admin(), null, null, null)));
            assertEquals(503, status(() -> off.definitions(w.admin(), null, null)));
        }
    }

    /** A portal administrator whose only management rule grants these actions on one Device Group. */
    private void limited(World w, String user, String group, List<String> actions) {
        w.store().transaction(w.tenant(), true, tx -> {
            var before = tx.load();
            var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.USER, new ControlUser(user, "Limited administrator", true, 1L, null, null, null));
            GroupGraph.addDefault(entries, codec, Ids.Kind.USER, user);
            var none = new GroupSelection(List.of(), false);
            var only = new GroupSelection(List.of(group), false);
            var conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
            var ceiling = new EnterpriseScope(none, only, List.of(), false, List.of(), conditions);
            var rule = new EnterpriseManagementRule(actions, EnterpriseGroupType.DEVICE_GROUP, only, List.of(ceiling), conditions, null);
            TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole("trigger-operator", "Trigger operator", true, 1L, UserRole.ADMINISTRATOR, EnterpriseRoleType.MANAGEMENT, List.of(rule)));
            TestSupport.put(entries, codec, Ids.Kind.TEAM, new ControlTeam("trigger-operators", "Trigger operators", true, 1L, List.of(user), List.of("trigger-operator"),null));
            tx.save(before, new Directory(before.revision() + 1, entries));
            return null;
        });
    }
}
