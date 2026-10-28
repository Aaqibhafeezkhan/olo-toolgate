// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.fixtures.Tenants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Stage 7 overhead measurements for ADR 031 §6 (Stage 7 Performance and Write amplification gates). Runs only with
 *  TOOLGATE_INSIGHTS_BENCHMARK=1, on SQLite and, when CONTROL_TEST_URL is set, PostgreSQL. Writes
 *  build/insights-benchmark/{database}.json. Single-threaded and local: these numbers bound the synchronous cost
 *  Insights adds to a ledger transition, not end-to-end latency under production load. */
final class InsightsBenchmarkTest {
    @TempDir Path directory;
    private final ContractCodec codec = new ContractCodec();
    private final Ids.TenantId tenant = Tenants.id("tenant-a");
    private static final int WARMUP = 300, CALLS = 750; // 4 transitions per call: 3,000 measured transitions

    private record Database(String name, PostgresStore store, String url, Path sqlite) {}

    private Database open(boolean postgres, InsightsMode mode) throws Exception {
        if (!postgres) {
            var file = directory.resolve(UUID.randomUUID() + ".sqlite");
            var store = new PostgresStore(Tenants.database(SqliteState.open(file)), codec); store.insightsMode(mode);
            return new Database("sqlite", store, null, file);
        }
        var base = System.getenv("CONTROL_TEST_URL"); var password = System.getenv("CONTROL_TEST_PASSWORD");
        var name = "bench_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = java.sql.DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
        var url = base.replace("/control?", "/" + name + "?");
        org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
        var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password);
        var store = new PostgresStore(Tenants.database(app), codec); store.insightsMode(mode);
        return new Database("postgresql", store, url, null);
    }

    private String document(String id, String state, long revision, String nonce) {
        return """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"%s","mode":"HUMAN","userId":"ada","agentId":"agent-planner","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"acme.orders/orders.lookup","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"%s","revision":%d,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[]%s,"diagnosticId":"diag"}"""
            .formatted(id, "a".repeat(64), id, tenant.value(), "b".repeat(64), "c".repeat(64), "d".repeat(64), state, revision, System.currentTimeMillis() + 600_000,
                nonce == null ? "" : ",\"reservedNonce\":\"" + nonce + "\"");
    }
    /** Runs calls through QUEUED, RESERVED, EXECUTING and SUCCEEDED; returns each transition's commit time in µs. */
    private long[] transitions(PostgresStore store, String prefix, int calls) {
        var states = List.of("QUEUED", "RESERVED", "EXECUTING", "SUCCEEDED");
        var out = new long[calls * states.size()]; int n = 0;
        for (int i = 0; i < calls; i++) {
            String id = prefix + i;
            for (int k = 0; k < states.size(); k++) {
                var model = codec.model(document(id, states.get(k), k + 1, k == 0 ? null : "n-" + id), EnterpriseInvocation.class); long revision = k;
                long started = System.nanoTime();
                store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(model, System.currentTimeMillis(), revision); return null; });
                out[n++] = (System.nanoTime() - started) / 1_000;
            }
        }
        return out;
    }
    private static Map<String, Long> percentiles(long[] micros) {
        var sorted = micros.clone(); Arrays.sort(sorted);
        java.util.function.DoubleFunction<Long> at = q -> sorted[(int) Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1)];
        return new LinkedHashMap<>(Map.of("p50", at.apply(0.50), "p95", at.apply(0.95), "p99", at.apply(0.99)));
    }

    @Test void measureOverheadOnBothDatabases() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("TOOLGATE_INSIGHTS_BENCHMARK")), "set TOOLGATE_INSIGHTS_BENCHMARK=1 to measure");
        var databases = new ArrayList<Boolean>(List.of(false));
        if (System.getenv("CONTROL_TEST_URL") != null) databases.add(true);
        var output = Path.of("build/insights-benchmark"); Files.createDirectories(output);
        for (boolean postgres : databases) {
            var report = new LinkedHashMap<String, Object>();
            var latency = new LinkedHashMap<String, Object>();
            for (var mode : List.of(InsightsMode.NONE, InsightsMode.BASIC)) {
                var db = open(postgres, mode);
                transitions(db.store(), "warm-", WARMUP);
                latency.put(mode.name(), percentiles(transitions(db.store(), "m-", CALLS)));
                if (mode == InsightsMode.BASIC) {
                    // Projector throughput over the whole backlog, and storage written per fact.
                    var service = new InsightsService(db.store(), InsightsProjector.Settings.defaults(), (c, l) -> c.isEmpty() ? List.of(tenant) : List.of(), db.store()::insightsMode);
                    long facts = db.store().transaction(tenant, false, tx -> tx.insights().backlog()).facts();
                    long started = System.nanoTime(); int runs = 0;
                    while (db.store().transaction(tenant, false, tx -> tx.insights().backlog()).facts() > 0) { service.projector().project(tenant, System.currentTimeMillis()); runs++; }
                    double seconds = (System.nanoTime() - started) / 1e9;
                    report.put("projector", Map.of("facts", facts, "seconds", Math.round(seconds * 1000) / 1000.0, "factsPerSecond", Math.round(facts / seconds), "runs", runs));
                    report.put("storage", storage(db, facts));
                }
            }
            var none = (Map<?, ?>) latency.get("NONE"); var basic = (Map<?, ?>) latency.get("BASIC");
            var added = new LinkedHashMap<String, Long>();
            for (var q : List.of("p50", "p95", "p99")) added.put(q, (Long) basic.get(q) - (Long) none.get(q));
            latency.put("addedByBasic", added);
            report.put("transitionCommitMicros", latency);
            report.put("spans", spanCost(postgres));
            report.put("measuredTransitions", CALLS * 4);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(output.resolve((postgres ? "postgresql" : "sqlite") + ".json"), json + "\n");
            System.out.println("INSIGHTS-BENCHMARK " + (postgres ? "postgresql" : "sqlite") + " " + json.replace("\n", " "));
        }
    }

    /** Rows written per fact (SQLite reads the tables; PostgreSQL forces RLS on the owner, so it reports relation sizes). */
    private Map<String, Object> storage(Database db, long facts) throws Exception {
        var out = new LinkedHashMap<String, Object>();
        if (db.sqlite() != null) {
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.sqlite()); var s = c.createStatement()) {
                long rows = 0; var tables = new ArrayList<String>();
                try (var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'control_insights_%'")) { while (r.next()) tables.add(r.getString(1)); }
                for (var t : tables) try (var r = s.executeQuery("SELECT count(*) FROM " + t)) { r.next(); out.put(t + "Rows", r.getLong(1)); rows += r.getLong(1); }
                out.put("rowsPerFact", Math.round(rows * 100.0 / facts) / 100.0);
            }
        } else {
            try (var c = java.sql.DriverManager.getConnection(db.url(), "control_migrator", System.getenv("CONTROL_TEST_PASSWORD"))) {
                for (var t : List.of("control_insights_facts", "control_insights_checkpoints", "control_insights_invocations", "control_insights_attempts", "control_insights_cells"))
                    try (var query = c.prepareStatement("SELECT pg_total_relation_size(?)")) { query.setString(1,t); try(var r=query.executeQuery()){r.next(); out.put(t + "Bytes", r.getLong(1));} }
                out.put("factBytesPerFact", (Long) out.get("control_insights_factsBytes") / facts);
            }
        }
        return out;
    }

    /** DETAILED: the cost of checking and buffering a call's four spans and deciding it, per call. */
    private Map<String, Object> spanCost(boolean postgres) throws Exception {
        var db = open(postgres, InsightsMode.DETAILED); int calls = 300;
        var service = new InsightsService(db.store(), InsightsProjector.Settings.defaults(), (c, l) -> c.isEmpty() ? List.of(tenant) : List.of(), db.store()::insightsMode);
        var sink = new InsightsSpanSink(db.store(), InsightsSpanSink.Settings.defaults(), (t, s, x) -> {}); service.spans(sink);
        transitions(db.store(), "s-", calls); service.projector().project(tenant, System.currentTimeMillis());
        long now = System.currentTimeMillis(), cpu = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < calls; i++) {
            var spans = new ArrayList<InsightsSpan>();
            for (int k = 0; k < 4; k++) spans.add(new InsightsSpan("0af7651916cd43dd8448eb211c80319c", String.format("%016x", (long) i << 8 | k), null,
                InsightsSpanName.GATEWAY_REQUEST, null, "s-" + i, null, now, 5L, InsightsSpanStatus.OK, null, null, null));
            long started = bean.getCurrentThreadCpuTime();
            service.ingestSpans(tenant, tx -> new InsightsService.SpanSender(InsightsSpanSource.GATEWAY, null), null, new InsightsSpanBatch(spans, 0L), now);
            cpu += bean.getCurrentThreadCpuTime() - started;
        }
        long started = bean.getCurrentThreadCpuTime(); sink.resolve(System.currentTimeMillis()); cpu += bean.getCurrentThreadCpuTime() - started;
        double perCallMicros = cpu / 1_000.0 / calls;
        return Map.of("cpuMicrosPerCall", Math.round(perCallMicros), "coreShareAt100CallsPerSecond", Math.round(perCallMicros * 100 / 1e6 * 1000) / 1000.0,
            "spansPerCall", 4, "decided", sink.stats().retained() + sink.stats().sampledOut());
    }
}
