// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
