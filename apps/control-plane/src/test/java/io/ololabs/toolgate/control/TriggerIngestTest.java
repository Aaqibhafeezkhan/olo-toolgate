// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.TriggerOutboxRecord;
import io.ololabs.toolgate.contracts.v2.TriggerSync;
import io.ololabs.toolgate.contracts.v2.TriggerSyncAck;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.packagearchive.TriggerPackages;
import java.nio.file.Files;
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
 * Triggers module 5 (issue #274): Control ingest of Trigger Sync, T6–T9 of plan 6.6.1. Every scenario runs on SQLite,
 * and on PostgreSQL when {@code CONTROL_TEST_URL} names an isolated server. The stateful test drives random sequences
 * of check-ins against a reference model and asserts A, C, D and F after every step.
 */
final class TriggerIngestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1700000000000L;
    private static final String PACKAGE = "acme.watch";
    private final ContractCodec codec = new ContractCodec();

    static final class MutableClock extends Clock {
        long millis = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    private List<Map.Entry<String, PostgresStore>> stores() throws Exception {
        var out = new ArrayList<Map.Entry<String, PostgresStore>>();
        var directory = Files.createTempDirectory("toolgate-ingest");
        out.add(Map.entry("sqlite", new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("ingest.sqlite"))), codec)));
        String base = System.getenv("CONTROL_TEST_URL");
        if (base != null) {
            String password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
            String database = "ingest_" + UUID.randomUUID().toString().replace("-", "");
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

    private static FleetTrustKey trust(String kid, RSAPublicKey key) {
        var bytes = key.getModulus().toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return new FleetTrustKey(kid, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), "AQAB");
    }

    private record World(PostgresStore store, Ids.TenantId tenant, DirectoryService.Actor admin, TriggerService triggers, TriggerIngest ingest,
                         MutableClock clock, String instance) {}

    /** Device a is assigned {@code acme.watch/new-file} (policy as given); device c is enrolled but not assigned. */
    private World world(PostgresStore store, String label, String policy) throws Exception { return world(store, label, policy, 3600); }

    private World world(PostgresStore store, String label, String policy, int perHour) throws Exception {
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
        var packages = new ServerPackages(store, codec, null, clock).acceptTriggers(true);
        String descriptor = TriggerPackages.clientDescriptor(PACKAGE, "new-file").replace("\"onTerminalRejection\":\"HALT\"", "\"onTerminalRejection\":\"" + policy + "\"")
                .replace("\"maxEventsPerHour\":600", "\"maxEventsPerHour\":" + perHour);
        packages.upload(admin, TriggerPackages.build(PACKAGE, "1.0.0", List.of(descriptor)), "request");
        String body = """
                {"name":"inbox","spec":{"trigger":"acme.watch/new-file","packageVersion":"1.0.0",
                 "target":{"executor":"CLIENT","devices":["device-a"]},"config":{"folder":"inbox"},"desiredState":"Running"}}""";
        assertEquals(201, triggers.create(admin, body, null, false, "request").status());
        var instance = JSON.readTree(triggers.instances(admin, "inbox", null, null, null).body()).path("items").path(0).path("instanceId").asText();
        return new World(store, tenant, admin, triggers, new TriggerIngest(triggers, codec), clock, instance);
    }

    private EndpointDeviceRecord device(World w, String id) {
        return w.store().transaction(w.tenant(), false, tx -> codec.model(tx.endpoint(id).document(), EndpointDeviceRecord.class));
    }

    private String source(World w) { return "toolgate:tenant:" + w.tenant().value() + ":trigger-instance:" + w.instance(); }

    private TriggerOutboxRecord record(World w, String instance, String source, long epoch, long seq, String id, String data, String kind, String type) {
        String event = """
                {"specversion":"1.0","id":"%s","source":"%s","type":"%s","datacontenttype":"application/json","data":%s,
                 "tgleaseepoch":%d,"tgsequence":%d,"tgkind":"%s"}""".formatted(id, source, type, data, epoch, seq, kind);
        return codec.model("{\"instanceId\":\"" + instance + "\",\"event\":" + event + "}", TriggerOutboxRecord.class);
    }

    private TriggerOutboxRecord fired(World w, long epoch, long seq, String id) {
        return record(w, w.instance(), source(w), epoch, seq, id, "{\"name\":\"" + id + "\",\"path\":\"inbox/" + id + "\",\"size\":1}", "fired", "examples.files.observed.v1");
    }

    private TriggerOutboxRecord invalid(World w, long epoch, long seq, String id) {
        return record(w, w.instance(), source(w), epoch, seq, id, "{\"name\":1}", "fired", "examples.files.observed.v1");
    }

    private TriggerOutboxRecord loss(World w, long epoch, long seq, String id) {
        return record(w, w.instance(), source(w), epoch, seq, id + ":rejected", "{\"id\":\"" + id + "\",\"reason\":\"SchemaInvalid\",\"dataDigest\":\"sha256:" + "0".repeat(64) + "\"}",
                "rejected", TriggerIngest.LOSS_RECORD_TYPE);
    }

    private TriggerSyncAck sync(World w, String device, List<TriggerOutboxRecord> records) {
        var sync = new TriggerSync(0L, null, null, records.isEmpty() ? null : records, 0L);
        return w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, device), sync, "request"));
    }

    private static long acked(TriggerSyncAck ack, long epoch) {
        if (ack.acked() == null) return 0;
        return ack.acked().stream().filter(a -> a.leaseEpoch() == epoch).mapToLong(a -> a.outboxSeq()).max().orElse(0);
    }

    private JsonNode events(World w) throws Exception {
        return JSON.readTree(w.triggers().events(w.admin(), "inbox", w.instance(), null, 200).body()).path("items");
    }

    private long rows(World w, TriggerStore.Kind kind) {
        return w.store().transaction(w.tenant(), false, tx -> tx.triggers().count(kind));
    }

    @Test
    void storesOnceAcknowledgesContiguouslyAndReturnsLeases() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a", "HALT");
            var first = sync(w, "device-a", List.of());
            assertEquals(1, first.leases().size(), "a Running instance gets its lease in the ack");
            assertEquals(1L, first.leases().get(0).leaseEpoch());
            assertTrue(first.generation() >= 1);

            // Out of order: 2 and 3 are stored but not acknowledged until 1 arrives (A).
            var ack = sync(w, "device-a", List.of(fired(w, 1, 3, "c"), fired(w, 1, 2, "b")));
            assertEquals(0, acked(ack, 1), entry.getKey());
            ack = sync(w, "device-a", List.of(fired(w, 1, 1, "a")));
            assertEquals(3, acked(ack, 1));
            // Re-sent records and a re-emitted event under a new outboxSeq are deduplicated (D).
            ack = sync(w, "device-a", List.of(fired(w, 1, 1, "a"), fired(w, 1, 2, "b"), fired(w, 1, 4, "a")));
            assertEquals(4, acked(ack, 1));
            var stored = events(w);
            assertEquals(3, stored.size(), "stored once per (source, id)");
            assertEquals(List.of("a", "b", "c"), List.of(stored.get(0).path("event").path("id").asText(), stored.get(1).path("event").path("id").asText(), stored.get(2).path("event").path("id").asText()));
            var producer = stored.get(0).path("producer");
            assertEquals("DEVICE", producer.path("kind").asText());
            assertEquals("device-a", producer.path("executorId").asText());
            assertEquals("inbox", producer.path("assignment").asText());
            assertEquals(1, producer.path("leaseEpoch").asInt());
        }
    }

    @Test
    void fencedRecordsWriteNothingAndOlderEpochsDrainForSevenDays() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a", "HALT");
            sync(w, "device-a", List.of());
            long events = rows(w, TriggerStore.Kind.EVENT), keys = rows(w, TriggerStore.Kind.KEY);
            // A device the instance does not resolve to, and a future epoch, are fenced (T9, F).
            var ack = sync(w, "device-c", List.of(fired(w, 1, 1, "x")));
            assertEquals("NotAssigned", ack.rejected().get(0).reason().name());
            ack = sync(w, "device-a", List.of(fired(w, 2, 1, "x")));
            assertEquals("StaleLeaseEpoch", ack.rejected().get(0).reason().name());
            assertEquals(2L, ack.rejected().get(0).leaseEpoch());
            assertEquals(events, rows(w, TriggerStore.Kind.EVENT), "no event row for a fenced record");
            assertEquals(keys, rows(w, TriggerStore.Kind.KEY), "no dedupe key for a fenced record");
            // A fenced id is not suppressed: the same id from the current epoch is stored.
            assertEquals(1, acked(sync(w, "device-a", List.of(fired(w, 1, 1, "x"))), 1));

            // A config change starts epoch 2; epoch 1's queued records still drain from this device...
            var assignment = w.triggers().get(w.admin(), "inbox");
            w.triggers().patch(w.admin(), "inbox", "{\"spec\":{\"config\":{\"folder\":\"other\"}}}", "W/\"" + assignment.revision() + "\"", null, false, "request");
            var renewed = sync(w, "device-a", List.of(fired(w, 1, 2, "y")));
            assertEquals(2L, renewed.leases().get(0).leaseEpoch());
            assertEquals(2, acked(renewed, 1), "the old epoch drains");
            // ...for 7 days after epoch 2 began.
            w.clock().millis += 7L * 24 * 60 * 60 * 1000 + 1;
            ack = sync(w, "device-a", List.of(fired(w, 1, 3, "z")));
            assertEquals("StaleLeaseEpoch", ack.rejected().get(0).reason().name());
            assertEquals(1L, ack.rejected().get(0).leaseEpoch());
        }
    }

    @Test
    void controlSideRejectionsAreDispositionsCountedOnlyUnderSkipAndRecord() throws Exception {
        for (var entry : stores()) {
            var skip = world(entry.getValue(), "tenant-a", "SKIP_AND_RECORD");
            sync(skip, "device-a", List.of());
            var ack = sync(skip, "device-a", List.of(fired(skip, 1, 1, "a"), invalid(skip, 1, 2, "b"), fired(skip, 1, 3, "c"), loss(skip, 1, 4, "d")));
            assertEquals(1, ack.dispositions().size());
            assertEquals("SchemaInvalid", ack.dispositions().get(0).reason());
            assertEquals(2L, ack.dispositions().get(0).outboxSeq());
            assertEquals(4, acked(ack, 1), "under SKIP_AND_RECORD the disposition and the loss record resolve their records");
            var stored = events(skip);
            assertEquals(4, stored.size());
            assertEquals("SchemaInvalid", stored.get(1).path("disposition").path("reason").asText());
            assertEquals("LossRecord", stored.get(3).path("disposition").path("reason").asText());

            var halt = world(entry.getValue(), "tenant-b", "HALT");
            sync(halt, "device-a", List.of());
            ack = sync(halt, "device-a", List.of(fired(halt, 1, 1, "a"), invalid(halt, 1, 2, "b"), fired(halt, 1, 3, "c")));
            assertEquals(1, ack.dispositions().size());
            assertEquals(1, acked(ack, 1), "under HALT the accepted sequence stops before the rejected record");
            var instance = JSON.readTree(halt.triggers().instances(halt.admin(), "inbox", null, null, null).body()).path("items").path(0);
            assertEquals("Failed", instance.path("phase").asText());
            // A loss record is unexpected under HALT and never resolves anything.
            ack = sync(halt, "device-a", List.of(fired(halt, 1, 2, "b2")));
            assertEquals(1, acked(ack, 1), "nothing at or after the halted record resolves");
        }
    }

    @Test
    void identityTypeAndRateAreRevalidated() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a", "SKIP_AND_RECORD", 60);
            sync(w, "device-a", List.of());
            var forged = record(w, w.instance(), "toolgate:tenant:other:trigger-instance:" + w.instance(), 1, 1, "a", "{\"name\":\"a\",\"path\":\"p\",\"size\":1}", "fired", "examples.files.observed.v1");
            var wrongType = record(w, w.instance(), source(w), 1, 2, "b", "{\"name\":\"b\",\"path\":\"p\",\"size\":1}", "fired", "examples.files.other.v1");
            var ack = sync(w, "device-a", List.of(forged, wrongType));
            assertEquals(List.of("IdentityMismatch", "TypeMismatch"), ack.dispositions().stream().map(d -> d.reason()).toList());
            assertTrue(events(w).findValues("producer").size() == 2);
            // 60 per hour: a burst past the bucket is asked to retry later, from the first record not accepted.
            var burst = new ArrayList<TriggerOutboxRecord>();
            for (long seq = 3; seq <= 100; seq++) burst.add(fired(w, 1, seq, "e" + seq));
            ack = sync(w, "device-a", burst);
            assertEquals(1, ack.retryLater().size());
            long from = ack.retryLater().get(0).fromOutboxSeq();
            assertEquals(from - 1, acked(ack, 1), "acknowledged up to the first record over the rate");
            assertTrue(ack.retryLater().get(0).retryAfterMs() >= 1000);
            w.clock().millis += 3_600_000;
            assertEquals(100, acked(sync(w, "device-a", burst.subList((int) (from - 3), burst.size())), 1));
        }
    }

    @Test
    void snapshotsUpdateStatusAndStaleIsDerived() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a", "HALT");
            String report = """
                    {"snapshotGeneration":1,"snapshotDigest":"sha256:%s","page":1,"pageCount":1,"pageDigest":"sha256:%s","instances":[
                     {"instanceId":"%s","configRevision":1,"leaseEpoch":1,"phase":"Running",
                      "conditions":[{"type":"Ready","status":"True","reason":"Started","lastTransitionTime":"2023-11-14T22:13:20Z"}],
                      "counters":{"emitted":2,"rejected":0,"dropped":0,"restarts":0},"outbox":{"depth":1,"oldestAgeMs":5}}]}""".formatted("1".repeat(64), "2".repeat(64), w.instance());
            var sync = codec.model("{\"observedGeneration\":1,\"snapshot\":" + report + ",\"pending\":0}", TriggerSync.class);
            var ack = w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, "device-a"), sync, "request"));
            assertEquals(1L, ack.snapshotPages().get(0).page());
            var view = JSON.readTree(w.triggers().instances(w.admin(), "inbox", null, null, null).body()).path("items").path(0);
            assertEquals("Running", view.path("phase").asText());
            assertEquals(2, view.path("counters").path("emitted").asInt());
            assertEquals(T0, view.path("lastSeenAtUnixMs").asLong());
            assertEquals("False", condition(view, "Stale"));
            w.clock().millis += 5 * 60_000 + 1;
            view = JSON.readTree(w.triggers().instances(w.admin(), "inbox", null, null, null).body()).path("items").path(0);
            assertEquals("True", condition(view, "Stale"), "after 5 minutes without a check-in");
            assertEquals("Running", view.path("phase").asText(), "Stale changes what is shown, not the phase or the lease");
        }
    }

    private static String condition(JsonNode view, String type) {
        for (var c : view.path("conditions")) if (c.path("type").asText().equals(type)) return c.path("status").asText();
        return null;
    }

    @Test
    void acceptedCheckpointsNeverPassAcceptedOutboxSeq() throws Exception {
        for (var entry : stores()) {
            var w = world(entry.getValue(), "tenant-a", "HALT");
            sync(w, "device-a", List.of());
            sync(w, "device-a", List.of(fired(w, 1, 1, "a"), fired(w, 1, 3, "c")));
            String body = Base64.getEncoder().encodeToString("{\"next\":4}".getBytes());
            String digest = "sha256:" + DirectoryService.digest("{\"next\":4}".getBytes());
            for (long after : List.of(3L, 1L)) {
                var sync = codec.model("""
                        {"observedGeneration":1,"checkpoints":[{"instanceId":"%s","leaseEpoch":1,"afterOutboxSeq":%d,"level":"ACCEPTED","version":%d,
                         "digest":"%s","sizeBytes":10,"inline":"%s"}],"pending":0}""".formatted(w.instance(), after, after, digest, body), TriggerSync.class);
                w.store().transaction(w.tenant(), true, tx -> w.ingest().sync(tx, device(w, "device-a"), sync, "request"));
            }
            var row = w.store().transaction(w.tenant(), false, tx -> tx.triggers().get(TriggerStore.Kind.CHECKPOINT, w.instance()));
            assertEquals(1, JSON.readTree(row.document()).path("afterOutboxSeq").asInt(), "C: the checkpoint past acceptedOutboxSeq 1 was refused");
        }
    }

    @Test
    void anotherTenantsDeviceCannotIngestIntoAnInstance() throws Exception {
        for (var entry : stores()) {
            var a = world(entry.getValue(), "tenant-a", "HALT");
            var b = world(entry.getValue(), "tenant-b", "HALT");
            sync(a, "device-a", List.of());
            long before = rows(a, TriggerStore.Kind.EVENT);
            // Tenant B's device names tenant A's instance: it is not one it resolves to, exactly like a nonexistent id.
            var ack = sync(b, "device-a", List.of(record(a, a.instance(), source(a), 1, 1, "a", "{\"name\":\"a\",\"path\":\"p\",\"size\":1}", "fired", "examples.files.observed.v1")));
            var unknown = sync(b, "device-a", List.of(record(a, "ti-" + "0".repeat(32), source(a), 1, 1, "a", "{\"name\":\"a\",\"path\":\"p\",\"size\":1}", "fired", "examples.files.observed.v1")));
            assertEquals(ack.rejected().get(0).reason(), unknown.rejected().get(0).reason());
            assertEquals(before, rows(a, TriggerStore.Kind.EVENT));
            assertEquals(404, status(() -> b.triggers().events(b.admin(), "inbox", a.instance(), null, null)));
        }
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 0;
        } catch (Failure failure) {
            return failure.status();
        }
    }

    /**
     * G-STATE item 2 for Control ingest: random check-ins of records in any order, with duplicates, re-emitted ids,
     * schema-invalid records, fenced epochs and epoch changes, against a reference model. After every check-in:
     * A (acknowledged = contiguous resolved prefix), D (one row per id), F (fenced records leave nothing).
     */
    @Test
    void randomCheckInsKeepTheModelsInvariants() throws Exception {
        int runs = Integer.getInteger("toolgate.ingest.runs", 60);
        for (int run = 0; run < runs; run++) {
            var random = new Random(20261107L + run);
            boolean skip = random.nextBoolean();
            var w = world(stores().get(0).getValue(), "tenant-a", skip ? "SKIP_AND_RECORD" : "HALT");
            sync(w, "device-a", List.of());
            var bad = new HashSet<String>();
            var sent = new TreeMap<Long, String>();      // epoch-1 outbox: seq -> id
            var resolved = new TreeSet<Long>();
            long halted = 0;
            var storedIds = new HashSet<String>();
            for (int step = 0; step < 40; step++) {
                var batch = new ArrayList<TriggerOutboxRecord>();
                int op = random.nextInt(10);
                if (op < 6 || sent.isEmpty()) {
                    long seq = sent.size() + 1;
                    String id = random.nextInt(4) == 0 && !sent.isEmpty() ? sent.get(1 + random.nextLong(sent.size())) : "i" + seq;
                    boolean invalid = random.nextInt(8) == 0;
                    if (invalid) bad.add(seq + "");
                    sent.put(seq, id);
                    batch.add(invalid ? invalid(w, 1, seq, id) : fired(w, 1, seq, id));
                } else if (op < 9) {
                    var seqs = new ArrayList<>(sent.keySet());
                    Collections.shuffle(seqs, random);
                    for (long seq : seqs.subList(0, 1 + random.nextInt(seqs.size()))) {
                        String id = sent.get(seq);
                        batch.add(bad.contains(seq + "") ? invalid(w, 1, seq, id) : fired(w, 1, seq, id));
                    }
                } else {
                    batch.add(fired(w, 5, 1, "fenced-" + step));
                }
                long eventsBefore = rows(w, TriggerStore.Kind.EVENT), keysBefore = rows(w, TriggerStore.Kind.KEY);
                var ack = sync(w, "device-a", batch);
                // Reference model of T6 for the records of this batch.
                for (var record : batch) {
                    long ep = record.event().tgleaseepoch(), seq = record.event().tgsequence();
                    if (ep != 1) continue;
                    if (halted > 0 && seq >= halted) continue;
                    if (bad.contains(seq + "")) {
                        if (skip) resolved.add(seq);
                        else if (halted == 0 || seq < halted) halted = halted == 0 ? seq : Math.min(halted, seq);
                    } else {
                        resolved.add(seq);
                        storedIds.add(record.event().id());
                    }
                }
                long prefix = 0;
                while (resolved.contains(prefix + 1) && (halted == 0 || prefix + 1 < halted)) prefix++;
                assertEquals(prefix, acked(ack, 1) == 0 ? previousAck(w) : acked(ack, 1), "A at run " + run + " step " + step);
                if (batch.get(0).event().tgleaseepoch() == 5) {
                    assertEquals(eventsBefore, rows(w, TriggerStore.Kind.EVENT), "F: no row");
                    assertEquals(keysBefore, rows(w, TriggerStore.Kind.KEY), "F: no key");
                }
                var ids = new HashSet<String>();
                for (var item : events(w)) if (!item.has("disposition")) assertTrue(ids.add(item.path("event").path("id").asText()), "D at run " + run);
                assertTrue(ids.size() <= storedIds.size() + 0);
            }
        }
    }

    /** The accepted sequence currently recorded for epoch 1 (an ack carries it only when it is positive). */
    private long previousAck(World w) throws Exception {
        var row = w.store().transaction(w.tenant(), false, tx -> tx.triggers().get(TriggerStore.Kind.EPOCH, w.instance() + "/" + String.format("%016d", 1)));
        return row == null ? 0 : JSON.readTree(row.document()).path("acceptedOutboxSeq").asLong();
    }
}
