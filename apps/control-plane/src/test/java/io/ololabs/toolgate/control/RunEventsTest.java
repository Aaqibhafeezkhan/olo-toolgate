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

final class RunEventsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    private record Rig(PostgresStore store, ContractCodec codec, Ids.TenantId tenant, DirectoryService.Actor admin, RunEvents events) {}

    private Rig rig() {
        var codec = new ContractCodec(); var store = new PostgresStore(SqliteState.open(directory.resolve("events.sqlite")), codec);
        var tenant = new Ids.TenantId("tenant"); var admin = TestSupport.seed(store, codec, tenant, "root");
        store.transaction(tenant, true, tx -> { var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.BINDING, new ControlExecutionBinding("bind-1", "Hello", true, 1L, "default-tools", "default-devices", List.of(), List.of(), false, false));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null; });
        var operations = new EnterpriseOperations(store, codec, null, java.time.Clock.systemUTC(), "control", "toolgate-client", 60000, 256);
        return new Rig(store, codec, tenant, admin, new RunEvents(store, codec, operations));
    }

    /** An invocation a person started that host-1 admitted under fence-1. */
    private static void executing(Rig rig, String id) {
        String document = """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"tenant","mode":"HUMAN","userId":"ada","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"ololabs.hello-world/hello_world.server_greet","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"EXECUTING","revision":1,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[],"reservedNonce":"fence-1","diagnosticId":"diag"}"""
            .formatted(id, "a".repeat(64), id, "b".repeat(64), "c".repeat(64), "d".repeat(64), System.currentTimeMillis() + 600000);
        rig.store.transaction(rig.tenant, true, tx -> { tx.enterprise().saveInvocation(rig.codec.model(document, EnterpriseInvocation.class), System.currentTimeMillis(), 0); return null; });
    }

    private static String batch(String id, String fence, String... frames) {
        return "{\"invocationId\":\"" + id + "\",\"fencingToken\":\"" + fence + "\",\"attempt\":1,\"events\":[" + String.join(",", frames) + "]}";
    }
    private static String log(int sequence, String message) {
        try { return "{\"sequence\":" + sequence + ",\"emittedAtUnixMs\":1,\"kind\":\"LOG\",\"level\":\"info\",\"message\":" + JSON.writeValueAsString(message) + "}"; }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static JsonNode node(Store.Reply reply) throws Exception { return JSON.readTree(reply.body()); }

    @Test void framesAreFencedRedactedDeduplicatedAndReplayedInOrder() throws Exception {
        var rig = rig(); executing(rig, "inv-1");
        String progress = "{\"sequence\":2,\"emittedAtUnixMs\":2,\"kind\":\"PROGRESS\",\"fraction\":0.5,\"message\":\"half way\"}";
        var first = batch("inv-1", "fence-1", log(1, "\u001b[31mcalling\u001b[0m with api_key=sk-live-123 and Bearer abcdefghijklmnop"), progress);

        // Only the admitting executor, under the current fencing token, may add frames.
        assertEquals(403, assertThrows(Failure.class, () -> rig.events.ingest(rig.tenant, tx -> "host-2", "inv-1", first)).status());
        assertEquals(403, assertThrows(Failure.class, () -> rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", batch("inv-1", "stale", log(1, "x")))).status());
        assertEquals(400, assertThrows(Failure.class, () -> rig.events.ingest(rig.tenant, tx -> "host-1", "inv-2", first)).status());
        // A LOG frame carrying a PROGRESS field is not a protocol frame.
        assertEquals(400, assertThrows(Failure.class, () -> rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1",
            batch("inv-1", "fence-1", "{\"sequence\":3,\"emittedAtUnixMs\":1,\"kind\":\"LOG\",\"level\":\"info\",\"message\":\"x\",\"fraction\":1}"))).status());

        var ack = node(rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", first));
        assertEquals(2, ack.get("acceptedThrough").asInt()); assertEquals(0, ack.get("duplicates").asInt());
        // Redelivery after a reconnect is acknowledged, never stored twice.
        var again = node(rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", first));
        assertEquals(2, again.get("duplicates").asInt()); assertEquals(2, again.get("acceptedThrough").asInt());
        // Sequence 3 never arrives: the gap is reported, not invented.
        var later = node(rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", batch("inv-1", "fence-1", log(4, "done"))));
        assertEquals(2, later.get("acceptedThrough").asInt());

        var page = node(rig.events.page(rig.admin, "inv-1", 0, 200));
        var events = page.get("events");
        assertEquals(List.of("STATE", "LOG", "PROGRESS", "LOG"), kinds(events));
        assertEquals("PLATFORM", events.get(0).get("source").asText()); assertEquals("EXECUTING", events.get(0).get("state").asText());
        assertEquals("EXECUTOR", events.get(1).get("source").asText()); assertEquals("host-1", events.get(1).get("executorId").asText());
        assertEquals("calling with api_key=[REDACTED] and Bearer [REDACTED]", events.get(1).get("message").asText());
        assertEquals(0.5, events.get(2).get("fraction").asDouble());
        for (int i = 1; i < events.size(); i++) assertTrue(events.get(i).get("cursor").asLong() > events.get(i - 1).get("cursor").asLong());
        assertEquals("[{\"attempt\":1,\"fromSequence\":3,\"toSequence\":3}]", page.get("gaps").toString());
        assertFalse(page.get("terminal").asBoolean()); assertFalse(page.get("truncated").asBoolean()); assertFalse(page.get("expired").asBoolean());

        // Cursor replay resumes after the last event the reader saw.
        long cursor = events.get(1).get("cursor").asLong();
        var resumed = node(rig.events.page(rig.admin, "inv-1", cursor, 1));
        assertEquals(List.of("PROGRESS"), kinds(resumed.get("events"))); assertTrue(resumed.get("more").asBoolean());
        assertEquals(events.get(2).get("cursor").asLong(), resumed.get("nextCursor").asLong());
    }

    @Test void readersNeedCurrentAccessAndRetentionIsExplicit() throws Exception {
        var rig = rig(); executing(rig, "inv-1");
        rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", batch("inv-1", "fence-1", log(1, "hello")));
        // Another person with no operation.read on the binding's groups sees nothing.
        var stranger = new DirectoryService.Actor(rig.tenant, "b".repeat(64), false, false, "mallory");
        assertEquals(403, assertThrows(Failure.class, () -> rig.events.page(stranger, "inv-1", 0, 50)).status());
        // The owner's access is re-evaluated on every page: without a current grant the stream is closed to them too.
        var owner = new DirectoryService.Actor(rig.tenant, "c".repeat(64), false, false, "ada");
        assertThrows(Failure.class, () -> rig.events.page(owner, "inv-1", 0, 50));
        assertEquals(400, assertThrows(Failure.class, () -> rig.events.page(rig.admin, "inv-1", -1, 50)).status());
        assertEquals(400, assertThrows(Failure.class, () -> rig.events.page(rig.admin, "inv-1", 0, 201)).status());

        assertEquals(1, rig.events.purge(rig.tenant, System.currentTimeMillis() + RunEvents.RETENTION_MS + 60000));
        var page = node(rig.events.page(rig.admin, "inv-1", 0, 50));
        assertTrue(page.get("expired").asBoolean()); assertEquals(0, page.get("events").size());
        // A purged stream takes no new frames.
        assertThrows(Failure.class, () -> rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", batch("inv-1", "fence-1", log(2, "late"))));
    }

    @Test void boundsRefuseFramesAndSayTheStreamIsTruncated() throws Exception {
        var rig = rig(); executing(rig, "inv-1");
        int sequence = 0; int refused = 0;
        while (sequence < RunEvents.MAX_EVENTS + 10) {
            var frames = new ArrayList<String>();
            for (int i = 0; i < 64; i++) frames.add(log(++sequence, "tick " + sequence));
            refused += node(rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", batch("inv-1", "fence-1", frames.toArray(String[]::new)))).get("refused").asInt();
        }
        // One platform STATE event shares the bound with the executor's frames.
        assertEquals(sequence - (RunEvents.MAX_EVENTS - 1), refused);
        var page = node(rig.events.page(rig.admin, "inv-1", 0, 200));
        assertTrue(page.get("truncated").asBoolean());
        var dropped = node(rig.events.ingest(rig.tenant, tx -> "host-1", "inv-1", "{\"invocationId\":\"inv-1\",\"fencingToken\":\"fence-1\",\"attempt\":1,\"events\":[],\"droppedFrames\":7}"));
        assertEquals(0, dropped.get("refused").asInt());
        assertEquals(7, node(rig.events.page(rig.admin, "inv-1", 0, 1)).get("droppedFrames").asLong());
    }

    @Test void redactionMasksCredentialShapesAndTerminalControls() {
        assertEquals("password=[REDACTED] ok", RunEvents.redact("password=hunter2 ok"));
        assertEquals("[REDACTED] tail", RunEvents.redact("eyJhbGciOi.eyJzdWIiOiJ4.c2lnbmF0dXJl tail"));
        assertEquals("[REDACTED]", RunEvents.redact("-----BEGIN " + "PRIVATE KEY-----\nMIIabc\n-----END " + "PRIVATE KEY-----"));
        assertEquals("clean\ttext\nline", RunEvents.redact("clean\ttext\nline\u0007\u001b]0;title\u0007"));
        assertEquals("Hello World", RunEvents.redact("Hello World"));
    }

    private static List<String> kinds(JsonNode events) { var out = new ArrayList<String>(); events.forEach(e -> out.add(e.get("kind").asText())); return out; }
}
