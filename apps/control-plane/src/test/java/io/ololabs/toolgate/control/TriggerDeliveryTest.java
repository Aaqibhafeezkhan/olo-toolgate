// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.TriggerClassification;
import io.ololabs.toolgate.contracts.v2.TriggerOutboxRecord;
import io.ololabs.toolgate.contracts.v2.TriggerSync;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Triggers modules 10, 11 and 12 (issue #274) through real ingest: the event router and retention, coarse information
 * flow, and subscriptions with the {@code toolgate.triggers.v1} protocol (without the socket transport). Owns M12
 * scenarios 16 and 31 at the protocol level. Every scenario runs on SQLite, and on PostgreSQL when
 * {@code CONTROL_TEST_URL} names an isolated server.
 */
final class TriggerDeliveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1700000000000L, DAY = 86_400_000L;
    private final ContractCodec codec = new ContractCodec();

    static final class MutableClock extends Clock {
        long millis = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    private record Backend(String name, PostgresStore store, Path sqlite) {}

    private List<Backend> stores() throws Exception {
        var out = new ArrayList<Backend>();
        var directory = Files.createTempDirectory("toolgate-delivery");
        var file = directory.resolve("delivery.sqlite");
        out.add(new Backend("sqlite", new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(file)), codec), file));
        String base = System.getenv("CONTROL_TEST_URL");
        if (base != null) {
            String password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
            String database = "delivery_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = java.sql.DriverManager.getConnection(base, "control_migrator", password); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
            String url = base.replace("/control?", "/" + database + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var source = new org.postgresql.ds.PGSimpleDataSource();
            source.setURL(url);
            source.setUser("control_app");
            source.setPassword(password);
            out.add(new Backend("postgresql", new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(source), codec), null));
        }
        return out;
    }

    private static FleetTrustKey trust(String kid, RSAPublicKey key) {
        var bytes = key.getModulus().toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return new FleetTrustKey(kid, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), "AQAB");
    }

    private record World(PostgresStore store, Ids.TenantId tenant, DirectoryService.Actor admin, TriggerService triggers, TriggerIngest ingest,
                         TriggerRouter router, TriggerSubscriptions subscriptions, TriggerStream stream, MutableClock clock, String instance) {}

    /** device-a in Device Group "finance" runs {@code acme.watch/new-file} for assignment "inbox" (event schema as given). */
    private World world(PostgresStore store, String label, String eventSchema, List<TriggerRouter.Push> push) throws Exception {
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id(label);
        var admin = TestSupport.seed(store, codec, tenant, "root");
        TestSupport.pool(store, codec, tenant, "finance", "device-a");
        TestSupport.pool(store, codec, tenant, "spare", "device-c");
        for (String id : List.of("device-a", "device-c")) {
            store.transaction(tenant, true, tx -> {
                tx.saveEndpoint(new Store.EndpointRecord(id, DirectoryService.digest(label + id), codec.json(new EndpointDeviceRecord(id, tenant.value(), "root",
                        DirectoryService.digest(label + id), EndpointState.ACTIVE, 1L, T0, 1L, null, null, null, null, null, null)), "public", "0".repeat(64), "{}"));
                return null;
            });
        }
        var clock = new MutableClock();
        var endpoints = new EndpointService(store, codec, null, clock, true, tenant.value(), "server", "Organization", "https://control.example.test", "https://gateway.example.test");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var crypto = new RsaFleetCrypto(codec, (RSAPrivateCrtKey) pair.getPrivate(), "signer", List.of(trust("release", (RSAPublicKey) generator.generateKeyPair().getPublic())),
                List.of(trust("signer", (RSAPublicKey) pair.getPublic())), List.of());
        var triggers = new TriggerService(store, codec, crypto, endpoints, clock, true, tenant.value());
        String descriptor = TriggerPackages.clientDescriptor("acme.watch", "new-file").replace("\"maxEventsPerHour\":600", "\"maxEventsPerHour\":3600");
        if (eventSchema != null) {
            var parsed = (ObjectNode) JSON.readTree(descriptor);
            parsed.set("eventSchema", JSON.readTree(eventSchema));
            descriptor = JSON.writeValueAsString(parsed);
        }
        new ServerPackages(store, codec, null, clock).acceptTriggers(true).upload(admin, TriggerPackages.build("acme.watch", "1.0.0", List.of(descriptor)), "request");
        assertEquals(201, triggers.create(admin, """
                {"name":"inbox","spec":{"trigger":"acme.watch/new-file","packageVersion":"1.0.0",
                 "target":{"executor":"CLIENT","deviceGroups":["finance"]},"config":{"folder":"inbox"},"desiredState":"Running"}}""", null, false, "request").status());
        var instance = JSON.readTree(triggers.instances(admin, "inbox", null, null, null).body()).path("items").path(0).path("instanceId").asText();
        var subscriptions = new TriggerSubscriptions(store, codec, triggers, clock, tenant.value());
        var router = new TriggerRouter(store, codec, triggers, clock, tenant.value(), 30).subscriptions(subscriptions);
        push.forEach(router::push);
        var stream = new TriggerStream(store, codec, triggers, subscriptions, router, clock, tenant.value());
        var w = new World(store, tenant, admin, triggers, new TriggerIngest(triggers, codec), router, subscriptions, stream, clock, instance);
        sync(w, List.of());   // lease epoch 1
        return w;
    }

    private EndpointDeviceRecord device(World w, String id) {
        return w.store().transaction(w.tenant(), false, tx -> codec.model(tx.endpoint(id).document(), EndpointDeviceRecord.class));
    }

    private void sync(World w, List<TriggerOutboxRecord> records) {
        var sync = new TriggerSync(0L, null, null, records.isEmpty() ? null : records, 0L);
        w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, "device-a"), sync, "request"));
    }

    private TriggerOutboxRecord fired(World w, long seq, String id, String data) {
        String source = "toolgate:tenant:" + w.tenant().value() + ":trigger-instance:" + w.instance();
        String event = """
                {"specversion":"1.0","id":"%s","source":"%s","type":"examples.files.observed.v1","subject":"inbox/%s","datacontenttype":"application/json","data":%s,
                 "tgleaseepoch":1,"tgsequence":%d,"tgkind":"fired"}""".formatted(id, source, id, data, seq);
        return codec.model("{\"instanceId\":\"" + w.instance() + "\",\"event\":" + event + "}", TriggerOutboxRecord.class);
    }
    private TriggerOutboxRecord fired(World w, long seq, String id) { return fired(w, seq, id, "{\"name\":\"" + id + "\",\"path\":\"inbox/" + id + "\",\"size\":1}"); }

    @Test void workflowTriggerReadinessRequiresCurrentReportsAndLeases() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            java.util.function.BooleanSupplier ready = () -> w.store().transaction(w.tenant(), false,
                tx -> w.triggers().prepared(tx, "inbox", "acme.watch/new-file", "1.0.0", (JsonNode)codec.value("{\"folder\":\"inbox\"}"), List.of("device-a")));
            assertFalse(ready.getAsBoolean(), "desired state and a lease alone do not prove running");
            String report = """
                {"observedGeneration":1,"pending":0,"snapshot":{
                  "snapshotGeneration":1,"snapshotDigest":"sha256:%s","page":1,"pageCount":1,"pageDigest":"sha256:%s","instances":[{
                    "instanceId":"%s","configRevision":1,"phase":"Running",
                    "conditions":[{"type":"Ready","status":"True","reason":"Started","lastTransitionTime":"2023-11-14T22:13:20Z"}],
                    "counters":{"emitted":0,"rejected":0,"dropped":0,"restarts":0},"outbox":{"depth":0,"oldestAgeMs":0}
                  }]}}
                """.formatted("1".repeat(64), "2".repeat(64), w.instance());
            var document = codec.model(report, TriggerSync.class);
            w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, "device-a"), document, "report"));
            assertTrue(ready.getAsBoolean());
            assertFalse((boolean)w.store().transaction(w.tenant(), false, tx -> w.triggers().prepared(tx, "inbox", "acme.watch/new-file", "1.0.0",
                (JsonNode)codec.value("{\"folder\":\"inbox\"}"), List.of("device-c"))), "trigger and package devices must be colocated");
            assertFalse((boolean)w.store().transaction(w.tenant(), false, tx -> w.triggers().prepared(tx, "inbox", "acme.watch/new-file", "1.0.0",
                (JsonNode)codec.value("{\"folder\":\"other\"}"), List.of("device-a"))));
            w.clock().millis += 300001;
            assertFalse(ready.getAsBoolean(), "expired leases/stale reports cannot authorize publication");
            w.clock().millis = T0;
            var current = w.triggers().get(w.admin(), "inbox");
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"other\"}}}", "W/\"" + current.revision() + "\"", null, false, "change");
            assertFalse((boolean)w.store().transaction(w.tenant(), false, tx -> w.triggers().prepared(tx, "inbox", "acme.watch/new-file", "1.0.0",
                (JsonNode)codec.value("{\"folder\":\"other\"}"), List.of("device-a"))), "the old Ready report cannot satisfy new configuration");
            var foreign = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-b");
            TestSupport.seed(w.store(), codec, foreign, "root");
            assertFalse((boolean)w.store().transaction(foreign, false, tx -> w.triggers().prepared(tx, "inbox", "acme.watch/new-file", "1.0.0",
                (JsonNode)codec.value("{\"folder\":\"inbox\"}"), List.of("device-a"))));
        }
    }

    @Test void workflowDevicePlacementIsDurableColocatedAndDoesNotFailOver() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            TestSupport.pool(w.store(), codec, w.tenant(), "finance", "device-a", "device-c");
            String source = """
                {"profileVersion":2,"profileId":"device-profile","packages":[{
                  "packageId":"acme.watch","version":"1.0.0","source":"PREBUILT_CORE","required":true,"deviceGroupIds":["finance"]}]}
                """;
            var profile = codec.model(source, io.ololabs.toolgate.contracts.v2.DeploymentProfile.class);
            var placement = new WorkflowDevicePlacement(w.store(), codec, w.clock());
            w.store().transaction(w.tenant(), true, tx -> {
                var before = tx.load(); var entries = new HashMap<>(before.entries());
                TestSupport.put(entries, codec, Ids.Kind.USER, new ControlUser("reader", "Reader", true, 1L, null, null, null));
                tx.save(before, new Directory(before.revision()+1, entries)); return null;
            });
            var staleRole = new DirectoryService.Actor(w.tenant(), "b".repeat(64), true, true, "reader");
            assertEquals(403, assertThrows(Failure.class, () -> placement.resolve(staleRole, profile)).status(), "cached administrator flags cannot select a device");
            assertNull(w.store().transaction(w.tenant(), false, tx -> tx.workflowProfiles().devicePlacement(profile.profileId())));
            long directoryRevision = w.store().transaction(w.tenant(), false, tx -> tx.load().revision());
            var selected = placement.resolve(w.admin(), profile);
            assertEquals("device-a", selected.deviceId()); assertNull(selected.blocker());
            assertEquals(directoryRevision, (long)w.store().transaction(w.tenant(), false, tx -> tx.load().revision()), "selection never enables/approves devices or changes membership");
            assertEquals(selected, new WorkflowDevicePlacement(w.store(), codec, w.clock()).resolve(w.admin(), profile));
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var raced = codec.model(source.replace("device-profile", "race-profile"), io.ololabs.toolgate.contracts.v2.DeploymentProfile.class);
                var a = executor.submit(() -> placement.resolve(w.admin(), raced));
                var b = executor.submit(() -> placement.resolve(w.admin(), raced));
                assertEquals(a.get(15, java.util.concurrent.TimeUnit.SECONDS), b.get(15, java.util.concurrent.TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
            var colocated = (ObjectNode)codec.value(source.replace("device-profile", "colocated"));
            ((com.fasterxml.jackson.databind.node.ArrayNode)colocated.path("packages")).addObject()
                .put("packageId", "acme.files").put("version", "1.0.0").put("source", "PREBUILT_CORE").put("required", true).putArray("deviceGroupIds").add("spare");
            assertEquals("device-c", placement.resolve(w.admin(), codec.model(codec.json(colocated), io.ololabs.toolgate.contracts.v2.DeploymentProfile.class)).deviceId(), "one device must satisfy every package group set");
            colocated.put("profileId", "optional-device");
            ((ObjectNode)colocated.path("packages").get(1)).put("required", false);
            assertEquals("device-a", placement.resolve(w.admin(), codec.model(codec.json(colocated), io.ololabs.toolgate.contracts.v2.DeploymentProfile.class)).deviceId(), "optional packages must not constrain required placement");
            // Constrain the watch to the selected device and its independently managed group.
            var current = w.triggers().get(w.admin(), "inbox");
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"target\":{\"executor\":\"CLIENT\",\"devices\":[\"device-a\"],\"requiredDeviceGroupSets\":[[\"finance\"]]}}}",
                "W/\"" + current.revision() + "\"", null, false, "constrain");
            sync(w, List.of());
            // A different connected member cannot take over when the selected device is offline.
            w.store().transaction(w.tenant(), true, tx -> {
                var row = tx.endpoint("device-a"); var value = (ObjectNode)codec.value(row.document()); value.put("lastSeenUnixMs", T0 - 180001);
                tx.saveEndpoint(new Store.EndpointRecord(row.id(), row.fingerprint(), codec.json(value), row.csr(), row.reportDigest(), row.acknowledgment())); return null;
            });
            assertEquals("device-a", placement.resolve(w.admin(), profile).deviceId());
            assertTrue(placement.resolve(w.admin(), profile).blocker().contains("check in"));
            TestSupport.pool(w.store(), codec, w.tenant(), "finance", "device-c");
            assertEquals("device-a", placement.resolve(w.admin(), profile).deviceId());
            assertTrue(placement.resolve(w.admin(), profile).blocker().contains("reassignment"));
            var ack = w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, "device-a"), new TriggerSync(0L, null, null, null, 0L), "group-removed"));
            assertNull(ack.leases());
            assertNotNull(ack.revoked(), "group removal fences the old watch on its next existing check-in");
            assertEquals(w.instance(), ack.revoked().getFirst().instanceId());
            var foreign = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-b");
            TestSupport.seed(w.store(), codec, foreign, "root");
            assertNull(w.store().transaction(foreign, false, tx -> tx.workflowProfiles().devicePlacement("device-profile")));
            assertEquals(0, (long)w.store().transaction(w.tenant(), false, tx -> tx.fleet().count(FleetStore.Kind.ROLLOUT)), "selection itself never installs a package");
        }
    }

    @Test void workerPresenceIsBoundedTenantOwnerAndCompilerScopedAndExpires() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            var keys = new TenantVaultKeys(codec, new LocalVaultWrappingProvider("fixture", Map.of("fixture", new byte[32])));
            var profiles = new WorkflowProfiles(w.store(), codec, (tx,p,digest) -> true, keys);
            var leases = new WorkflowLeases(w.store(), codec, w.clock(), profiles, keys);
            var worker = new DirectoryService.Actor(w.tenant(), "b".repeat(64), false);
            String compiler = "sha256:" + "a".repeat(64), graph = "sha256:" + "c".repeat(64);
            java.util.function.IntSupplier ready = () -> w.store().transaction(w.tenant(), false, tx -> tx.workflowRuns().compatibleWorkers(compiler,graph,w.clock().millis()));
            assertEquals(0,ready.getAsInt());
            assertEquals(204,leases.heartbeat(worker,"worker-a",compiler,List.of(graph),"heartbeat").status());
            assertEquals(1,ready.getAsInt());
            assertEquals(204,leases.heartbeat(worker,"worker-a",compiler,List.of(graph),"renew").status());
            assertEquals(1,ready.getAsInt(),"one instance cannot count as two replicas");
            var impostor = new DirectoryService.Actor(w.tenant(), "d".repeat(64), false);
            assertEquals(403,assertThrows(Failure.class,() -> leases.heartbeat(impostor,"worker-a",compiler,List.of(graph),"steal")).status());
            assertEquals(1,ready.getAsInt());
            assertEquals(0,(int)w.store().transaction(w.tenant(),false,tx -> tx.workflowRuns().compatibleWorkers("sha256:"+"f".repeat(64),graph,w.clock().millis())));
            assertEquals(0,(int)w.store().transaction(w.tenant(),false,tx -> tx.workflowRuns().compatibleWorkers(compiler,"sha256:"+"f".repeat(64),w.clock().millis())));
            var other = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant-b");
            TestSupport.seed(w.store(),codec,other,"root");
            assertEquals(0,(int)w.store().transaction(other,false,tx -> tx.workflowRuns().compatibleWorkers(compiler,graph,w.clock().millis())));
            w.clock().millis += 30000;
            assertEquals(0,ready.getAsInt(),"expiry is evaluated during readiness without a cleanup sweep");
            assertEquals(204,leases.heartbeat(worker,"worker-a",compiler,List.of(),"no-graphs").status());
            assertEquals(0,ready.getAsInt());
            w.store().transaction(w.tenant(),true,tx -> {
                for(int i=1;i<256;i++) tx.workflowRuns().reportWorker(new WorkflowRunStore.Worker("worker-"+i,worker.id(),compiler,List.of(graph),w.clock().millis()+30000),w.clock().millis());
                return null;
            });
            assertEquals(409,assertThrows(Failure.class,() -> leases.heartbeat(worker,"overflow",compiler,List.of(graph),"overflow")).status());
            assertEquals(204,leases.heartbeat(worker,"worker-a",compiler,List.of(graph),"existing-at-limit").status());
            assertEquals(256,ready.getAsInt());
            w.clock().millis += 30000;
            assertEquals(204,leases.heartbeat(worker,"replacement",compiler,List.of(graph),"expired-cleanup").status());
            assertEquals(1,ready.getAsInt());
        }
    }

    private long rows(World w, TriggerStore.Kind kind) { return w.store().transaction(w.tenant(), false, tx -> tx.triggers().count(kind)); }

    private JsonNode deliveries(World w, String consumer) throws Exception {
        return JSON.readTree(w.router().deliveries(w.admin(), consumer, null, null, 200).body()).path("items");
    }

    private static int status(Runnable call) {
        try { call.run(); return 200; } catch (Failure failure) { return failure.status(); }
    }

    /** An in-process TEST consumer that answers from a queue of outcomes (Delivered when empty). */
    static final class Probe implements TriggerRouter.Push {
        final String name; final TriggerClassification clearance; final Deque<TriggerRouter.Outcome> answers = new ArrayDeque<>();
        final List<ObjectNode> seen = new ArrayList<>();
        Probe(String name, TriggerClassification clearance) { this.name = name; this.clearance = clearance; }
        public String name() { return name; }
        public TriggerClassification clearance() { return clearance; }
        public boolean matches(TriggerRouter.Routed event) { return true; }
        public TriggerRouter.Outcome deliver(JsonNode delivery, ObjectNode view) { seen.add(view); return answers.isEmpty() ? TriggerRouter.Outcome.delivered() : answers.poll(); }
    }

    // ---- module 10 ----

    @Test
    void routesEachEventOnceInOrderWithRetryDeadLetterAndRedrive() throws Exception {
        for (var backend : stores()) {
            var ok = new Probe("ok", TriggerClassification.INTERNAL);
            var flaky = new Probe("flaky", TriggerClassification.INTERNAL);
            var w = world(backend.store(), "tenant-a", null, List.of(ok, flaky));
            sync(w, List.of(fired(w, 1, "a"), fired(w, 2, "b")));
            assertEquals(2, rows(w, TriggerStore.Kind.OUTBOX), "the outbox row is written with the event");
            assertEquals(2, w.router().route());
            assertEquals(0, w.router().route(), "routed once: the outbox row is gone");
            assertEquals(0, rows(w, TriggerStore.Kind.OUTBOX));
            assertEquals(2, rows(w, TriggerStore.Kind.LOG));
            var routed = deliveries(w, "test:ok");
            assertEquals(List.of("0000000000000001", "0000000000000002"), List.of(routed.get(0).path("cursor").asText(), routed.get(1).path("cursor").asText()), "stream order is the source's order");
            assertEquals("a", routed.get(0).path("eventId").asText());

            for (int i = 0; i < 40; i++) flaky.answers.add(TriggerRouter.Outcome.retry("Busy"));   // both deliveries share the queue
            assertEquals(4, w.router().dispatch());
            assertEquals(List.of("a", "b"), ok.seen.stream().map(v -> v.path("id").asText()).toList());
            assertEquals("Delivered", deliveries(w, "test:ok").get(0).path("state").asText());
            var retry = deliveries(w, "test:flaky").get(0);
            assertEquals("RetryWait", retry.path("state").asText(), backend.name());
            long wait = retry.path("nextAttemptAtUnixMs").asLong() - w.clock().millis;
            assertTrue(wait >= 1 && wait <= 1000, "full jitter, first ceiling 1 s: " + wait);
            assertEquals(0, w.router().dispatch(), "not before the backoff");
            // Ten attempts, then a dead letter; the backoff never exceeds 5 minutes.
            for (int attempt = 2; attempt <= 10; attempt++) {
                w.clock().millis += 300_001;
                w.router().dispatch();
                var current = deliveries(w, "test:flaky").get(0);
                if (attempt < 10) assertTrue(current.path("nextAttemptAtUnixMs").asLong() - w.clock().millis <= 300_000);
            }
            var dead = deliveries(w, "test:flaky").get(0);
            assertEquals("DeadLettered", dead.path("state").asText());
            assertEquals(10, dead.path("attempts").asInt());
            assertEquals(2, JSON.readTree(w.router().deliveries(w.admin(), "test:flaky", "DeadLettered", null, 10).body()).path("items").size(),
                    "dead letters are visible through the API");
            // An admin re-drive starts it over; it is audited and needs trigger.manage.
            flaky.answers.clear();
            assertEquals(409, status(() -> w.router().redrive(w.admin(), routed.get(0).path("deliveryId").asText(), "request")), "only dead letters and skips");
            assertEquals(200, w.router().redrive(w.admin(), dead.path("deliveryId").asText(), "request").status());
            w.router().dispatch();
            assertEquals("Delivered", deliveries(w, "test:flaky").get(0).path("state").asText());
        }
    }

    @Test
    void retentionPurgesInBatchesKeepsTombstonesAndDeadLettersPastTheCeiling() throws Exception {
        for (var backend : stores()) {
            var stuck = new Probe("stuck", TriggerClassification.INTERNAL);
            var w = world(backend.store(), "tenant-a", null, List.of(stuck));
            var settings = "{\"eventRetentionDays\":1,\"defaultClassification\":{\"sdkPackage\":\"INTERNAL\",\"mcpProxy\":\"CONFIDENTIAL\",\"external\":\"CONFIDENTIAL\",\"unknown\":\"RESTRICTED\"}}";
            assertEquals(200, w.router().updateSettings(w.admin(), settings, "request").status());
            assertEquals(400, status(() -> w.router().updateSettings(w.admin(), settings.replace("\"eventRetentionDays\":1", "\"eventRetentionDays\":31"), "request")), "above the operator ceiling");
            assertEquals(400, status(() -> w.router().updateSettings(w.admin(), settings.replace("\"external\":\"CONFIDENTIAL\"", "\"external\":\"INTERNAL\""), "request")), "a default is never lowered");

            sync(w, List.of(fired(w, 1, "a")));
            w.router().route();
            for (int i = 0; i < 20; i++) stuck.answers.add(TriggerRouter.Outcome.retry("Busy"));
            w.router().dispatch();
            // Past retention, but its delivery is not terminal: kept until the hard ceiling (retention + 7 days).
            w.clock().millis += DAY + 1;
            w.router().purge();
            assertEquals(1, rows(w, TriggerStore.Kind.LOG));
            // A late duplicate is still deduplicated.
            sync(w, List.of(fired(w, 2, "a")));
            assertEquals(0, rows(w, TriggerStore.Kind.OUTBOX));
            w.clock().millis = T0 + 8 * DAY + 1;
            w.router().purge();
            assertEquals(0, rows(w, TriggerStore.Kind.LOG), "past the ceiling the event goes");
            assertEquals(0, JSON.readTree(w.triggers().events(w.admin(), "inbox", w.instance(), null, 200).body()).path("items").size());
            var expired = deliveries(w, "test:stuck").get(0);
            assertEquals("DeadLettered", expired.path("state").asText());
            assertEquals("Expired", expired.path("reason").asText());
            // The dedupe key outlived the event by a day and is purged by then: the same id is stored again.
            w.router().purge();
            assertEquals(0, rows(w, TriggerStore.Kind.KEY));
            sync(w, List.of(fired(w, 3, "a")));
            assertEquals(1, rows(w, TriggerStore.Kind.OUTBOX), "a late replay after tombstone expiry is a new event");
            // Terminal deliveries go 30 days after they end.
            w.clock().millis += 31 * DAY;
            w.router().purge();
            assertEquals(0, deliveries(w, "test:stuck").size());
        }
    }

    // ---- module 11 ----

    private static final String CONFIDENTIAL_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["name","path","size"],"properties":{
             "name":{"type":"string"},"path":{"type":"string"},"size":{"type":"integer","x-toolgate-classification":"CONFIDENTIAL"}}}""";

    @Test
    void coarseLabelsRedactBelowClearanceAndFiltersCompileOnlyAgainstTheReadableView() throws Exception {
        for (var backend : stores()) {
            var low = new Probe("low", TriggerClassification.INTERNAL);
            var high = new Probe("high", TriggerClassification.CONFIDENTIAL);
            var w = world(backend.store(), "tenant-a", CONFIDENTIAL_SCHEMA, List.of(low, high));
            sync(w, List.of(fired(w, 1, "a")));
            w.router().route();
            w.router().dispatch();
            var redacted = low.seen.get(0);
            assertEquals("CONFIDENTIAL", redacted.path("tgclassification").asText(), "one label per event type: its highest declared label");
            assertTrue(redacted.path("tgredacted").asBoolean());
            assertFalse(redacted.has("data"), "no data below clearance");
            assertFalse(redacted.has("subject"), "subject may be derived from data");
            assertEquals("a", redacted.path("id").asText(), "the envelope is still delivered");
            assertEquals(1, high.seen.get(0).path("data").path("size").asInt());

            // The INTERNAL-cleared owner (root has no dataClearance) cannot filter on data; the envelope is fine.
            String expires = String.valueOf(T0 + DAY);
            String create = "{\"name\":\"%s\",\"spec\":{\"sources\":{\"assignments\":[\"inbox\"]},\"filter\":%s,\"expiresAtUnixMs\":" + expires + "}}";
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"event.data.size > 0\""), null, "request")));
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"has(event.data)\""), null, "request")), "has() cannot reach it");
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"event['data'] != null\""), null, "request")), "nor indexing");
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"size(event) > 3\""), null, "request")), "nor key counting");
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"[1,2].exists(x, x > 1)\""), null, "request")), "no comprehensions");
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", "\"event.type\""), null, "request")), "boolean results only");
            String deep = JSON.writeValueAsString("(".repeat(40) + "true" + ")".repeat(40));
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), create.formatted("s1", deep), null, "request")), "depth limit");
            assertEquals(201, w.subscriptions().create(w.admin(), create.formatted("s1", "\"event.type == 'examples.files.observed.v1'\""), null, "request").status());
            // Raising the owner's clearance makes data readable.
            clearance(w, "root", ControlDataClearance.CONFIDENTIAL);
            assertEquals(201, w.subscriptions().create(w.admin(), create.formatted("s2", "\"event.data.size > 0\""), null, "request").status());
        }
    }

    private void clearance(World w, String user, ControlDataClearance level) {
        w.store().transaction(w.tenant(), true, tx -> {
            var before = tx.load();
            var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.TEAM, new ControlTeam("cleared-" + user, "Cleared", true, 1L, List.of(user), List.of(), level));
            tx.save(before, new Directory(before.revision() + 1, entries));
            return null;
        });
    }

    // ---- module 12 ----

    private String subscription(World w, DirectoryService.Actor owner, String name, String filter) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"spec\":{\"sources\":{\"assignments\":[\"inbox\"]}," + (filter == null ? "" : "\"filter\":" + JSON.writeValueAsString(filter) + ",")
                + "\"expiresAtUnixMs\":" + (w.clock().millis + 30 * DAY) + "}}";
        return JSON.readTree(w.subscriptions().create(owner, body, null, "request").body()).path("metadata").path("uid").asText();
    }

    private String ticket(World w, DirectoryService.Actor actor, String origin) throws Exception {
        return JSON.readTree(w.stream().ticket(actor, origin).body()).path("ticket").asText();
    }

    private static List<JsonNode> parse(List<String> messages) throws Exception {
        var out = new ArrayList<JsonNode>();
        for (String message : messages) out.add(JSON.readTree(message));
        return out;
    }

    @Test
    void subscribersGetEachEventOnceAckResumeCreditsAndGaps() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            assertEquals(400, status(() -> w.subscriptions().create(w.admin(), "{\"name\":\"far\",\"spec\":{\"sources\":{\"assignments\":[\"inbox\"]},\"expiresAtUnixMs\":" + (T0 + 91 * DAY) + "}}", null, "request")),
                    "at most 90 days");
            subscription(w, w.admin(), "agent", "event.type == 'examples.files.observed.v1'");
            sync(w, List.of(fired(w, 1, "a"), fired(w, 2, "b"), fired(w, 3, "c")));
            w.router().route();
            var session = w.stream().open(ticket(w, w.admin(), null), null);
            assertEquals("subscribed", parse(session.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\"}")).get(0).path("type").asText());
            session.receive("{\"type\":\"credit\",\"n\":1}");
            var sent = parse(session.pump());
            assertEquals(3, sent.size(), "within the default window of 100 (plus 1 credit)");
            assertEquals(List.of("a", "b", "c"), sent.stream().map(m -> m.path("event").path("id").asText()).toList());
            assertEquals(1, sent.get(0).path("event").path("data").path("size").asInt(), "INTERNAL data is readable at the default clearance");
            assertTrue(session.pump().isEmpty(), "sent once per socket");
            session.receive("{\"type\":\"ack\",\"subscription\":\"agent\",\"cursor\":\"" + sent.get(1).path("cursor").asText() + "\"}");
            var states = deliveries(w, JSON.readTree(w.subscriptions().get(w.admin(), "agent").body()).path("metadata").path("uid").asText());
            assertEquals(List.of("Delivered", "Delivered", "InFlight"), List.of(states.get(0).path("state").asText(), states.get(1).path("state").asText(), states.get(2).path("state").asText()));
            assertEquals(sent.get(1).path("cursor").asText(), JSON.readTree(w.subscriptions().get(w.admin(), "agent").body()).path("status").path("ackedCursor").asText());

            // Reconnect: resumes after the acknowledged cursor, so only c comes again (at least once).
            var again = w.stream().open(ticket(w, w.admin(), null), null);
            again.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\"}");
            assertEquals(List.of("c"), parse(again.pump()).stream().map(m -> m.path("event").path("id").asText()).toList());
            // Credit flow control: with no credit nothing is sent, and after 60 s the consumer is closed with 1013.
            var slow = w.stream().open(ticket(w, w.admin(), null), null);
            slow.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\",\"resumeAfter\":\"0000000000000000\"}");
            var window = parse(slow.pump());
            assertEquals(3, window.size());
            sync(w, List.of(fired(w, 4, "d")));
            w.router().route();
            // Exhaust the credit: 100 + 0 granted, 3 used; consume the rest with a credit-free session instead.
            var starved = w.stream().open(ticket(w, w.admin(), null), null);
            starved.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\",\"resumeAfter\":\"0000000000000000\"}");
            for (int i = 0; i < 25; i++) { sync(w, List.of(fired(w, 5 + i, "e" + i))); }
            w.router().route();
            int total = 0;
            for (int i = 0; i < 5; i++) total += starved.pump().size();
            assertEquals(29, total, "every stored event once");
            // Gap: a cursor older than retention is reported, never silently skipped.
            w.router().updateSettings(w.admin(), "{\"eventRetentionDays\":1,\"defaultClassification\":{\"sdkPackage\":\"INTERNAL\",\"mcpProxy\":\"CONFIDENTIAL\",\"external\":\"CONFIDENTIAL\",\"unknown\":\"RESTRICTED\"}}", "request");
            w.clock().millis += 9 * DAY;
            for (int i = 0; i < 3; i++) w.router().purge();
            sync(w, List.of(fired(w, 40, "late")));
            w.router().route();
            var resumed = w.stream().open(ticket(w, w.admin(), null), null);
            var gap = parse(resumed.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\",\"resumeAfter\":\"0000000000000001\"}"));
            assertEquals("gap", gap.get(0).path("type").asText());
            assertEquals("RetentionExpired", gap.get(0).path("reason").asText());
            assertEquals("0000000000000002", gap.get(0).path("fromCursor").asText());
        }
    }

    @Test
    void workflowDispatchResolvesOnlyAnInFlightDeliveryForItsCurrentOwner() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            subscription(w, w.admin(), "agent", "event.type == 'examples.files.observed.v1'");
            String uid = JSON.readTree(w.subscriptions().get(w.admin(), "agent").body()).path("metadata").path("uid").asText();
            sync(w, List.of(fired(w, 1, "a")));
            w.router().route();
            String cursor = "0000000000000001";
            java.util.function.Function<DirectoryService.Actor, TriggerStream.DispatchEvent> resolve = actor ->
                w.store().transaction(w.tenant(), false, tx -> w.stream().dispatchEvent(tx, actor, uid, cursor));
            assertEquals(404, status(() -> resolve.apply(w.admin())), "pending delivery was not sent");

            var session = w.stream().open(ticket(w, w.admin(), null), null);
            session.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\"}");
            var event = parse(session.pump()).getFirst().path("event");
            var target = resolve.apply(w.admin());
            assertEquals(event, target.event());
            assertEquals("a", target.event().path("id").asText());
            assertTrue(target.assignmentUid().startsWith("ta-"));

            var other = new DirectoryService.Actor(w.tenant(), DirectoryService.digest("other"), true, false, "other");
            assertEquals(404, status(() -> resolve.apply(other)));
            session.receive("{\"type\":\"ack\",\"subscription\":\"agent\",\"cursor\":\"" + cursor + "\"}");
            assertEquals(404, status(() -> resolve.apply(w.admin())), "new admission requires an unacknowledged delivery");
        }
    }

    @Test
    void workflowAdmissionUsesLiveServiceGrantsAndReplaysTheDurableReceipt() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            subscription(w, w.admin(), "agent", "event.type == 'examples.files.observed.v1'");
            String uid = JSON.readTree(w.subscriptions().get(w.admin(), "agent").body()).path("metadata").path("uid").asText();
            String assignment = JSON.readTree(w.triggers().get(w.admin(), "inbox").body()).path("metadata").path("uid").asText();
            var path = Path.of(System.getProperty("toolgate.fixtures")).getParent().resolve("../v2/valid.json");
            var config = JSON.readTree(Files.readString(path)).path("WorkflowConfiguration");
            ((ObjectNode) config.path("dispatchBindings").get(0)).put("subscriptionUid", uid)
                .put("sourceAssignmentUid", assignment).put("messagePointer", "/name")
                .put("serviceAgentId", "investigator").put("serviceAgentGroupId", "support-agents");
            var summarizer = config.path("agentDefinitions").get(0).deepCopy();
            ((ObjectNode) summarizer).put("agentId", "summarizer").putArray("contextSources");
            ((com.fasterxml.jackson.databind.node.ArrayNode) config.path("agentDefinitions")).add(summarizer);
            ((com.fasterxml.jackson.databind.node.ArrayNode) config.path("workflows").get(0).path("agents"))
                .addObject().put("agentId", "summarizer").put("agentGroupId", "summary-agents")
                .put("entryEligible", false).put("delegationAllowed", true);
            var profile = JSON.createObjectNode().put("profileVersion", 2).put("profileId", "support");
            profile.putArray("packages");
            profile.set("workflows", config);
            var keys = new TenantVaultKeys(codec, new LocalVaultWrappingProvider("fixture", Map.of("fixture", new byte[32])));
            // This test qualifies admission independently of external model and tool readiness.
            var profiles = new WorkflowProfiles(w.store(), codec, (tx, desired, digest) -> true, keys);
            var staged = JSON.readTree(profiles.upload(w.admin(), profile.toString(), "stage").body());
            String digest = staged.path("profileDigest").asText();
            profiles.review(w.admin(), "support", codec.json(Map.of("profileDigest", digest)), "review");
            var graphPreparation = new WorkflowGraphPreparation(codec, profiles, build ->
                new io.ololabs.toolgate.contracts.v2.WorkflowGraphBuildResult(build.tenantId(), build.profileId(), build.profileDigest(),
                    build.workflowId(), build.expectedGeneration(), "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64), List.of("fixture"), "READY"));
            graphPreparation.prepare(w.admin(), "support", codec.json(Map.of("profileDigest", digest, "expectedGeneration", 0)), "prepare");
            profiles.publish(w.admin(), "support", codec.json(Map.of("profileDigest", digest, "expectedGeneration", 0)), "publish");

            sync(w, List.of(fired(w, 1, "alpha")));
            w.router().route();
            var session = w.stream().open(ticket(w, w.admin(), null), null);
            session.receive("{\"type\":\"subscribe\",\"subscription\":\"agent\"}");
            String cursor = parse(session.pump()).getFirst().path("cursor").asText();
            var service = new WorkflowAdmissions(w.store(), codec, w.clock(), w.stream(), profiles, keys);
            String request = codec.json(Map.of("subscriptionUid", uid, "cursor", cursor));
            var backendDispatch=new WorkflowTriggerDispatch(w.store(),codec,w.stream(),w.subscriptions(),profiles,service);
            assertEquals(0,backendDispatch.tick(w.tenant()),"missing service authority cannot admit or ACK");
            assertEquals(403, status(() -> service.admit(w.admin(), request, "denied")));
            assertEquals(400, status(() -> service.admit(w.admin(), request.replace("}", ",\"workflowId\":\"forged\"}"), "forged")));

            w.store().transaction(w.tenant(), true, tx -> {
                var before = tx.load();
                var entries = new HashMap<>(before.entries());
                TestSupport.put(entries, codec, Ids.Kind.AGENT, new ControlAgent("investigator", "Investigator", null, true, 1L, "root"));
                TestSupport.put(entries, codec, Ids.Kind.AGENT, new ControlAgent("summarizer", "Summarizer", null, true, 1L, "root"));
                TestSupport.put(entries, codec, Ids.Kind.AGENT_GROUP, new ControlAgentGroup("summary-agents", "Summary", null, true,
                    1L, List.of("summarizer"), List.of("workflow-service-role"), null));
                TestSupport.put(entries, codec, Ids.Kind.AGENT_GROUP, new ControlAgentGroup("support-agents", "Support", null, true,
                    1L, List.of("investigator"), List.of("workflow-service-role"), null));
                TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole("workflow-service-role", "Workflow service", true, 1L,
                    UserRole.BASIC, EnterpriseRoleType.ACTOR_SERVICE, List.of()));
                var scope = new EnterpriseScope(new GroupSelection(List.of(), true), new GroupSelection(List.of(), true),
                    List.of("workflow.execute", "model.infer"), false,
                    List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM, "workflow-group:support", EnterpriseResourceMatch.EXACT),
                            new EnterpriseResourceRule(ResourceKind.CUSTOM, "model-group:models", EnterpriseResourceMatch.EXACT)),
                    new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null));
                TestSupport.put(entries, codec, Ids.Kind.GRANT, new ControlAccessGrant("workflow-service-grant", "Workflow service", true,
                    1L, EnterpriseSourceType.ROLE, "workflow-service-role", EnterpriseGrantPurpose.SERVICE, scope));
                TestSupport.put(entries, codec, Ids.Kind.TOOL_GROUP, new ControlToolGroup("workflow-files", "Files", true, 1L, List.of()));
                TestSupport.put(entries, codec, Ids.Kind.DEVICE_GROUP, new ControlDeviceGroup("workflow-devices", "Workflow devices", true, 1L, List.of("device-a")));
                TestSupport.put(entries, codec, Ids.Kind.BINDING, new ControlExecutionBinding("files-binding", "Files", true, 1L,
                    "workflow-files", "workflow-devices", List.of("read"), List.of(), true, false));
                var files = new EnterpriseScope(new GroupSelection(List.of("workflow-files"), false),
                    new GroupSelection(List.of("workflow-devices"), false), List.of("read"), false,
                    List.of(new EnterpriseResourceRule(ResourceKind.CUSTOM, "tool://files/read", EnterpriseResourceMatch.EXACT)), scope.conditions());
                TestSupport.put(entries, codec, Ids.Kind.GRANT, new ControlAccessGrant("file-capability", "File capability", true, 1L,
                    EnterpriseSourceType.AGENT_GROUP, "support-agents", EnterpriseGrantPurpose.CAPABILITY, files));
                TestSupport.put(entries, codec, Ids.Kind.WORKLOAD_BINDING, new ControlWorkloadBinding("files-workload", "Files", true, 1L,
                    "investigator", io.ololabs.toolgate.contracts.v2.RequestMode.SERVICE, "gateway", "files", "gateway",
                    "f".repeat(64), 1L, T0 + DAY, null, null, null, null, false));
                tx.save(before, new Directory(before.revision() + 1, entries));
                return null;
            });
            assertEquals(1,backendDispatch.tick(w.tenant()),"backend dispatch needs no external listener or Admin token");
            assertEquals(0,backendDispatch.tick(w.tenant()),"durable ACK prevents redispatch after a fresh tick");
            var response = service.admit(w.admin(), request, "admit");
            assertEquals(200, response.status());
            var admitted = JSON.readTree(response.body());
            assertEquals("DUPLICATE", admitted.path("disposition").asText());
            var runId = admitted.path("runId").asText();
            w.store().transaction(w.tenant(), false, tx -> {
                var run = tx.workflowRuns().queued(runId);
                assertEquals(runId, tx.workflowRuns().nextQueued("sha256:" + "c".repeat(64), List.of(config.path("workflows").get(0).path("graph").path("digest").asText())).id());
                assertEquals("investigate", run.workflowId());
                assertEquals(digest, run.profileDigest());
                assertEquals("alpha", new WorkflowRunInputs(codec, keys).open(tx, w.tenant(), run));
                return null;
            });
            var duplicate = JSON.readTree(service.admit(w.admin(), request, "duplicate").body());
            assertEquals("DUPLICATE", duplicate.path("disposition").asText());
            assertEquals(admitted.path("admissionId"), duplicate.path("admissionId"));
            assertEquals(admitted.path("runId"), duplicate.path("runId"));

            var manual=new WorkflowManualRuns(w.store(),codec,w.clock(),profiles,keys);
            var manualBody=codec.json(Map.of("profileDigest",digest,"expectedGeneration",1,"entryAgentId","investigator","message","Private manual input"));
            var manualReceipt=JSON.readTree(manual.start(w.admin(),"support","investigate",manualBody,"manual-key","manual-start").body());
            String manualId=manualReceipt.path("runId").asText();
            assertEquals(manualId,JSON.readTree(manual.start(w.admin(),"support","investigate",manualBody,"manual-key","manual-replay").body()).path("runId").asText());
            assertEquals(409,status(()->manual.start(w.admin(),"support","investigate",manualBody.replace("Private manual input","changed"),"manual-key","manual-conflict")));
            assertEquals(409,status(()->manual.start(w.admin(),"support","investigate",manualBody.replace("\"expectedGeneration\":1","\"expectedGeneration\":2"),"stale-key","manual-stale")));
            assertEquals(403,status(()->manual.start(new DirectoryService.Actor(w.tenant(),DirectoryService.digest("not-admin"),false),"support","investigate",manualBody,"forbidden-key","manual-denied")));
            assertEquals(403,status(()->manual.start(w.admin(),"support","investigate",manualBody.replace("investigator","summarizer"),"ineligible-key","manual-ineligible")));
            var foreignWorld=world(w.store(),"tenant-b",null,List.of());
            assertEquals(409,status(()->manual.start(foreignWorld.admin(),"support","investigate",manualBody,"foreign-key","manual-foreign")));
            var progress=new WorkflowProgress(w.store(),codec,w.clock());
            var queued=JSON.readTree(progress.read(w.admin(),manualId,0).body());
            assertEquals("QUEUED",queued.path("items").get(0).path("kind").asText());
            assertEquals("CONTROL",queued.path("items").get(0).path("source").asText());
            assertFalse(queued.toString().contains("Private manual input"));
            assertEquals(404,status(()->progress.read(foreignWorld.admin(),manualId,0)));
            w.store().transaction(w.tenant(),true,tx->{
                assertFalse(tx.workflowRuns().get(manualId).document().contains("Private manual input"));
                assertEquals("Private manual input",new WorkflowRunInputs(codec,keys).open(tx,w.tenant(),tx.workflowRuns().get(manualId)));
                tx.workflowRuns().cancelQueued(manualId);return null;
            });
            var worker = new DirectoryService.Actor(w.tenant(), DirectoryService.digest("runtime-worker"), false);
            var leases = new WorkflowLeases(w.store(), codec, w.clock(), profiles, keys);
            var compiler = "sha256:" + "c".repeat(64);
            var graphs = List.of(config.path("workflows").get(0).path("graph").path("digest").asText());
            assertEquals(204, leases.heartbeat(worker, "worker-a", compiler, graphs, "heartbeat-before-claim").status());
            assertEquals("QUEUED", w.store().transaction(w.tenant(), false, tx -> tx.workflowRuns().state(runId)), "heartbeats must not claim queued work");
            assertEquals(204, leases.claim(worker, "worker-a", "sha256:" + "0".repeat(64), graphs, "wrong-compiler").status());
            assertEquals(204, leases.claim(worker, "worker-a", compiler, List.of("sha256:" + "0".repeat(64)), "wrong-graph").status());
            assertEquals(204, leases.claim(worker, "worker-a", compiler, List.of(), "no-graphs").status());
            assertEquals(400, status(() -> leases.claim(worker, "worker-a", compiler, List.of(graphs.getFirst(), graphs.getFirst()), "duplicate-graphs")));
            assertEquals(400, status(() -> leases.claim(worker, "worker-a", "bad", graphs, "malformed-compiler")));
            w.store().transaction(w.tenant(), false, tx -> {
                assertEquals("QUEUED", tx.workflowRuns().state(runId), "incompatible requests must not consume or fail the run");
                return null;
            });
            var claim = leases.claim(worker, "worker-a", compiler, graphs, "claim");
            assertEquals(200, claim.status());
            var snapshot = JSON.readTree(claim.body());
            assertEquals(runId, snapshot.path("runId").asText());
            assertEquals("alpha", snapshot.path("message").asText());
            assertEquals("investigator", snapshot.path("agents").path(0).path("agentId").asText());
            assertFalse(claim.body().contains("https://models.example"));
            assertEquals(204, leases.claim(worker, "worker-a", compiler, graphs, "claim-again").status());
            var leaseId = snapshot.path("leaseId").asText();
            var token = snapshot.path("leaseToken").asText();
            String progressBody=codec.json(Map.of("leaseId",leaseId,"leaseToken",token,"eventId","node-1","kind","NODE_COMPLETED","nodeId","read"));
            assertEquals(204,progress.report(worker,runId,progressBody,"progress-first").status());
            int progressCount=JSON.readTree(progress.read(w.admin(),runId,0).body()).path("items").size();
            assertEquals(204,progress.report(worker,runId,progressBody,"progress-replay").status());
            assertEquals(progressCount,JSON.readTree(progress.read(w.admin(),runId,0).body()).path("items").size());
            assertEquals(403,status(()->progress.report(worker,runId,progressBody.replace(token,"x".repeat(43)),"progress-forged")));
            assertEquals(403,status(()->progress.read(worker,runId,0)));
            assertEquals(400,status(()->progress.report(worker,runId,progressBody.replace("}",",\"message\":\"private\"}"),"progress-private")));
            assertFalse(progress.read(w.admin(),runId,0).body().contains(token));
            // Publishing B changes new admissions, while the leased A run keeps its exact configuration.
            var replacement = profile.deepCopy();
            ((ObjectNode)replacement.path("workflows").path("workflows").get(0))
                .put("versionId", "revision-2").put("systemPrompt", "Replacement supervisor");
            ((ObjectNode)replacement.path("workflows").path("dispatchBindings").get(0)).put("workflowVersionId", "revision-2");
            ((ObjectNode)replacement.path("workflows").path("modelDeployments").get(0)).put("modelId", "replacement-model");
            String nextDigest = JSON.readTree(profiles.upload(w.admin(), replacement.toString(), "next-stage").body()).path("profileDigest").asText();
            profiles.review(w.admin(), "support", codec.json(Map.of("profileDigest", nextDigest)), "next-review");
            graphPreparation.prepare(w.admin(), "support", codec.json(Map.of("profileDigest", nextDigest, "expectedGeneration", 1)), "next-prepare");
            profiles.publish(w.admin(), "support", codec.json(Map.of("profileDigest", nextDigest, "expectedGeneration", 1)), "next-publish");
            var replayAfterSwap = JSON.readTree(service.admit(w.admin(), request, "duplicate-after-swap").body());
            assertEquals(runId, replayAfterSwap.path("runId").asText());
            assertEquals("DUPLICATE", replayAfterSwap.path("disposition").asText());
            assertEquals(403, status(() -> leases.authorizeStep(worker, runId, leaseId, token, "WORKFLOW", "other", "models")));
            assertEquals(403, status(() -> leases.authorizeStep(worker, runId, leaseId, token, "WORKFLOW", "investigate", "other")));
            assertEquals(403, status(() -> leases.authorizeStep(worker, runId, leaseId, token, "AGENT", "other", "models")));
            var summaryGrant = JSON.readTree(leases.authorizeStep(worker, runId, leaseId, token, "AGENT", "summarizer", "models").body());
            w.store().transaction(w.tenant(), false, tx -> {
                var models = new WorkflowModelSelection(profiles, codec, w.clock());
                assertEquals("deployment-1", models.resolve(tx, w.tenant(), runId, summaryGrant.path("token").asText()).id());
                assertEquals(config.path("modelDeployments").get(0).path("modelId").asText(),
                    models.resolve(tx, w.tenant(), runId, summaryGrant.path("token").asText()).modelId());
                assertEquals(403, status(() -> new WorkflowToolAuthority(codec, models, w.clock()).resolve(tx, w.tenant(), runId,
                    summaryGrant.path("token").asText(), "files/read", "summary-no-tools")));
                return null;
            });
            w.store().transaction(w.tenant(), true, tx -> {
                var before = tx.load(); var entries = new HashMap<>(before.entries());
                TestSupport.put(entries, codec, Ids.Kind.AGENT_GROUP, new ControlAgentGroup("summary-agents", "Summary", null, false,
                    2L, List.of("summarizer"), List.of("workflow-service-role"), null));
                tx.save(before, new Directory(before.revision() + 1, entries));
                return null;
            });
            assertEquals(403, status(() -> leases.authorizeStep(worker, runId, leaseId, token, "AGENT", "summarizer", "models")));
            w.store().transaction(w.tenant(), false, tx -> {
                assertEquals(403, status(() -> new WorkflowModelSelection(profiles, codec, w.clock()).resolve(tx, w.tenant(), runId,
                    summaryGrant.path("token").asText())));
                return null;
            });
            var grant = JSON.readTree(leases.authorizeStep(worker, runId, leaseId, token, "WORKFLOW", "investigate", "models").body());
            assertEquals("investigate", grant.path("subjectId").asText());
            var agentGrant = JSON.readTree(leases.authorizeStep(worker, runId, leaseId, token, "AGENT", "investigator", "models").body());
            assertEquals("investigator", agentGrant.path("subjectId").asText());
            w.store().transaction(w.tenant(), false, tx -> {
                assertEquals(DirectoryService.digest(agentGrant.path("token").asText()), tx.workflowRuns().activeStep(runId).tokenDigest());
                assertFalse(grant.path("token").asText().equals(agentGrant.path("token").asText()));
                var models = new WorkflowModelSelection(profiles, codec, w.clock());
                assertEquals("deployment-1", models.resolve(tx, w.tenant(), runId, agentGrant.path("token").asText()).id());
                var toolContext = new WorkflowToolAuthority(codec, models, w.clock()).resolve(tx, w.tenant(), runId,
                    agentGrant.path("token").asText(), null, "file-tools");
                assertEquals("investigator", toolContext.context().agentId());
                assertEquals("files-workload", toolContext.context().workloadBindingId());
                assertEquals(403, status(() -> models.resolve(tx, w.tenant(), runId, grant.path("token").asText())));
                return null;
            });
            var result = JSON.createObjectNode().put("issueId", "issue-123").put("summary", "Timeout investigated");
            assertEquals(403, status(() -> leases.finish(w.admin(), runId, leaseId, token, "SUCCEEDED", result, null, "foreign-finish")));
            assertEquals(403, status(() -> leases.finish(worker, runId, leaseId, "x".repeat(43), "SUCCEEDED", result, null, "forged-finish")));
            assertEquals(400, status(() -> leases.finish(worker, runId, leaseId, token, "SUCCEEDED", result, "DENIED", "bad-result")));
            assertEquals(200, leases.finish(worker, runId, leaseId, token, "SUCCEEDED", result, null, "finish").status());
            assertEquals(403,status(()->progress.report(worker,runId,progressBody,"progress-after-finish")));
            var saved = JSON.readTree(leases.result(w.admin(), runId).body());
            assertEquals(result, saved.path("outcome").path("result"));
            assertEquals("alpha", saved.path("message").asText());
            assertEquals("SUCCEEDED", saved.path("state").asText());
            assertEquals(403, status(() -> leases.result(worker, runId)));
            assertEquals(403, status(() -> leases.authorizeStep(worker, runId, leaseId, token, "WORKFLOW", "investigate", "models")));
            assertEquals(403, status(() -> leases.finish(worker, runId, leaseId, token, "SUCCEEDED", result, null, "repeat-finish")));
            sync(w, List.of(fired(w, 2, "beta")));
            w.router().route();
            String nextCursor = parse(session.pump()).getLast().path("cursor").asText();
            var nextAdmission = JSON.readTree(service.admit(w.admin(), codec.json(Map.of("subscriptionUid", uid, "cursor", nextCursor)), "admit-next").body());
            var nextSnapshot = JSON.readTree(leases.claim(worker, "worker-a", compiler, graphs, "claim-next").body());
            assertEquals(nextAdmission.path("runId"), nextSnapshot.path("runId"));
            assertEquals(nextDigest, nextSnapshot.path("profileDigest").asText());
            assertEquals("revision-2", nextSnapshot.path("workflowVersionId").asText());
            assertEquals("Replacement supervisor", nextSnapshot.path("supervisorPrompt").asText());
            var nextGrant = JSON.readTree(leases.authorizeStep(worker, nextSnapshot.path("runId").asText(),
                nextSnapshot.path("leaseId").asText(), nextSnapshot.path("leaseToken").asText(), "AGENT", "investigator", "models").body());
            w.store().transaction(w.tenant(), false, tx -> {
                assertEquals("replacement-model", new WorkflowModelSelection(profiles, codec, w.clock()).resolve(tx, w.tenant(),
                    nextSnapshot.path("runId").asText(), nextGrant.path("token").asText()).modelId());
                return null;
            });
        }
    }

    /** M12 scenario 16: removing the subscriber's grant sends revoked and closes; a replayed ticket is refused. */
    @Test
    void scenario16RevokedGrantClosesTheSocketAndTicketsAreSingleUse() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", null, List.of());
            subscriber(w, "alice", true);
            var alice = new DirectoryService(w.store(), codec, 512, 1048576).portalActor(w.tenant(), "c".repeat(64), "alice");
            subscription(w, alice, "alice-feed", null);
            String ticket = ticket(w, alice, "https://console.example.test");
            assertEquals(401, status(() -> w.stream().open(ticket, null)), "bound to its origin; a failed attempt also uses it");
            String fresh = ticket(w, alice, "https://console.example.test");
            var session = w.stream().open(fresh, "https://console.example.test");
            assertEquals(401, status(() -> w.stream().open(fresh, "https://console.example.test")), "a ticket replayed after use is refused");
            String late = ticket(w, alice, null);
            w.clock().millis += 30_001;
            assertEquals(401, status(() -> w.stream().open(late, null)), "valid for 30 s");
            String epoch = ticket(w, alice, null);
            subscriber(w, "bob", true);   // any directory change before use
            assertEquals(401, status(() -> w.stream().open(epoch, null)), "bound to the authorization epoch at issue");

            session.receive("{\"type\":\"subscribe\",\"subscription\":\"alice-feed\"}");
            sync(w, List.of(fired(w, 1, "a")));
            w.router().route();
            assertEquals(1, session.pump().size());
            subscriber(w, "alice", false);
            var revoked = parse(session.pump());
            assertEquals("revoked", revoked.get(0).path("type").asText());
            assertEquals("GrantRevoked", revoked.get(0).path("reason").asText());
            assertEquals(1008, session.closeCode());
            assertEquals("Suspended", JSON.readTree(w.subscriptions().get(alice, "alice-feed").body()).path("status").path("phase").asText());
        }
    }

    /** M12 scenario 31: ticket values never reach audit or storage; classified filters are refused; replay skips lost sources. */
    @Test
    void scenario31SocketHygiene() throws Exception {
        for (var backend : stores()) {
            var w = world(backend.store(), "tenant-a", CONFIDENTIAL_SCHEMA, List.of());
            subscriber(w, "alice", true);
            var alice = new DirectoryService(w.store(), codec, 512, 1048576).portalActor(w.tenant(), "c".repeat(64), "alice");
            String create = "{\"name\":\"peek\",\"spec\":{\"sources\":{\"assignments\":[\"inbox\"]},\"filter\":\"event.data.size > 100\",\"expiresAtUnixMs\":" + (T0 + DAY) + "}}";
            assertEquals(400, status(() -> w.subscriptions().create(alice, create, null, "request")), "a filter on a classified field, below clearance");
            subscription(w, alice, "feed", null);
            var tickets = new ArrayList<String>();
            for (int i = 0; i < 3; i++) tickets.add(ticket(w, alice, null));
            var session = w.stream().open(tickets.get(0), null);
            if (backend.sqlite() != null) {
                try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + backend.sqlite()); var statement = connection.createStatement()) {
                    var dump = new StringBuilder();
                    for (String table : List.of("control_audit", "control_triggers")) {
                        try (var rows = statement.executeQuery("SELECT * FROM " + table)) {
                            int columns = rows.getMetaData().getColumnCount();
                            while (rows.next()) for (int c = 1; c <= columns; c++) dump.append(rows.getString(c)).append('\n');
                        }
                    }
                    for (String ticket : tickets) assertFalse(dump.toString().contains(ticket), "a ticket value is stored or audited");
                }
            }
            session.receive("{\"type\":\"subscribe\",\"subscription\":\"feed\"}");
            sync(w, List.of(fired(w, 1, "a")));
            w.router().route();
            var first = parse(session.pump());
            assertTrue(first.get(0).path("event").path("tgredacted").asBoolean(), "delivered as the owner's readable view");
            assertFalse(first.get(0).path("event").has("data"));
            // device-a leaves the owner's Device Group: replay of its stored events is skipped, never sent on old authority.
            TestSupport.pool(w.store(), codec, w.tenant(), "spare", "device-a", "device-c");
            TestSupport.pool(w.store(), codec, w.tenant(), "finance");
            subscriber(w, "alice", true);
            var replay = w.stream().open(ticket(w, alice, null), null);
            replay.receive("{\"type\":\"subscribe\",\"subscription\":\"feed\",\"resumeAfter\":\"0000000000000000\"}");
            assertTrue(replay.pump().isEmpty(), "the now-unauthorized event is skipped");
            var uid = JSON.readTree(w.subscriptions().get(alice, "feed").body()).path("metadata").path("uid").asText();
            assertEquals("Skipped", deliveries(w, uid).get(0).path("state").asText());
            assertEquals("Unauthorized", deliveries(w, uid).get(0).path("reason").asText());
        }
    }

    /** A user whose management rule grants trigger.read and trigger.subscribe on Device Group "finance" (or nothing). */
    private void subscriber(World w, String user, boolean granted) {
        w.store().transaction(w.tenant(), true, tx -> {
            var before = tx.load();
            var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.USER, new ControlUser(user, "Subscriber " + user, true, 1L, null, null, null));
            GroupGraph.addDefault(entries, codec, Ids.Kind.USER, user);
            var none = new GroupSelection(List.of(), false);
            var only = new GroupSelection(List.of("finance"), false);
            var conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
            var ceiling = new EnterpriseScope(none, only, List.of(), false, List.of(), conditions);
            var rule = new EnterpriseManagementRule(List.of("trigger.read", "trigger.subscribe"), EnterpriseGroupType.DEVICE_GROUP, only, List.of(ceiling), conditions, null);
            String role = "subscriber-" + user;
            TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole(role, "Subscriber", true, 1L, UserRole.ADMINISTRATOR, EnterpriseRoleType.MANAGEMENT, List.of(rule)));
            TestSupport.put(entries, codec, Ids.Kind.TEAM, new ControlTeam("subscribers-" + user, "Subscribers", true, 1L, granted ? List.of(user) : List.of(), List.of(role), null));
            tx.save(before, new Directory(before.revision() + 1, entries));
            return null;
        });
    }

    @Test
    void labelsAreComputedFromSignedSchemasAndClearancesFromGroups() {
        var settings = JSON.createObjectNode();
        assertEquals(TriggerClassification.INTERNAL, TriggerLabels.eventLabel(JSON.createObjectNode().set("eventSchema", JSON.createObjectNode()), settings));
        var external = JSON.createObjectNode();
        external.putArray("routes").add("mail");
        assertEquals(TriggerClassification.CONFIDENTIAL, TriggerLabels.eventLabel(external, settings), "external integrations fail closed");
        var nested = JSON.createObjectNode();
        nested.putObject("eventSchema").putObject("properties").putObject("x").putArray("oneOf").addObject().put("x-toolgate-classification", "RESTRICTED");
        assertEquals(TriggerClassification.RESTRICTED, TriggerLabels.eventLabel(nested, settings), "every branch counts");
        var unknown = JSON.createObjectNode();
        unknown.putObject("eventSchema").put("x-toolgate-classification", "SECRET");
        assertEquals(TriggerClassification.RESTRICTED, TriggerLabels.eventLabel(unknown, settings), "an unknown label fails closed");
        assertNotNull(TriggerFilters.compile("event.source.startsWith('toolgate:')", false));
        assertThrows(Failure.class, () -> TriggerFilters.compile("event.data.x == 1", false));
    }
}
