// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.fixtures.Tenants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan §9 release gates for Operator Insights that the step tests do not already cover (Stage 7): atomic capture,
 *  crash recovery, concurrent writers and projectors, late corrections, quota, replay fidelity, NONE, mode
 *  equivalence, canaries, span memory and failure injection. Each runs on SQLite; {@link #theGatesHoldOnPostgres()}
 *  repeats the ones that do not read tables directly on PostgreSQL through the runtime role. */
final class InsightsReleaseGatesTest {
    @TempDir Path directory;
    private final ContractCodec codec = new ContractCodec();
    private final Ids.TenantId a = Tenants.id("tenant-a");
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private PostgresStore store;
    private boolean postgres;
    private Path sqlite;
    private String url, password;
    private InsightsProjector.Settings settings = InsightsProjector.Settings.defaults();

    private PostgresStore store(InsightsMode mode) {
        revisions.clear();
        javax.sql.DataSource source;
        if (postgres) {
            var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Real PostgreSQL required; run tools/control/check.py");
            password = System.getenv("CONTROL_TEST_PASSWORD"); var name = "gates_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = java.sql.DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            url = base.replace("/control?", "/" + name + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password);
            source = Tenants.database(app);
        } else { sqlite = directory.resolve(UUID.randomUUID() + ".sqlite"); source = Tenants.database(SqliteState.open(sqlite)); }
        store = new PostgresStore(source, codec);
        store.insightsMode(mode);
        return store;
    }
    private InsightsService service() {
        return new InsightsService(store, settings, (cursor, limit) -> cursor.isEmpty() ? List.of(a) : List.of(), store::insightsMode);
    }
    private void transition(String id, String state, String nonce) {
        long revision = revisions.merge(id, 1L, Long::sum);
        String document = """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"%s","mode":"HUMAN","userId":"ada","agentId":"agent-planner","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"acme.orders/orders.lookup","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"%s","revision":%d,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[]%s,"diagnosticId":"%s"}"""
            .formatted(id, "a".repeat(64), id, a.value(), "b".repeat(64), "c".repeat(64), "d".repeat(64), state, revision, System.currentTimeMillis() + 600_000,
                nonce == null ? "" : ",\"reservedNonce\":\"" + nonce + "\"", diagnostic);
        try { store.transaction(a, true, tx -> { tx.enterprise().saveInvocation(codec.model(document, EnterpriseInvocation.class), System.currentTimeMillis(), revision - 1); return null; }); }
        catch (RuntimeException failure) { revisions.merge(id, -1L, Long::sum); throw failure; }
    }
    private String diagnostic = "diag";
    private void run(String id, String... states) { for (var state : states) transition(id, state, state.equals("QUEUED") ? null : "n-" + id); }
    private void runToSuccess(String id) { run(id, "QUEUED", "RESERVED", "EXECUTING", "SUCCEEDED"); }
    private InsightsProjector.Result project() { return service().projector().project(a, System.currentTimeMillis()); }
    private InsightsProjector.Result project(long now) { return service().projector().project(a, now); }
    private Map<String, Long> cells(String series, InsightsGranularity g) {
        var out = new TreeMap<String, Long>();
        store.transaction(a, false, tx -> { tx.insights().cells(series, g, 0, Long.MAX_VALUE, null).forEach(c -> { if (c.value().calls() != 0) out.merge(c.cell().dim() + "/" + c.cell().outcome(), c.value().calls(), Long::sum); }); return null; });
        return out;
    }
    private long backlog() { return store.transaction(a, false, tx -> tx.insights().backlog()).facts(); }

    /** Installs a trigger that fails inserts of one invocation's rows into a table, on either database. */
    private AutoCloseable inject(String table, String invocationId) throws Exception {
        if (postgres) {
            try (var c = java.sql.DriverManager.getConnection(url, "control_migrator", password); var s = c.createStatement()) {
                s.execute("CREATE OR REPLACE FUNCTION toolgate_inject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
                s.execute("CREATE TRIGGER toolgate_inject BEFORE INSERT ON " + table + " FOR EACH ROW WHEN (NEW.invocation_id='" + invocationId + "') EXECUTE FUNCTION toolgate_inject()");
            }
            return () -> { try (var c = java.sql.DriverManager.getConnection(url, "control_migrator", password); var s = c.createStatement()) { s.execute("DROP TRIGGER toolgate_inject ON " + table); } };
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER toolgate_inject BEFORE INSERT ON " + table + " WHEN NEW.invocation_id='" + invocationId + "' BEGIN SELECT RAISE(ABORT,'injected'); END");
        }
        return () -> { try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite); var s = c.createStatement()) { s.execute("DROP TRIGGER toolgate_inject"); } };
    }
    /** Every Insights table's row count, read directly (SQLite only: PostgreSQL forces row-level security on the owner). */
    private Map<String, Long> insightsRows() throws Exception {
        var out = new TreeMap<String, Long>();
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite); var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var rows = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'control_insights_%'")) { while (rows.next()) tables.add(rows.getString(1)); }
            for (var t : tables) try (var rows = s.executeQuery("SELECT count(*) FROM " + t)) { rows.next(); out.put(t, rows.getLong(1)); }
        }
        return out;
    }
    private String dump() throws Exception {
        var out = new StringBuilder();
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite); var s = c.createStatement()) {
            for (var t : insightsRows().keySet()) try (var rows = s.executeQuery("SELECT * FROM " + t)) {
                int n = rows.getMetaData().getColumnCount();
                while (rows.next()) for (int i = 1; i <= n; i++) out.append(rows.getString(i)).append('\t');
            }
        }
        return out.toString();
    }

    // Atomic capture: an injected fact-insert failure aborts the ledger transition; nothing commits without its fact.
    @Test void aFailedFactInsertAbortsTheTransitionItBelongsTo() throws Exception {
        store(InsightsMode.BASIC);
        transition("inv-boom", "QUEUED", null);
        var fault = inject("control_insights_facts", "inv-boom");
        try {
            assertThrows(RuntimeException.class, () -> transition("inv-boom", "RESERVED", "n1"));
            assertEquals(io.ololabs.toolgate.contracts.EnterpriseInvocationState.QUEUED,
                store.transaction(a, false, tx -> tx.enterprise().invocation("inv-boom")).state(), "the ledger did not move without its fact");
        } finally { fault.close(); }
        transition("inv-boom", "RESERVED", "n1"); transition("inv-boom", "EXECUTING", "n1"); transition("inv-boom", "SUCCEEDED", "n1");
        assertEquals(List.of(1L, 2L, 3L, 4L), store.transaction(a, false, tx -> tx.insights().facts("inv-boom")).stream().map(Insights.Fact::seq).toList());
        project();
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 1L), cells("TOOL", InsightsGranularity.HOUR));
    }

    // Projection recovery: a projector run that dies mid-batch commits nothing; the rerun counts every call once.
    @Test void aProjectorThatFailsMidBatchLeavesNothingAndTheRerunCountsOnce() throws Exception {
        store(InsightsMode.BASIC);
        for (int i = 1; i <= 5; i++) runToSuccess("inv-" + i);
        var fault = inject("control_insights_invocations", "inv-3");
        try {
            assertThrows(RuntimeException.class, this::project);
            assertTrue(cells("TOOL", InsightsGranularity.HOUR).isEmpty(), "the failed run wrote no cells");
            assertEquals(20, backlog(), "and marked no fact projected");
        } finally { fault.close(); }
        assertEquals(20, project().facts());
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 5L), cells("TOOL", InsightsGranularity.HOUR));
        assertEquals(new InsightsProjector.Result(0, 0, 0, 0), project());
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 5L), cells("TOOL", InsightsGranularity.DAY));
    }

    // Ordering and projector ownership: concurrent ledger writers and two projector "replicas" never double count,
    // never skip a fact and never deadlock.
    @Test void concurrentWritersAndProjectorsCountEveryCallExactlyOnce() throws Exception {
        store(InsightsMode.BASIC);
        int writers = 6, perWriter = 15;
        var pool = Executors.newFixedThreadPool(writers + 2); var done = new java.util.concurrent.atomic.AtomicBoolean();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        try {
            var projectors = new ArrayList<Future<?>>();
            for (int p = 0; p < 2; p++) projectors.add(pool.submit(() -> {
                while (!done.get()) try { project(); } catch (RuntimeException busy) { /* a contended run retries next loop */ }
            }));
            var work = new ArrayList<Future<?>>();
            for (int w = 0; w < writers; w++) { int writer = w; work.add(pool.submit(() -> {
                for (int i = 0; i < perWriter; i++) {
                    String id = "inv-" + writer + "-" + i, outcome = i % 5 == 0 ? "FAILED" : "SUCCEEDED";
                    try {
                        retrying(() -> transition(id, "QUEUED", null)); retrying(() -> transition(id, "RESERVED", "n-" + id));
                        retrying(() -> transition(id, "EXECUTING", "n-" + id));
                        retrying(() -> transition(id, outcome, "n-" + id));
                    } catch (Throwable t) { failures.add(t); }
                }
            })); }
            for (var f : work) f.get(5, TimeUnit.MINUTES);
            done.set(true);
            for (var f : projectors) f.get(1, TimeUnit.MINUTES);
        } finally { pool.shutdownNow(); }
        assertTrue(failures.isEmpty(), () -> "writers failed: " + failures);
        while (backlog() > 0) project();
        int total = writers * perWriter, failed = writers * ((perWriter + 4) / 5);
        assertEquals(Map.of("acme.orders/orders.lookup/FAILED", (long) failed, "acme.orders/orders.lookup/SUCCEEDED", (long) (total - failed)),
            cells("TOOL", InsightsGranularity.HOUR));
        assertEquals(cells("TOOL", InsightsGranularity.HOUR), cells("TOOL", InsightsGranularity.DAY));
        var now = System.currentTimeMillis();
        assertEquals(0, service().projector().rebuild(a, now - 3_600_000, now + 3_600_000, true, now).cellsDiffering());
    }
    private void retrying(Runnable write) {
        for (int attempt = 0; ; attempt++) {
            try { write.run(); return; }
            catch (Failure contended) { if (attempt >= 50 || contended.status() != 409 && contended.status() != 503) throw contended; Thread.onSpinWait(); }
        }
    }

    // Late corrections: a T13 outcome change after the minute bucket expired corrects the hour and day cells exactly.
    @Test void aCorrectionAfterMinuteRetentionCorrectsHourAndDayCells() {
        settings = new InsightsProjector.Settings(500, 7 * 86_400_000L, 30 * 86_400_000L, 60_000L, 90 * 86_400_000L, 396 * 86_400_000L, 2_000_000L);
        store(InsightsMode.BASIC);
        run("inv-1", "QUEUED", "RESERVED", "EXECUTING", "OUTCOME_UNKNOWN");
        project();
        assertEquals(Map.of("acme.orders/orders.lookup/OUTCOME_UNKNOWN", 1L), cells("TOOL", InsightsGranularity.MINUTE));
        long later = System.currentTimeMillis() + 3 * 3_600_000L;
        service().projector().retain(a, later);
        assertTrue(cells("TOOL", InsightsGranularity.MINUTE).isEmpty(), "the minute bucket expired");
        transition("inv-1", "FAILED", "n-inv-1"); // T13
        project(later);
        assertTrue(cells("TOOL", InsightsGranularity.MINUTE).isEmpty(), "an expired bucket is not recreated");
        assertEquals(Map.of("acme.orders/orders.lookup/FAILED", 1L), cells("TOOL", InsightsGranularity.HOUR));
        assertEquals(Map.of("acme.orders/orders.lookup/FAILED", 1L), cells("TOOL", InsightsGranularity.DAY));
    }

    // Quota: over quota, outcome changes and retries still correct aggregates exactly; summary-dependent views show it.
    @Test void overQuotaAggregatesStayExactAndSummaryViewsSayWhy() {
        settings = new InsightsProjector.Settings(500, 7 * 86_400_000L, 30 * 86_400_000L, 2 * 86_400_000L, 90 * 86_400_000L, 396 * 86_400_000L, 2L);
        store(InsightsMode.BASIC);
        runToSuccess("inv-1"); runToSuccess("inv-2"); project();
        long now = System.currentTimeMillis(); var projector = service().projector();
        projector.retain(a, now);
        assertEquals(1L, service().status(now).summariesSuspendedTenants());
        // While suspended: an in-flight call that is re-queued and then reconciled, and new finished calls.
        run("inv-3", "QUEUED", "RESERVED", "EXECUTING", "QUEUED", "RESERVED", "EXECUTING", "OUTCOME_UNKNOWN"); project();
        transition("inv-3", "SUCCEEDED", "n-inv-3"); runToSuccess("inv-4"); run("inv-5", "QUEUED", "RESERVED", "EXECUTING", "FAILED");
        project(); projector.retain(a, now);
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 4L, "acme.orders/orders.lookup/FAILED", 1L), cells("TOOL", InsightsGranularity.HOUR));
        assertNull(store.transaction(a, false, tx -> tx.insights().summary("inv-4")), "no new summary of a finished call over quota");
        assertEquals(409, assertThrows(Failure.class, () -> projector.rebuild(a, now - 60_000, now + 60_000, true, now)).status(),
            "rebuild is refused while summaries are incomplete");
    }

    // Reconciliation: replaying every retained fact into an empty projection reproduces summaries, attempts, timing
    // and cells exactly (SQLite: the replay resets projection state directly).
    @Test void replayingFactsReproducesSummariesTimingAttemptsAndCells() throws Exception {
        store(InsightsMode.BASIC);
        runToSuccess("inv-1"); run("inv-2", "QUEUED", "RESERVED", "EXECUTING", "QUEUED", "RESERVED", "EXECUTING", "FAILED");
        run("inv-3", "QUEUED", "RESERVED", "EXECUTING", "OUTCOME_UNKNOWN"); project(); transition("inv-3", "SUCCEEDED", "n-inv-3");
        transition("inv-4", "QUEUED", null); project();
        var service = service(); var before = new TreeMap<String, InsightsInvocationDetail>();
        for (var id : List.of("inv-1", "inv-2", "inv-3", "inv-4")) before.put(id, service.detail(a.value(), id));
        var cellsBefore = new TreeMap<>(Map.of("TOOL/H", cells("TOOL", InsightsGranularity.HOUR), "CLIENT/D", cells("CLIENT", InsightsGranularity.DAY)));
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite); var s = c.createStatement()) {
            for (var t : List.of("control_insights_invocations", "control_insights_attempts", "control_insights_checkpoints", "control_insights_cells")) s.execute("DELETE FROM " + t);
            s.execute("UPDATE control_insights_facts SET projected_at=NULL");
        }
        while (backlog() > 0) project();
        for (var id : before.keySet()) {
            var after = service.detail(a.value(), id);
            assertEquals(before.get(id).invocation(), after.invocation(), id);
            assertEquals(before.get(id).attempts(), after.attempts(), id);
        }
        assertEquals(cellsBefore, new TreeMap<>(Map.of("TOOL/H", cells("TOOL", InsightsGranularity.HOUR), "CLIENT/D", cells("CLIENT", InsightsGranularity.DAY))));
    }

    // NONE: no facts, checkpoints, summaries, counters, aggregates, spans or exports.
    @Test void noneWritesNothingInAnyInsightsTableAndExportsNothing() throws Exception {
        store(InsightsMode.NONE); var service = service();
        runToSuccess("inv-1");
        store.transaction(a, true, tx -> { tx.enterprise().insightsContext("inv-1", new ExecutionContext(null, null, "toolu_1", "sess-1", null, null, null)); return null; });
        long now = System.currentTimeMillis();
        service.ingest(a, new InsightsAdmissionBatch("gw-1", "e1", 1L, null, 0L,
            List.of(new InsightsAdmissionCount(Insights.floor(now, InsightsGranularity.MINUTE), null, InsightsAdmissionCategory.POLICY_DENIED, 1L))), now);
        var span = new InsightsSpan("0af7651916cd43dd8448eb211c80319c", "1111111111111111", null, InsightsSpanName.GATEWAY_REQUEST, null, "inv-1", null, now, 5L,
            InsightsSpanStatus.OK, null, null, null);
        assertEquals(0L, service.ingestSpans(a, tx -> new InsightsService.SpanSender(InsightsSpanSource.GATEWAY, null), null, new InsightsSpanBatch(List.of(span), 0L), now).accepted());
        // In NONE the Insights worker never starts (InsightsWatchdog returns before scheduling), so the projector,
        // retention and metric export do not run; an exporter asked directly still finds nothing to claim.
        var exporter = new InsightsOtlpExporter(new InsightsOtlpExporter.Settings(java.net.URI.create("http://127.0.0.1:9/"), "olo-toolgate", "test", 10, java.time.Duration.ofMillis(200)));
        assertEquals(0, exporter.metrics(store, (c, l) -> c.isEmpty() ? List.of(a) : List.of(), now + 180_000));
        assertEquals(0L, exporter.failures(), "no export was even attempted");
        insightsRows().forEach((table, rows) -> assertEquals(0L, rows, table));
    }

    // Mode equivalence: BASIC and DETAILED produce identical counts and summaries for the same workload.
    @Test void basicAndDetailedCountTheSameWorkloadIdentically() {
        var results = new ArrayList<List<Object>>();
        for (var mode : List.of(InsightsMode.BASIC, InsightsMode.DETAILED)) {
            store(mode); var service = service();
            if (mode == InsightsMode.DETAILED) service.spans(new InsightsSpanSink(store, InsightsSpanSink.Settings.defaults(), (t, s, x) -> {}));
            runToSuccess("inv-1"); run("inv-2", "QUEUED", "RESERVED", "EXECUTING", "FAILED");
            run("inv-3", "QUEUED", "RESERVED", "EXECUTING", "QUEUED", "RESERVED", "EXECUTING", "SUCCEEDED"); transition("inv-4", "QUEUED", null);
            project();
            var summaries = new ArrayList<Object>();
            for (var id : List.of("inv-1", "inv-2", "inv-3", "inv-4")) {
                var d = service.detail(a.value(), id); var i = d.invocation();
                summaries.add(List.of(i.outcome(), i.attempts(), String.valueOf(i.errorCategory()), d.attempts().stream().map(x -> String.valueOf(x.outcome())).toList()));
            }
            results.add(List.of(cells("TOOL", InsightsGranularity.HOUR), cells("CLIENT", InsightsGranularity.DAY), summaries));
        }
        assertEquals(results.get(0), results.get(1));
    }

    // PII leakage: a canary in ledger free text never reaches Insights tables, operator responses or exports.
    @Test void aCanaryInLedgerFreeTextNeverReachesInsightsResponsesOrExports() throws Exception {
        store(InsightsMode.DETAILED);
        diagnostic = "CANARY-7f3a91";
        var exported = new ArrayList<String>();
        var sink = new InsightsSpanSink(store, new InsightsSpanSink.Settings(64L << 20, 512, 60_000, 1.0, 600_000, 3_600_000), (t, s, x) -> {});
        var service = service(); service.spans(sink);
        run("inv-1", "QUEUED", "RESERVED", "EXECUTING", "FAILED");
        store.transaction(a, true, tx -> { tx.enterprise().insightsContext("inv-1", new ExecutionContext(null, null, "toolu_1", "sess-1", null, null, "vendor=CANARY-7f3a91")); return null; });
        long now = System.currentTimeMillis();
        service.ingestSpans(a, tx -> new InsightsService.SpanSender(InsightsSpanSource.GATEWAY, null), null, new InsightsSpanBatch(List.of(new InsightsSpan(
            "0af7651916cd43dd8448eb211c80319c", "1111111111111111", null, InsightsSpanName.GATEWAY_REQUEST, null, "inv-1", null, now, 5L, InsightsSpanStatus.ERROR,
            null, 500L, "UPSTREAM_ERROR")), 0L), now);
        project(); sink.resolve(System.currentTimeMillis());
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exported.add(new String(exchange.getRequestBody().readAllBytes())); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var exporter = new InsightsOtlpExporter(new InsightsOtlpExporter.Settings(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "olo-toolgate", "test", 100, java.time.Duration.ofSeconds(5)));
            exporter.spans(a, store.transaction(a, false, tx -> tx.insights().summary("inv-1")), service.detail(a.value(), "inv-1").spans());
            exporter.flush(System.currentTimeMillis());
            exporter.metrics(store, (c, l) -> c.isEmpty() ? List.of(a) : List.of(), Insights.floor(now, InsightsGranularity.MINUTE) + 120_000);
        } finally { server.stop(0); }
        assertFalse(exported.isEmpty());
        var responses = codec.json(service.detail(a.value(), "inv-1")) + codec.json(service.invocations(a.value(), now - 60_000, now + 60_000, null, null, null, null, null, 50))
            + codec.json(service.session(a.value(), "sess-1")) + codec.json(service.status(now));
        for (var surface : Map.of("tables", dump(), "responses", responses, "exports", String.join("\n", exported)).entrySet())
            assertFalse(surface.getValue().contains("CANARY"), "canary reached " + surface.getKey());
    }

    // Span memory: thousands of concurrent long-running executions keep the buffer within its budget; early
    // decisions and drops are counted.
    @Test void spanBuffersStayWithinTheGlobalBudgetUnderConcurrency() throws Exception {
        store(InsightsMode.DETAILED);
        long budget = 1000L * 320;
        var sink = new InsightsSpanSink(store, new InsightsSpanSink.Settings(budget, 512, 600_000, 0.1, 600_000, 3_600_000), (t, s, x) -> {});
        var pool = Executors.newFixedThreadPool(8); var peak = new java.util.concurrent.atomic.AtomicLong();
        try {
            var work = new ArrayList<Future<?>>();
            for (int w = 0; w < 8; w++) { int writer = w; work.add(pool.submit(() -> {
                long now = System.currentTimeMillis();
                for (int i = 0; i < 400; i++) {
                    String id = "inv-" + writer + "-" + i; var spans = new ArrayList<InsightsSpan>();
                    for (int k = 0; k < 4; k++) spans.add(new InsightsSpan("0af7651916cd43dd8448eb211c80319c", String.format("%016x", (long) writer << 40 | (long) i << 8 | k), null,
                        InsightsSpanName.GATEWAY_REQUEST, InsightsSpanSource.GATEWAY, id, null, now, 5L, InsightsSpanStatus.OK, null, null, null));
                    sink.accept(a, id, spans, now);
                    peak.accumulateAndGet(sink.stats().bytes(), Math::max);
                }
            })); }
            for (var f : work) f.get(5, TimeUnit.MINUTES);
        } finally { pool.shutdownNow(); }
        var stats = sink.stats();
        assertTrue(stats.bytes() <= budget, "buffer " + stats.bytes() + " over budget " + budget);
        assertTrue(peak.get() <= budget + 8L * 4 * 320, "peak " + peak.get() + " stayed within one batch per writer of the budget");
        assertTrue(stats.dropped() > 0, "3,200 long-running calls cannot all fit, and the losses are counted");
        assertTrue(stats.dropped() + stats.retained() + stats.buffered() >= 3200 - 8, "every call is buffered, kept early or counted as dropped");
    }

    // Failure injection: an unreachable Collector and a span flood never affect execution; drops are counted.
    @Test void anUnreachableCollectorAndASpanFloodNeverAffectExecution() {
        store(InsightsMode.DETAILED);
        var exporter = new InsightsOtlpExporter(new InsightsOtlpExporter.Settings(java.net.URI.create("http://127.0.0.1:9/"), "olo-toolgate", "test", 50,
            java.time.Duration.ofMillis(500)));
        var sink = new InsightsSpanSink(store, new InsightsSpanSink.Settings(64L << 20, 512, 600_000, 1.0, 600_000, 3_600_000), exporter);
        var service = service(); service.spans(sink);
        transition("inv-1", "QUEUED", null);
        long now = System.currentTimeMillis(); var flood = new ArrayList<InsightsSpan>();
        for (int i = 0; i < 512; i++) flood.add(new InsightsSpan("0af7651916cd43dd8448eb211c80319c", String.format("%016x", i), null, InsightsSpanName.GATEWAY_REQUEST,
            null, "inv-1", null, now, 1L, InsightsSpanStatus.OK, null, null, null));
        long accepted = 0, refused = 0;
        for (int batch = 0; batch < 3; batch++) {
            var ack = service.ingestSpans(a, tx -> new InsightsService.SpanSender(InsightsSpanSource.GATEWAY, null), null, new InsightsSpanBatch(flood, 0L), now);
            accepted += ack.accepted(); refused += ack.refused();
        }
        assertEquals(512, accepted, "at most the per-invocation cap is kept"); assertEquals(1024, refused, "the rest is refused and counted");
        run("inv-1", "RESERVED", "EXECUTING", "FAILED"); // the ledger keeps working while telemetry is flooded
        project(); sink.resolve(System.currentTimeMillis());
        long started = System.nanoTime();
        exporter.flush(System.currentTimeMillis()); exporter.flush(System.currentTimeMillis());
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "a dead endpoint fails fast and then backs off");
        assertEquals(1L, exporter.failures(), "the second flush waited for its backoff");
        assertTrue(exporter.dropped() > 0, "spans over the export queue were dropped and counted");
        assertEquals(Map.of("acme.orders/orders.lookup/FAILED", 1L), cells("TOOL", InsightsGranularity.HOUR));
        assertEquals(InsightsSpanState.RETAINED, service.detail(a.value(), "inv-1").spanState(), "the local store still has the failure's spans");
    }

    @Test void theGatesHoldOnPostgres() throws Exception {
        postgres = true;
        aFailedFactInsertAbortsTheTransitionItBelongsTo();
        aProjectorThatFailsMidBatchLeavesNothingAndTheRerunCountsOnce();
        concurrentWritersAndProjectorsCountEveryCallExactlyOnce();
        aCorrectionAfterMinuteRetentionCorrectsHourAndDayCells();
        settings = InsightsProjector.Settings.defaults();
        overQuotaAggregatesStayExactAndSummaryViewsSayWhy();
        settings = InsightsProjector.Settings.defaults();
        basicAndDetailedCountTheSameWorkloadIdentically();
        spanBuffersStayWithinTheGlobalBudgetUnderConcurrency();
        anUnreachableCollectorAndASpanFloodNeverAffectExecution();
    }
}
