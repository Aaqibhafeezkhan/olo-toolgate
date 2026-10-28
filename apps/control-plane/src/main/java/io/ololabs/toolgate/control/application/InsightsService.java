// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;
import java.util.function.BiFunction;

/** Gateway admission counters in, installation-operator queries out (ADR 031 §5, §7). Operator queries read tenant by
 *  tenant through the ordinary tenant transaction, so row-level security still fences every read. */
public final class InsightsService {
    private final Store store; private final InsightsProjector projector; private final InsightsProjector.Settings settings;
    private final BiFunction<String, Integer, List<Ids.TenantId>> tenants; private final java.util.function.Supplier<InsightsMode> mode;
    /** An epoch quiet for this long without its final batch is reported as a source gap. */
    static final long QUIET_MS = 120_000;
    static final int MAX_POINTS = 100_000;

    public InsightsService(Store store, InsightsProjector.Settings settings, BiFunction<String, Integer, List<Ids.TenantId>> tenants,
                           java.util.function.Supplier<InsightsMode> mode) {
        this.store = store; this.settings = settings; this.projector = new InsightsProjector(store, settings); this.tenants = tenants; this.mode = mode;
    }
    public InsightsProjector projector() { return projector; }
    private volatile InsightsSpanSink spans;
    /** Set when the mode is DETAILED; without it span uploads are refused and nothing is buffered. */
    public void spans(InsightsSpanSink sink) { spans = sink; }
    public InsightsSpanSink spans() { return spans; }

    /** Who uploaded spans: the Gateway (tenant-wide) or an executor device, which may only report its own invocations. */
    public record SpanSender(InsightsSpanSource source, String deviceId) {}

    /** Checks each span against the ledger before buffering it: the invocation exists, an executor reports only
     *  invocations placed on it, the span name belongs to the sender, and an execution ID is the attempt's real one.
     *  Control sets the source from the authenticated sender, never from the span. */
    public InsightsSpanAck ingestSpans(Ids.TenantId tenant, java.util.function.Function<Store.Session, SpanSender> sender, String onlyInvocation,
                                       InsightsSpanBatch batch, long now) {
        var sink = spans;
        if (sink == null || mode.get() != InsightsMode.DETAILED) return new InsightsSpanAck(0L, (long) batch.spans().size());
        var accepted = new LinkedHashMap<String, List<InsightsSpan>>(); long refused = store.transaction(tenant, false, tx -> {
            var who = sender.apply(tx); long out = 0; var ledger = new HashMap<String, io.ololabs.toolgate.contracts.EnterpriseInvocation>();
            for (var span : batch.spans()) {
                String id = span.invocationId();
                if (onlyInvocation != null && !onlyInvocation.equals(id)) { out++; continue; }
                var i = ledger.computeIfAbsent(id, k -> tx.enterprise().invocation(k));
                boolean nameFits = switch (who.source()) {
                    case GATEWAY -> span.name().name().startsWith("GATEWAY_");
                    case TOOL_HOST -> span.name().name().startsWith("HOST_");
                    case CLIENT -> span.name() == InsightsSpanName.CLIENT_EXECUTE;
                };
                if (i == null || !nameFits || (who.deviceId() != null && !who.deviceId().equals(i.evaluation().context().deviceId()))) { out++; continue; }
                String execution = span.executionId() != null && i.reservedNonce() != null
                    && span.executionId().equals(Insights.executionId(tenant.value(), id, i.reservedNonce())) ? span.executionId() : null;
                accepted.computeIfAbsent(id, k -> new ArrayList<>()).add(new InsightsSpan(span.traceId(), span.spanId(), span.parentSpanId(), span.name(), who.source(), id,
                    execution, span.startUnixMs(), span.durationMs(), span.status(), span.routeId(), span.httpStatus(), span.errorCode()));
            }
            return out;
        });
        long taken = 0;
        for (var e : accepted.entrySet()) { int n = sink.accept(tenant, e.getKey(), e.getValue(), now); taken += n; refused += e.getValue().size() - n; }
        return new InsightsSpanAck(taken, refused);
    }

    /** One Gateway batch for its own tenant. A repeated (gateway, epoch, sequence) is acknowledged as a duplicate and
     *  counted once; a skipped sequence is recorded as missing. Nothing is stored while Insights is off. */
    public InsightsAdmissionAck ingest(Ids.TenantId tenant, InsightsAdmissionBatch batch, long now) {
        for (var c : batch.counts()) if (c.bucketStartUnixMs() % 60_000 != 0) throw Failure.validation();
        if (mode.get() == InsightsMode.NONE) return new InsightsAdmissionAck(batch.sequence(), false);
        return store.transaction(tenant, true, tx -> {
            var s = tx.insights(); var known = s.epoch(batch.gatewayId(), batch.epoch());
            long last = known == null ? 0 : known.lastSequence();
            if (batch.sequence() <= last) return new InsightsAdmissionAck(batch.sequence(), true);
            for (var c : batch.counts()) s.addAdmission(c.bucketStartUnixMs(), c.toolId() == null ? "" : c.toolId(), c.category().name(), c.count());
            long missing = (known == null ? 0 : known.missing()) + (batch.sequence() - last - 1);
            long dropped = (known == null ? 0 : known.dropped()) + (batch.droppedRequests() == null ? 0 : batch.droppedRequests());
            s.saveEpoch(new InsightsStore.Epoch(batch.gatewayId(), batch.epoch(), batch.sequence(), missing, dropped, Boolean.TRUE.equals(batch.lastBatch()), now));
            return new InsightsAdmissionAck(batch.sequence(), false);
        });
    }

    private List<Ids.TenantId> allTenants() {
        var out = new ArrayList<Ids.TenantId>(); String cursor = "";
        while (true) {
            var page = tenants.apply(cursor, 256); out.addAll(page);
            if (page.size() < 256) return out;
            cursor = page.get(page.size() - 1).value();
        }
    }
    private List<Ids.TenantId> scope(String tenant) { return tenant == null ? allTenants() : List.of(new Ids.TenantId(Ids.valid(tenant))); }

    public InsightsStatus status(long now) {
        var tenantList = allTenants(); long facts = 0; Long oldest = null; int suspended = 0, failed = 0; var gaps = new ArrayList<InsightsSourceGap>();
        for (var tenant : tenantList) {
            try {
                var part = store.transaction(tenant, false, tx -> {
                    var s = tx.insights(); return new Object[]{s.backlog(), s.state(), s.epochsWithGaps(now - QUIET_MS, 16)};
                });
                var backlog = (InsightsStore.Backlog) part[0]; var state = (InsightsStore.TenantState) part[1];
                facts += backlog.facts();
                if (backlog.oldest() != null && (oldest == null || backlog.oldest() < oldest)) oldest = backlog.oldest();
                if (state != null && state.summariesSuspendedSince() != null) suspended++;
                @SuppressWarnings("unchecked") var epochs = (List<InsightsStore.Epoch>) part[2];
                for (var e : epochs) if (gaps.size() < 256)
                    gaps.add(new InsightsSourceGap(tenant.value(), e.gatewayId(), e.epoch(), e.lastSequence(), e.missing(), e.lastSeen(), e.dropped()));
            } catch (RuntimeException unavailable) { failed++; }
        }
        return new InsightsStatus(mode.get(), now, (long) tenantList.size(), facts, oldest, (long) suspended, (long) failed, gaps);
    }

    /** Aggregates for [from, to) at one granularity, merged across the selected tenants. groupBy TOOL or CLIENT keys
     *  points by that dimension; otherwise points are summed per bucket and outcome. */
    public InsightsSeries series(InsightsGranularity g, long from, long to, String groupBy, String tenant, String toolId, String clientId, long now) {
        if (to <= from || (to - from) / Insights.width(g) > 2_000) throw Failure.validation();
        if (groupBy != null && !groupBy.equals("TOOL") && !groupBy.equals("CLIENT")) throw Failure.validation();
        if (toolId != null && clientId != null) throw Failure.validation();
        String series = clientId != null || "CLIENT".equals(groupBy) ? "CLIENT" : "TOOL";
        String dim = clientId != null ? clientId : toolId;
        long start = Insights.floor(from, g);
        var points = new TreeMap<String, long[]>(); var histograms = new HashMap<String, long[]>();
        var admissions = new TreeMap<String, Long>();
        for (var t : scope(tenant)) {
            store.transaction(t, false, tx -> {
                var s = tx.insights();
                for (var stored : s.cells(series, g, start, to, dim)) {
                    var c = stored.cell(); var v = stored.value();
                    String key = c.bucket() + "\t" + (groupBy == null ? "" : c.dim()) + "\t" + c.outcome();
                    var acc = points.computeIfAbsent(key, k -> new long[3]);
                    acc[0] += v.calls(); acc[1] += v.durationCount(); acc[2] += v.durationSum();
                    var h = histograms.computeIfAbsent(key, k -> new long[Insights.BUCKETS]);
                    for (int i = 0; i < h.length; i++) h[i] += v.histogram()[i];
                }
                if (toolId == null && clientId == null) s.admissions(g, start, to).forEach((k, v) -> admissions.merge(k, v, Long::sum));
                return null;
            });
            if (points.size() > MAX_POINTS) throw Failure.validation();
        }
        var out = new ArrayList<InsightsSeriesPoint>();
        for (var e : points.entrySet()) {
            String[] k = e.getKey().split("\t", -1); long[] v = e.getValue();
            out.add(new InsightsSeriesPoint(Long.parseLong(k[0]), k[1], InsightsOutcome.valueOf(k[2]), v[0], v[1], v[2],
                Arrays.stream(histograms.get(e.getKey())).boxed().toList()));
        }
        var admissionPoints = new ArrayList<InsightsAdmissionPoint>();
        for (var e : admissions.entrySet()) {
            String[] k = e.getKey().split("\t", -1);
            admissionPoints.add(new InsightsAdmissionPoint(Long.parseLong(k[0]), InsightsAdmissionCategory.valueOf(k[1]), e.getValue()));
        }
        long horizon = now - settings.retention(g);
        return new InsightsSeries(g, start, to, groupBy == null ? "" : groupBy, start < horizon ? Insights.floor(horizon, g) + Insights.width(g) : null,
            Arrays.stream(Insights.BOUNDS_MS).boxed().toList(), out, admissionPoints);
    }

    /** The explorer. Browsing needs a tenant; a search by any correlation ID looks across tenants. */
    public InsightsInvocationPage invocations(String tenant, long from, long to, String toolId, String clientId, String outcome, String search,
                                              String after, int limit) {
        if (limit < 1 || limit > 200 || to <= from) throw Failure.validation();
        if (outcome != null) try { InsightsOutcome.valueOf(outcome); } catch (IllegalArgumentException invalid) { throw Failure.validation(); }
        if (search != null && (search.isEmpty() || search.length() > 128)) throw Failure.validation();
        if (tenant == null && search == null) throw Failure.validation();
        var filter = new InsightsStore.Filter(from, to, toolId, clientId, outcome, search);
        var items = new ArrayList<InsightsInvocation>(); String next = null;
        for (var t : scope(tenant)) {
            var rows = store.transaction(t, false, tx -> tx.insights().summaries(filter, tenant == null ? null : after, limit + 1));
            for (var s : rows) items.add(contract(t.value(), s));
            if (tenant != null && rows.size() > limit) {
                items.remove(items.size() - 1); var last = rows.get(limit - 1); next = last.acceptedAt + ":" + last.invocationId;
            }
            if (items.size() >= limit) break;
        }
        if (items.size() > limit) items.subList(limit, items.size()).clear();
        return new InsightsInvocationPage(items, next);
    }

    /** A tenant's caller-declared sessions in a range, with how many calls declared none. */
    public InsightsSessionPage sessions(String tenant, long from, long to, String after, int limit) {
        if (limit < 1 || limit > 200 || to <= from) throw Failure.validation();
        var t = new Ids.TenantId(Ids.valid(tenant));
        return store.transaction(t, false, tx -> {
            var rows = tx.insights().sessions(from, to, after, limit + 1); String next = null;
            if (rows.size() > limit) { rows = rows.subList(0, limit); var last = rows.get(limit - 1); next = last.lastActivity() + ":" + last.sessionId(); }
            var items = rows.stream().map(r -> new InsightsSession(tenant, r.sessionId(), r.firstAccepted(), r.lastActivity(), r.calls(), r.succeeded(), r.failed(),
                r.unfinished(), r.agentRuns(), r.tools())).toList();
            return new InsightsSessionPage(items, next, tx.insights().callsWithoutSession(from, to));
        });
    }

    static final int TIMELINE = 1000, NESTING = 8;
    private static final java.util.regex.Pattern DECLARED = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@_-]{0,127}");
    /** One session's calls, plus calls that declared one of them as parent (nested work that did not repeat the
     *  session ID), down to {@link #NESTING} levels. Oldest first; at most {@link #TIMELINE} calls. */
    public InsightsSessionTimeline session(String tenant, String sessionId) {
        var t = new Ids.TenantId(Ids.valid(tenant));
        if (sessionId == null || !DECLARED.matcher(sessionId).matches()) throw Failure.validation();
        return store.transaction(t, false, tx -> {
            var s = tx.insights(); var calls = new LinkedHashMap<String, Insights.Summary>();
            for (var c : s.session(sessionId, TIMELINE + 1)) calls.put(c.invocationId, c);
            List<String> frontier = new ArrayList<>(calls.keySet());
            for (int depth = 0; depth < NESTING && !frontier.isEmpty() && calls.size() <= TIMELINE; depth++) {
                var next = new ArrayList<String>();
                for (var c : s.children(frontier, TIMELINE + 1 - calls.size())) if (calls.putIfAbsent(c.invocationId, c) == null) next.add(c.invocationId);
                frontier = next;
            }
            var items = calls.values().stream().sorted(Comparator.comparingLong((Insights.Summary c) -> c.acceptedAt).thenComparing(c -> c.invocationId))
                .limit(TIMELINE).map(c -> contract(tenant, c)).toList();
            return new InsightsSessionTimeline(tenant, sessionId, items, calls.size() > TIMELINE);
        });
    }

    public InsightsInvocationDetail detail(String tenant, String invocationId) {
        var t = new Ids.TenantId(Ids.valid(tenant)); Ids.valid(invocationId);
        return store.transaction(t, false, tx -> {
            var s = tx.insights(); var summary = s.summary(invocationId);
            if (summary == null) throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.NOT_FOUND, 404, "No Insights record for this invocation");
            var attempts = summary.attempts.stream().map(a -> new InsightsAttempt((long) a.number, a.executionId, a.executorId, a.startedAt, a.endedAt,
                a.outcome, a.durationMs())).toList();
            var facts = s.facts(invocationId);
            var transitions = facts.stream().map(f -> new InsightsTransition(f.seq(), f.fromState(), f.toState(), f.occurredAt())).toList();
            var stored = mode.get() == InsightsMode.DETAILED || s.spanState(invocationId) != null ? s.spans(invocationId) : List.<InsightsSpan>of();
            String recorded = s.spanState(invocationId);
            var state = recorded != null ? InsightsSpanState.valueOf(recorded) : mode.get() != InsightsMode.DETAILED ? InsightsSpanState.NOT_COLLECTED : InsightsSpanState.PENDING;
            return new InsightsInvocationDetail(contract(tenant, summary), attempts, transitions, !facts.isEmpty(), stored, state);
        });
    }

    public InsightsRebuildResult rebuild(InsightsRebuildRequest request, long now) {
        return projector.rebuild(new Ids.TenantId(Ids.valid(request.tenantId())), request.fromUnixMs(), request.toUnixMs(), request.dryRun(), now);
    }

    static InsightsInvocation contract(String tenant, Insights.Summary s) {
        return new InsightsInvocation(tenant, s.invocationId, s.toolId, s.action, s.clientId, s.userId, s.agentId, s.toolCallId, s.sessionId, s.agentRunId,
            s.parentInvocationId, s.outcome(), s.errorCategory(), (long) s.attempts.size(), s.acceptedAt, s.approvalRequestedAt, s.approvalResolvedAt, s.queuedAt,
            s.firstStartedAt, s.terminalAt, s.approvalMs(), s.queueMs(), s.executionMs(), s.endToEndMs(), s.partialCoverage);
    }
}
