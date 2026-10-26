// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.fixtures.Tenants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** DETAILED Insights spans (ADR 031 §9): ingest checks, deterministic retention, bounded memory and OTLP export. */
final class InsightsSpansTest {
    @TempDir Path directory;
    private final ContractCodec codec = new ContractCodec();
    private final Ids.TenantId tenant = Tenants.id("tenant-a");
    private final Map<String, Long> revisions = new HashMap<>();
    private PostgresStore store;

    private InsightsService service(InsightsMode mode, InsightsSpanSink.Settings settings, InsightsSpanSink.Export export) {
        revisions.clear();
        store = new PostgresStore(Tenants.database(SqliteState.open(directory.resolve(UUID.randomUUID() + ".sqlite"))), codec);
        store.insightsMode(mode);
        var service = new InsightsService(store, InsightsProjector.Settings.defaults(), (c, l) -> c.isEmpty() ? List.of(tenant) : List.of(), store::insightsMode);
        if (mode == InsightsMode.DETAILED) service.spans(new InsightsSpanSink(store, settings, export));
        return service;
    }
    private void transition(String id, String state, String nonce, String device) {
        long revision = revisions.merge(id, 1L, Long::sum);
        String document = """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"%s","mode":"HUMAN","userId":"ada","chain":[],"bindingId":"bind-1","deviceId":"%s"},
             "toolId":"acme.orders/orders.lookup","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"%s","revision":%d,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[]%s,"diagnosticId":"diag"}"""
            .formatted(id, "a".repeat(64), id, tenant.value(), device, "b".repeat(64), "c".repeat(64), "d".repeat(64), state, revision, System.currentTimeMillis() + 600_000,
                nonce == null ? "" : ",\"reservedNonce\":\"" + nonce + "\"");
        store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(codec.model(document, EnterpriseInvocation.class), 0, revision - 1); return null; });
    }
    private void run(String id, String outcome) {
        transition(id, "QUEUED", null, "host-1"); transition(id, "RESERVED", "n-" + id, "host-1"); transition(id, "EXECUTING", "n-" + id, "host-1"); transition(id, outcome, "n-" + id, "host-1");
    }
    private static InsightsSpan span(String id, InsightsSpanName name, String spanId, String execution) {
        return new InsightsSpan("0af7651916cd43dd8448eb211c80319c", spanId, null, name, InsightsSpanSource.GATEWAY, id, execution, System.currentTimeMillis(), 40L,
            InsightsSpanStatus.OK, null, null, null);
    }
    private InsightsSpanAck host(InsightsService service, String device, String id, InsightsSpan... spans) {
        return service.ingestSpans(tenant, tx -> new InsightsService.SpanSender(InsightsSpanSource.TOOL_HOST, device), id, new InsightsSpanBatch(List.of(spans), 0L), System.currentTimeMillis());
    }
    private InsightsSpanAck gateway(InsightsService service, InsightsSpan... spans) {
        return service.ingestSpans(tenant, tx -> new InsightsService.SpanSender(InsightsSpanSource.GATEWAY, null), null, new InsightsSpanBatch(List.of(spans), 0L), System.currentTimeMillis());
    }
    private void settle(InsightsService service) {
        service.projector().project(tenant, System.currentTimeMillis()); service.spans().resolve(System.currentTimeMillis());
    }

    @Test void onlyDetailedAcceptsSpansAndEveryExecutorReportsOnlyItsOwnWork() {
        var basic = service(InsightsMode.BASIC, InsightsSpanSink.Settings.defaults(), (t, s, x) -> {});
        run("inv-1", "SUCCEEDED");
        assertEquals(new InsightsSpanAck(0L, 1L), gateway(basic, span("inv-1", InsightsSpanName.GATEWAY_REQUEST, "1111111111111111", null)));

        var service = service(InsightsMode.DETAILED, new InsightsSpanSink.Settings(64L << 20, 512, 5_000, 1.0, 600_000, 3_600_000), (t, s, x) -> {});
        run("inv-1", "FAILED");
        String real = Insights.executionId(tenant.value(), "inv-1", "n-inv-1");
        // Another device, another invocation, a Gateway-only name and an unknown invocation are refused.
        assertEquals(new InsightsSpanAck(0L, 1L), host(service, "host-2", "inv-1", span("inv-1", InsightsSpanName.HOST_EXECUTE, "2222222222222222", real)));
        assertEquals(new InsightsSpanAck(0L, 1L), host(service, "host-1", "inv-1", span("inv-9", InsightsSpanName.HOST_EXECUTE, "2222222222222222", real)));
        assertEquals(new InsightsSpanAck(0L, 1L), host(service, "host-1", "inv-1", span("inv-1", InsightsSpanName.GATEWAY_REQUEST, "2222222222222222", real)));
        assertEquals(new InsightsSpanAck(0L, 1L), gateway(service, span("inv-404", InsightsSpanName.GATEWAY_REQUEST, "3333333333333333", null)));
        // Accepted spans carry the authenticated source; a forged execution ID is removed.
        assertEquals(new InsightsSpanAck(2L, 0L), host(service, "host-1", "inv-1", span("inv-1", InsightsSpanName.HOST_EXECUTE, "4444444444444444", real),
            span("inv-1", InsightsSpanName.HOST_EGRESS, "5555555555555555", "ex-00000000000000000000000000000000")));
        settle(service);
        var detail = service.detail(tenant.value(), "inv-1");
        assertEquals(InsightsSpanState.RETAINED, detail.spanState());
        assertEquals(Set.of(InsightsSpanSource.TOOL_HOST), new HashSet<>(detail.spans().stream().map(InsightsSpan::source).toList()));
        var bySpan = new HashMap<String, InsightsSpan>(); detail.spans().forEach(s -> bySpan.put(s.spanId(), s));
        assertEquals(real, bySpan.get("4444444444444444").executionId());
        assertNull(bySpan.get("5555555555555555").executionId());
    }

    @Test void failuresAreKeptAndQuickSuccessesFollowTheSampleRule() {
        var exported = new CopyOnWriteArrayList<String>();
        var service = service(InsightsMode.DETAILED, new InsightsSpanSink.Settings(64L << 20, 512, 60_000, 0.0, 600_000, 3_600_000),
            (t, s, spans) -> spans.forEach(x -> exported.add(x.invocationId())));
        run("inv-ok", "SUCCEEDED"); run("inv-bad", "FAILED");
        gateway(service, span("inv-ok", InsightsSpanName.GATEWAY_REQUEST, "1111111111111111", null), span("inv-bad", InsightsSpanName.GATEWAY_REQUEST, "2222222222222222", null));
        settle(service);
        assertEquals(InsightsSpanState.SAMPLED_OUT, service.detail(tenant.value(), "inv-ok").spanState());
        assertTrue(service.detail(tenant.value(), "inv-ok").spans().isEmpty());
        assertEquals(InsightsSpanState.RETAINED, service.detail(tenant.value(), "inv-bad").spanState());
        assertEquals(List.of("inv-bad"), exported);
        // The rule is deterministic, so every replica decides the same way.
        var sink = service.spans();
        assertEquals(sink.sampled("inv-ok"), sink.sampled("inv-ok"));
    }

    @Test void runningPastTheSlowThresholdStreamsAndPressureDecidesEarly() throws Exception {
        var exported = new CopyOnWriteArrayList<InsightsSpan>();
        var service = service(InsightsMode.DETAILED, new InsightsSpanSink.Settings(64L << 20, 512, 0, 0.0, 600_000, 3_600_000), (t, s, x) -> exported.addAll(x));
        transition("inv-slow", "QUEUED", null, "host-1"); transition("inv-slow", "RESERVED", "n1", "host-1"); transition("inv-slow", "EXECUTING", "n1", "host-1");
        gateway(service, span("inv-slow", InsightsSpanName.GATEWAY_REQUEST, "1111111111111111", null));
        settle(service); // slow threshold 0: still running, so retained and streamed
        assertEquals(InsightsSpanState.RETAINED, service.detail(tenant.value(), "inv-slow").spanState());
        gateway(service, span("inv-slow", InsightsSpanName.GATEWAY_DISPATCH, "2222222222222222", null));
        assertEquals(2, service.detail(tenant.value(), "inv-slow").spans().size(), "later spans go straight to the store");
        assertTrue(exported.isEmpty(), "export waits for the summary of the finished call");
        transition("inv-slow", "SUCCEEDED", "n1", "host-1");
        settle(service);
        assertEquals(List.of("1111111111111111", "2222222222222222"), exported.stream().map(InsightsSpan::spanId).toList(), "streamed spans are exported once it finishes");
        assertEquals(2, service.detail(tenant.value(), "inv-slow").spans().size(), "and are not stored twice");

        // One span over a tiny budget: the oldest buffer is decided by hash alone, so with ratio 0 it is dropped.
        var tight = service(InsightsMode.DETAILED, new InsightsSpanSink.Settings(100, 512, 60_000, 0.0, 600_000, 3_600_000), (t, s, x) -> {});
        run("inv-p", "SUCCEEDED");
        gateway(tight, span("inv-p", InsightsSpanName.GATEWAY_REQUEST, "1111111111111111", null));
        // The decision came before the projector made the summary; the next pass records it.
        settle(tight);
        assertEquals(InsightsSpanState.DROPPED, tight.detail(tenant.value(), "inv-p").spanState());
        assertEquals(1, tight.spans().stats().dropped());
    }

    @Test void exportSendsAllowlistedOtlpJsonAndClaimsEachMinuteOnce() throws Exception {
        var bodies = new CopyOnWriteArrayList<String>(); var paths = new CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { paths.add(exchange.getRequestURI().getPath()); bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var exporter = new InsightsOtlpExporter(new InsightsOtlpExporter.Settings(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "olo-toolgate", "test", 10, java.time.Duration.ofSeconds(5)));
            var service = service(InsightsMode.DETAILED, new InsightsSpanSink.Settings(64L << 20, 512, 60_000, 1.0, 600_000, 3_600_000), exporter);
            long before = System.currentTimeMillis();
            if (before % 60_000 > 55_000) { Thread.sleep(60_000 - before % 60_000 + 10); before = System.currentTimeMillis(); } // keep the call inside one minute
            run("inv-1", "SUCCEEDED");
            store.transaction(tenant, true, tx -> { tx.enterprise().insightsContext("inv-1", new ExecutionContext(null, null, "toolu_01A9q", null, null, null, null)); return null; });
            host(service, "host-1", "inv-1", span("inv-1", InsightsSpanName.HOST_EXECUTE, "4444444444444444", Insights.executionId(tenant.value(), "inv-1", "n-inv-1")));
            settle(service); exporter.flush(System.currentTimeMillis());
            assertEquals(List.of("/v1/traces"), paths);
            JsonNode traces = Insights.JSON.readTree(bodies.get(0)), s = traces.at("/resourceSpans/0/scopeSpans/0/spans/0");
            assertEquals("execute_tool acme.orders/orders.lookup", s.path("name").asText());
            var attributes = new HashMap<String, String>(); s.path("attributes").forEach(a -> attributes.put(a.path("key").asText(), a.path("value").path("stringValue").asText()));
            assertEquals("toolu_01A9q", attributes.get("gen_ai.tool.call.id"));
            assertEquals("TOOL_HOST", attributes.get("toolgate.span.source"));
            assertFalse(bodies.get(0).contains("n-inv-1"), "the fencing token never leaves Control");
            assertEquals("olo-toolgate", traces.at("/resourceSpans/0/resource/attributes/0/value/stringValue").asText());

            // Metrics: claim the last closed minute once; a second pass (another replica) finds nothing to send.
            long later = Insights.floor(before, InsightsGranularity.MINUTE) + 2 * 60_000;
            assertEquals(1, exporter.metrics(store, (c, l) -> c.isEmpty() ? List.of(tenant) : List.of(), later));
            int sent = paths.size();
            assertEquals("/v1/metrics", paths.get(sent - 1));
            JsonNode metrics = Insights.JSON.readTree(bodies.get(sent - 1)), calls = metrics.at("/resourceMetrics/0/scopeMetrics/0/metrics/0");
            assertEquals("toolgate.insights.calls", calls.path("name").asText());
            assertEquals(1, calls.at("/sum/aggregationTemporality").asInt(), "delta");
            assertEquals("1", calls.at("/sum/dataPoints/0/asInt").asText());
            assertEquals("toolgate.insights.duration", metrics.at("/resourceMetrics/0/scopeMetrics/0/metrics/1/name").asText());
            exporter.metrics(store, (c, l) -> c.isEmpty() ? List.of(tenant) : List.of(), later);
            assertEquals(sent, paths.size(), "a claimed minute is never exported twice");
        } finally { server.stop(0); }
    }
}
