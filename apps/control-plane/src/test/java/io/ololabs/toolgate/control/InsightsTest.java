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
import static org.junit.jupiter.api.Assertions.*;

/** Operator Insights (ADR 031): fact capture in the ledger transaction, projection, aggregates, retention and the
 *  operator queries, on SQLite through the same store as production. */
final class InsightsTest {
    @TempDir Path directory;
    private static final long T0 = 1_791_676_800_000L; // a minute boundary
    private final ContractCodec codec = new ContractCodec();
    private PostgresStore store;
    private final Ids.TenantId a = Tenants.id("tenant-a"), b = Tenants.id("tenant-b");
    private final Map<String, Long> revisions = new HashMap<>();
    /** Set by {@link #theSameRulesHoldOnPostgres()}: each scenario then gets a fresh migrated PostgreSQL database, used
     *  through the runtime role, so row-level security fences every Insights statement. */
    private boolean postgres;
    private Path sqlite;

    private PostgresStore store(InsightsMode mode) {
        revisions.clear();
        javax.sql.DataSource source;
        if (postgres) {
            var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Real PostgreSQL required; run tools/control/check.py");
            var password = System.getenv("CONTROL_TEST_PASSWORD"); var name = "insights_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = java.sql.DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            var url = base.replace("/control?", "/" + name + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password);
            source = Tenants.database(app);
        } else { sqlite = directory.resolve(UUID.randomUUID() + ".sqlite"); source = Tenants.database(SqliteState.open(sqlite)); }
        store = new PostgresStore(source, codec);
        store.insightsMode(mode);
        return store;
    }
    private InsightsService service() {
        return new InsightsService(store, InsightsProjector.Settings.defaults(),
            (cursor, limit) -> List.of(a, b).stream().filter(t -> t.value().compareTo(cursor) > 0).sorted(Comparator.comparing(Ids.TenantId::value)).toList(),
            store::insightsMode);
    }

    /** Writes the next ledger state of an invocation, as the transitions in invocation-state-v2 do. */
    private void transition(Ids.TenantId tenant, String id, String state, String nonce, long expiresAt) {
        long revision = revisions.merge(tenant.value() + id, 1L, Long::sum);
        String document = """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"%s","mode":"HUMAN","userId":"ada","agentId":"agent-planner","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"acme.orders/orders.lookup","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"%s","revision":%d,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[]%s,"diagnosticId":"diag"}"""
            .formatted(id, "a".repeat(64), id, tenant.value(), "b".repeat(64), "c".repeat(64), "d".repeat(64), state, revision, expiresAt,
                nonce == null ? "" : ",\"reservedNonce\":\"" + nonce + "\"");
        store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(codec.model(document, EnterpriseInvocation.class), T0, revision - 1); return null; });
    }
    private void transition(String id, String state, String nonce) { transition(a, id, state, nonce, System.currentTimeMillis() + 600_000); }
    private List<Insights.Fact> facts(Ids.TenantId tenant, String id) { return store.transaction(tenant, false, tx -> tx.insights().facts(id)); }
    private InsightsProjector.Result project(Ids.TenantId tenant) { return service().projector().project(tenant, System.currentTimeMillis()); }
    private Map<String, Long> cells(Ids.TenantId tenant, String series, InsightsGranularity g) {
        var out = new TreeMap<String, Long>();
        store.transaction(tenant, false, tx -> { tx.insights().cells(series, g, 0, Long.MAX_VALUE, null).forEach(c -> out.put(c.cell().dim() + "/" + c.cell().outcome(), c.value().calls())); return null; });
        return out;
    }
    private void runToSuccess(String id) {
        transition(id, "QUEUED", null); transition(id, "RESERVED", "nonce-" + id); transition(id, "EXECUTING", "nonce-" + id); transition(id, "SUCCEEDED", "nonce-" + id);
    }

    @Test void noneWritesNoFacts() {
        store(InsightsMode.NONE); runToSuccess("inv-1");
        assertTrue(facts(a, "inv-1").isEmpty());
        assertEquals(new InsightsProjector.Result(0, 0, 0, 0), project(a));
        assertTrue(cells(a, "TOOL", InsightsGranularity.MINUTE).isEmpty());
    }

    @Test void everyStateChangeIsOneOrderedFactAndTheFencingTokenNeverAppears() {
        store(InsightsMode.BASIC); runToSuccess("inv-1");
        var facts = facts(a, "inv-1");
        assertEquals(List.of(1L, 2L, 3L, 4L), facts.stream().map(Insights.Fact::seq).toList());
        assertEquals(Arrays.asList(null, "QUEUED", "RESERVED", "EXECUTING"), facts.stream().map(Insights.Fact::fromState).toList());
        assertEquals(List.of("QUEUED", "RESERVED", "EXECUTING", "SUCCEEDED"), facts.stream().map(Insights.Fact::toState).toList());
        assertEquals(Insights.executionId(a.value(), "inv-1", "nonce-inv-1"), facts.get(2).executionId());
        assertTrue(facts.stream().noneMatch(f -> f.detail().contains("nonce-inv-1") || String.valueOf(f.executionId()).contains("nonce")));
        // Revision bumps that are not state changes (lease renewal) write no fact.
        transition("inv-2", "QUEUED", null); transition("inv-2", "QUEUED", null);
        assertEquals(1, facts(a, "inv-2").size());
    }

    @Test void historyBeforeTheProjectorRunsIsKeptInFull() {
        store(InsightsMode.BASIC);
        transition("inv-1", "QUEUED", null); transition("inv-1", "RESERVED", "n1"); transition("inv-1", "EXECUTING", "n1");
        transition("inv-1", "OUTCOME_UNKNOWN", "n1"); transition("inv-1", "SUCCEEDED", "n1");
        assertEquals(5, project(a).facts());
        var detail = service().detail(a.value(), "inv-1");
        var summary = detail.invocation();
        assertEquals(InsightsOutcome.SUCCEEDED, summary.outcome());
        assertEquals(1L, summary.attempts());
        assertEquals(InsightsOutcome.SUCCEEDED, detail.attempts().get(0).outcome());
        assertNotNull(summary.queuedAtUnixMs()); assertNotNull(summary.firstStartedAtUnixMs()); assertNotNull(summary.terminalAtUnixMs());
        assertEquals(5, detail.transitions().size());
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 1L), cells(a, "TOOL", InsightsGranularity.MINUTE));
        assertEquals(Map.of("bind-1/SUCCEEDED", 1L), cells(a, "CLIENT", InsightsGranularity.DAY));
    }

    @Test void incrementalProjectionEqualsOneShotAndReplayIsANoOp() {
        store(InsightsMode.BASIC);
        transition("inv-1", "QUEUED", null); project(a);
        assertEquals(Map.of("acme.orders/orders.lookup/IN_PROGRESS", 1L), cells(a, "TOOL", InsightsGranularity.HOUR));
        transition("inv-1", "RESERVED", "n1"); project(a); transition("inv-1", "EXECUTING", "n1"); project(a); transition("inv-1", "SUCCEEDED", "n1"); project(a);
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 1L), cells(a, "TOOL", InsightsGranularity.HOUR));
        assertEquals(new InsightsProjector.Result(0, 0, 0, 0), project(a));
        assertEquals(Map.of("acme.orders/orders.lookup/SUCCEEDED", 1L), cells(a, "TOOL", InsightsGranularity.HOUR));
    }

    @Test void aReconciledOutcomeMovesBetweenCellsExactly() {
        store(InsightsMode.BASIC);
        transition("inv-1", "QUEUED", null); transition("inv-1", "RESERVED", "n1"); transition("inv-1", "EXECUTING", "n1"); transition("inv-1", "OUTCOME_UNKNOWN", "n1");
        project(a);
        assertEquals(Map.of("acme.orders/orders.lookup/OUTCOME_UNKNOWN", 1L), cells(a, "TOOL", InsightsGranularity.MINUTE));
        transition("inv-1", "FAILED", "n1"); // T13
        project(a);
        assertEquals(Map.of("acme.orders/orders.lookup/FAILED", 1L), cells(a, "TOOL", InsightsGranularity.MINUTE));
        assertEquals(InsightsErrorCategory.TOOL_ERROR, service().detail(a.value(), "inv-1").invocation().errorCategory());
    }

    @Test void aRequeueStartsANewAttemptWithItsOwnExecutionId() {
        store(InsightsMode.BASIC);
        transition("inv-1", "QUEUED", null); transition("inv-1", "RESERVED", "n1"); transition("inv-1", "EXECUTING", "n1");
        transition("inv-1", "QUEUED", "n1"); transition("inv-1", "RESERVED", "n2"); transition("inv-1", "EXECUTING", "n2"); transition("inv-1", "SUCCEEDED", "n2");
        project(a);
        var attempts = service().detail(a.value(), "inv-1").attempts();
        assertEquals(2, attempts.size());
        assertEquals(InsightsOutcome.FAILED, attempts.get(0).outcome());
        assertEquals(InsightsOutcome.SUCCEEDED, attempts.get(1).outcome());
        assertNotEquals(attempts.get(0).executionId(), attempts.get(1).executionId());
        assertEquals(Insights.executionId(a.value(), "inv-1", "n2"), attempts.get(1).executionId());
    }

    @Test void aMissingSequenceHoldsTheInvocationBack() throws Exception {
        store(InsightsMode.BASIC); runToSuccess("inv-1");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             var s = c.prepareStatement("DELETE FROM control_insights_facts WHERE invocation_id='inv-1' AND fact_seq=2")) { s.executeUpdate(); }
        var result = project(a);
        assertEquals(1, result.facts()); assertEquals(1, result.gaps());
        assertEquals(2, store.transaction(a, false, tx -> tx.insights().backlog()).facts());
    }

    @Test void expiryIsATimeoutAndDeclaredContextReachesTheSummary() {
        store(InsightsMode.BASIC);
        transition(a, "inv-1", "QUEUED", null, System.currentTimeMillis() + 600_000);
        var context = new ExecutionContext(null, null, "toolu_01A9q", "sess-42", "run-9", null, null);
        store.transaction(a, true, tx -> { tx.enterprise().insightsContext("inv-1", context); return null; });
        transition(a, "inv-1", "RESERVED", "n1", 1); transition(a, "inv-1", "EXECUTING", "n1", 1); transition(a, "inv-1", "FAILED", "n1", 1);
        project(a);
        var summary = service().detail(a.value(), "inv-1").invocation();
        assertEquals("toolu_01A9q", summary.toolCallId()); assertEquals("sess-42", summary.sessionId()); assertEquals("run-9", summary.agentRunId());
        assertEquals(InsightsErrorCategory.TIMEOUT, summary.errorCategory());
        assertEquals("agent-planner", summary.agentId()); assertEquals("bind-1", summary.clientId());
    }

    @Test void rebuildFindsAndRepairsDriftAndRetentionDropsFactsWithTheirCheckpoint() {
        store(InsightsMode.BASIC); runToSuccess("inv-1"); runToSuccess("inv-2"); transition("inv-3", "QUEUED", null);
        project(a);
        long now = System.currentTimeMillis(), from = now - 60_000, to = now + 60_000; var projector = service().projector();
        var clean = projector.rebuild(a, from, to, true, now);
        assertEquals(0, clean.cellsDiffering()); assertTrue(clean.cellsCompared() > 0);
        store.transaction(a, true, tx -> { tx.insights().saveCell(new Insights.Cell("TOOL", InsightsGranularity.DAY, Insights.floor(now, InsightsGranularity.DAY),
            "acme.orders/orders.lookup", "SUCCEEDED"), new Insights.CellValue(7, 0, 0, new long[Insights.BUCKETS])); return null; });
        assertEquals(1, projector.rebuild(a, from, to, true, now).cellsDiffering());
        assertTrue(projector.rebuild(a, from, to, false, now).applied());
        assertEquals(0, projector.rebuild(a, from, to, true, now).cellsDiffering());

        long later = now + 8L * 86_400_000L;
        projector.retain(a, later);
        assertTrue(facts(a, "inv-1").isEmpty(), "final invocations lose facts after fact retention");
        assertNull(store.transaction(a, false, tx -> tx.insights().checkpoint("inv-1")));
        assertFalse(facts(a, "inv-3").isEmpty(), "an unfinished invocation keeps its facts and checkpoint");
        assertNotNull(store.transaction(a, false, tx -> tx.insights().checkpoint("inv-3")));
        assertNotNull(store.transaction(a, false, tx -> tx.insights().summary("inv-1")), "summaries outlive facts");
    }

    @Test void admissionBatchesAreCountedOnceAndGapsAreReported() {
        store(InsightsMode.BASIC); var service = service(); long now = System.currentTimeMillis();
        var counts = List.of(new InsightsAdmissionCount(T0, "acme.orders/orders.refund", InsightsAdmissionCategory.POLICY_DENIED, 3L));
        assertFalse(service.ingest(a, new InsightsAdmissionBatch("gw-1", "e1", 1L, null, 0L, counts), now).duplicate());
        assertTrue(service.ingest(a, new InsightsAdmissionBatch("gw-1", "e1", 1L, null, 0L, counts), now).duplicate());
        service.ingest(a, new InsightsAdmissionBatch("gw-1", "e1", 3L, null, 2L, counts), now);
        var series = service.series(InsightsGranularity.MINUTE, T0, T0 + 60_000, null, a.value(), null, null, now);
        assertEquals(List.of(6L), series.admissions().stream().map(InsightsAdmissionPoint::count).toList());
        var gaps = service.status(now).sourceGaps();
        assertEquals(1, gaps.size());
        assertEquals(1L, gaps.get(0).missingSequences()); assertEquals(2L, gaps.get(0).droppedRequests());
        assertThrows(Failure.class, () -> service.ingest(a, new InsightsAdmissionBatch("gw-1", "e1", 4L, null, 0L,
            List.of(new InsightsAdmissionCount(T0 + 1, null, InsightsAdmissionCategory.UNAUTHENTICATED, 1L))), now));
    }

    @Test void operatorQueriesMergeTenantsAndSearchAcrossThem() {
        store(InsightsMode.BASIC); runToSuccess("inv-1");
        transition(b, "inv-9", "QUEUED", null, System.currentTimeMillis() + 600_000);
        store.transaction(b, true, tx -> { tx.enterprise().insightsContext("inv-9", new ExecutionContext(null, null, "call_7Hq2", null, null, null, null)); return null; });
        project(a); project(b);
        var service = service(); long now = System.currentTimeMillis(), from = now - 3_600_000, to = now + 3_600_000;
        var series = service.series(InsightsGranularity.HOUR, from, to, null, null, null, null, now);
        assertEquals(2L, series.points().stream().mapToLong(InsightsSeriesPoint::count).sum());
        var byTool = service.series(InsightsGranularity.HOUR, from, to, "TOOL", a.value(), null, null, now);
        assertEquals(Set.of("acme.orders/orders.lookup"), byTool.points().stream().map(InsightsSeriesPoint::key).collect(java.util.stream.Collectors.toSet()));
        var found = service.invocations(null, from, to, null, null, null, "call_7Hq2", null, 50);
        assertEquals(List.of(b.value() + "/inv-9"), found.items().stream().map(i -> i.tenantId() + "/" + i.invocationId()).toList());
        var execution = Insights.executionId(a.value(), "inv-1", "nonce-inv-1");
        assertEquals("inv-1", service.invocations(null, from, to, null, null, null, execution, null, 50).items().get(0).invocationId());
        assertThrows(Failure.class, () -> service.invocations(null, T0, T0 + 1, null, null, null, null, null, 50), "browsing needs a tenant");
        assertEquals(1, service.invocations(a.value(), from, to, null, null, "SUCCEEDED", null, null, 50).items().size());
    }

    private void declare(Ids.TenantId tenant, String id, String session, String parent) {
        store.transaction(tenant, true, tx -> { tx.enterprise().insightsContext(id, new ExecutionContext(null, null, null, session, null, parent, null)); return null; });
    }

    /** ADR 031 §12: sessions group a tenant's calls by declared session ID, and the timeline follows declared parents
     *  into nested work that did not repeat the session. A parent is kept only when it is another invocation of the
     *  same tenant. */
    @Test void sessionsGroupParallelAndNestedCallsWithinTheTenant() {
        store(InsightsMode.BASIC);
        for (var id : List.of("inv-1", "inv-2")) { transition(id, "QUEUED", null); declare(a, id, "sess-1", null); }
        transition("inv-3", "QUEUED", null); declare(a, "inv-3", null, "inv-1");   // nested under inv-1, no session
        transition("inv-4", "QUEUED", null); declare(a, "inv-4", null, "inv-3");   // nested two levels down
        transition("inv-5", "QUEUED", null); declare(a, "inv-5", "sess-2", "inv-404"); // unknown parent: dropped
        transition("inv-7", "QUEUED", null); declare(a, "inv-7", null, "inv-7");   // itself: dropped
        transition(b, "inv-6", "QUEUED", null, System.currentTimeMillis() + 600_000); declare(b, "inv-6", "sess-1", "inv-1"); // another tenant's ID: dropped
        for (var state : List.of("RESERVED", "EXECUTING", "SUCCEEDED")) transition("inv-1", state, "n-1");
        for (var state : List.of("RESERVED", "EXECUTING", "FAILED")) transition("inv-2", state, "n-2");
        project(a); project(b);
        var service = service(); long now = System.currentTimeMillis(), from = now - 3_600_000, to = now + 3_600_000;

        var page = service.sessions(a.value(), from, to, null, 50);
        var sessions = page.items().stream().collect(java.util.stream.Collectors.toMap(InsightsSession::sessionId, s -> s));
        assertEquals(Set.of("sess-1", "sess-2"), sessions.keySet(), "tenant b's sess-1 is not tenant a's");
        var one = sessions.get("sess-1");
        assertEquals(List.of(2L, 1L, 1L, 0L, 1L), List.of(one.calls(), one.succeeded(), one.failed(), one.unfinished(), one.tools()));
        assertEquals(3L, page.callsWithoutSession());
        var first = service.sessions(a.value(), from, to, null, 1);
        assertEquals(1, first.items().size());
        var second = service.sessions(a.value(), from, to, first.next(), 1);
        assertEquals(1, second.items().size()); assertNull(second.next());
        assertNotEquals(first.items().get(0).sessionId(), second.items().get(0).sessionId());

        var timeline = service.session(a.value(), "sess-1");
        var parents = new HashMap<String, String>();
        timeline.invocations().forEach(i -> parents.put(i.invocationId(), String.valueOf(i.parentInvocationId())));
        assertEquals(Map.of("inv-1", "null", "inv-2", "null", "inv-3", "inv-1", "inv-4", "inv-3"), parents);
        assertFalse(timeline.truncated());
        assertNull(service.detail(a.value(), "inv-5").invocation().parentInvocationId());
        assertNull(service.detail(a.value(), "inv-7").invocation().parentInvocationId());
        assertNull(service.detail(b.value(), "inv-6").invocation().parentInvocationId());
        assertEquals(List.of("inv-6"), service.session(b.value(), "sess-1").invocations().stream().map(InsightsInvocation::invocationId).toList());
        assertThrows(Failure.class, () -> service.session(a.value(), "bad id"));
        assertThrows(Failure.class, () -> service.sessions(a.value(), from, to, "not-a-cursor", 50));
    }

    @Test void theSameRulesHoldOnPostgres() {
        postgres = true;
        noneWritesNoFacts();
        everyStateChangeIsOneOrderedFactAndTheFencingTokenNeverAppears();
        historyBeforeTheProjectorRunsIsKeptInFull();
        incrementalProjectionEqualsOneShotAndReplayIsANoOp();
        aReconciledOutcomeMovesBetweenCellsExactly();
        aRequeueStartsANewAttemptWithItsOwnExecutionId();
        expiryIsATimeoutAndDeclaredContextReachesTheSummary();
        rebuildFindsAndRepairsDriftAndRetentionDropsFactsWithTheirCheckpoint();
        admissionBatchesAreCountedOnceAndGapsAreReported();
        operatorQueriesMergeTenantsAndSearchAcrossThem();
        sessionsGroupParallelAndNestedCallsWithinTheTenant();
    }

    @Test void percentilesNeedEnoughSamples() {
        long[] h = new long[Insights.BUCKETS]; h[Insights.histogramIndex(150)] = 18;
        assertEquals(200L, Insights.percentile(h, 0.5)); assertNull(Insights.percentile(h, 0.95), "18 samples are too few for p95");
        h[Insights.histogramIndex(4_000)] = 2; // 20 samples: the 19th is the first slow one
        assertEquals(5_000L, Insights.percentile(h, 0.95)); assertNull(Insights.percentile(h, 0.99));
    }
}
