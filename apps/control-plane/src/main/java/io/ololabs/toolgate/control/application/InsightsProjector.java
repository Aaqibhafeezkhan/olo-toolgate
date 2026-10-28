// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsRebuildResult;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** Projects one tenant's transition facts into summaries, checkpoints and aggregate cells (ADR 031 §5).
 *
 *  <p>Each run is one tenant write transaction, so it holds the same tenant lock as ledger transitions: two Control
 *  replicas never project the same tenant at once, and a projection never interleaves with a transition. Facts are
 *  applied strictly in fact_seq order; a missing sequence stops that invocation until it arrives. A checkpoint keeps
 *  the exact contribution an invocation made, so a later fact subtracts it and adds the new one: replay, retries and
 *  the T13 outcome change never double count. */
public final class InsightsProjector {
    public record Settings(int batch, long factRetentionMs, long summaryRetentionMs, long minuteRetentionMs, long hourRetentionMs,
                           long dayRetentionMs, long maxSummaryRows) {
        public static Settings defaults() {
            long day = 86_400_000L;
            return new Settings(500, 7 * day, 30 * day, 2 * day, 90 * day, 396 * day, 2_000_000L);
        }
        public long retention(InsightsGranularity g) {
            return switch (g) { case MINUTE -> minuteRetentionMs; case HOUR -> hourRetentionMs; case DAY -> dayRetentionMs; };
        }
    }
    /** What one run did. gaps counts invocations held back by a missing sequence; drift counts cells a correction
     *  would have made negative (reported, never written). */
    public record Result(int facts, int invocations, int gaps, long drift) {}

    private final Store store; private final Settings settings;
    public InsightsProjector(Store store, Settings settings) { this.store = store; this.settings = settings; }

    public Result project(Ids.TenantId tenant, long now) { return store.transaction(tenant, true, tx -> project(tx.insights(), now)); }

    Result project(InsightsStore s, long now) {
        var state = s.state();
        if (state == null) { state = new InsightsStore.TenantState(now, null, 0); s.saveState(state); }
        boolean suspended = state.summariesSuspendedSince() != null;
        var facts = s.unprojected(settings.batch());
        var byInvocation = new LinkedHashMap<String, List<Insights.Fact>>();
        for (var f : facts) byInvocation.computeIfAbsent(f.invocationId(), k -> new ArrayList<>()).add(f);
        var deltas = Insights.deltas(); int applied = 0, gaps = 0;
        for (var entry : byInvocation.entrySet()) {
            String id = entry.getKey();
            var checkpoint = s.checkpoint(id);
            long last = checkpoint == null ? 0 : checkpoint.lastSeq();
            var summary = checkpoint == null ? null : s.summary(id);
            var previous = checkpoint == null ? null : Insights.Contribution.parse(checkpoint.contribution());
            if (summary == null) summary = previous == null ? new Insights.Summary() : restored(id, previous);
            long through = last;
            for (var f : entry.getValue()) {
                if (f.seq() <= through) continue;
                if (f.seq() != through + 1) { gaps++; break; }
                Insights.apply(summary, f); through = f.seq(); applied++;
            }
            if (through == last) continue;
            var next = Insights.Contribution.of(summary);
            if (previous != null) previous.addTo(deltas, -1);
            next.addTo(deltas, +1);
            boolean isFinal = Insights.finalState(summary.state);
            Long finalAt = isFinal ? (checkpoint != null && checkpoint.finalAt() != null ? checkpoint.finalAt() : now) : null;
            // Over quota, new summaries of finished invocations are not kept; in-flight ones still are, because their
            // next fact needs them. Aggregates stay exact either way: the checkpoint holds the contribution.
            if (!suspended || !isFinal || checkpoint != null) s.saveSummary(summary, checkpoint == null);
            s.saveCheckpoint(new InsightsStore.Checkpoint(id, through, next.json(), finalAt));
            s.markProjected(id, through, now);
        }
        long drift = apply(s, deltas, now);
        return new Result(applied, byInvocation.size(), gaps, drift);
    }

    /** A summary rebuilt from a checkpoint when its row is gone (quota): identity and acceptance only. */
    private static Insights.Summary restored(String id, Insights.Contribution c) {
        var s = new Insights.Summary(); s.invocationId = id; s.toolId = c.tool(); s.clientId = c.client().isEmpty() ? null : c.client();
        s.acceptedAt = c.acceptedAt(); s.state = "EXECUTING"; s.partialCoverage = true; return s;
    }

    /** Writes cell deltas in sorted key order, skipping buckets older than their granularity's retention. */
    private long apply(InsightsStore s, Map<Insights.Cell, Insights.Delta> deltas, long now) {
        long drift = 0;
        for (var e : deltas.entrySet()) {
            var cell = e.getKey(); var delta = e.getValue();
            if (delta.empty() || cell.bucket() < now - settings.retention(cell.granularity())) continue;
            var current = s.cell(cell);
            var next = (current == null ? Insights.CellValue.empty() : current).plus(delta);
            if (next.calls() < 0 || next.durationCount() < 0 || next.durationSum() < 0 || Arrays.stream(next.histogram()).anyMatch(v -> v < 0)) {
                drift++;
                long[] h = next.histogram().clone(); for (int i = 0; i < h.length; i++) h[i] = Math.max(0, h[i]);
                next = new Insights.CellValue(Math.max(0, next.calls()), Math.max(0, next.durationCount()), Math.max(0, next.durationSum()), h);
            }
            s.saveCell(cell, next);
        }
        return drift;
    }

    /** Retention and quota for one tenant; returns rows removed. */
    public long retain(Ids.TenantId tenant, long now) {
        return store.transaction(tenant, true, tx -> {
            var s = tx.insights(); long removed = 0; int limit = 1000;
            removed += s.purgeFinal(now - settings.factRetentionMs(), limit);
            removed += s.purgeSummaries(now - settings.summaryRetentionMs(), limit);
            for (var g : InsightsGranularity.values()) removed += s.purgeCells(g, now - settings.retention(g), limit);
            removed += s.purgeAdmissions(now - settings.hourRetentionMs(), limit);
            var state = s.state();
            if (state != null) {
                long rows = s.countSummaries();
                Long suspended = state.summariesSuspendedSince();
                if (suspended == null && rows >= settings.maxSummaryRows()) suspended = now;
                else if (suspended != null && rows < settings.maxSummaryRows() * 9 / 10) suspended = null;
                s.saveState(new InsightsStore.TenantState(state.collectingSince(), suspended, rows));
                // Over quota, finished invocations keep no facts beyond what projection needs.
                if (suspended != null) removed += s.purgeFinal(now + 1, limit);
            }
            return removed;
        });
    }

    /** Recomputes a tenant's cells for whole days covering [from, to) from its summaries, and compares them with the
     *  live cells. Applies the recomputation unless dryRun. Refused where summaries are no longer complete. */
    public InsightsRebuildResult rebuild(Ids.TenantId tenant, long from, long to, boolean dryRun, long now) {
        long day = Insights.width(InsightsGranularity.DAY);
        long start = Insights.floor(from, InsightsGranularity.DAY), end = Math.floorDiv(to + day - 1, day) * day;
        if (to <= from || end - start > 31 * day || start < now - settings.summaryRetentionMs()) throw Failure.validation();
        return store.transaction(tenant, !dryRun, tx -> {
            var s = tx.insights(); var state = s.state();
            if (state != null && state.summariesSuspendedSince() != null) throw Failure.conflict();
            var expected = new TreeMap<Insights.Cell, Insights.CellValue>();
            var deltas = Insights.deltas();
            String cursor = null;
            while (true) {
                var page = s.summaries(new InsightsStore.Filter(start, end, null, null, null, null), cursor, 200);
                for (var summary : page) Insights.Contribution.of(summary).addTo(deltas, +1);
                if (page.size() < 200) break;
                var lastRow = page.get(page.size() - 1); cursor = lastRow.acceptedAt + ":" + lastRow.invocationId;
            }
            for (var e : deltas.entrySet()) if (!e.getValue().empty()) expected.put(e.getKey(), Insights.CellValue.empty().plus(e.getValue()));
            var live = new TreeMap<Insights.Cell, Insights.CellValue>();
            for (var series : Insights.SERIES) for (var g : InsightsGranularity.values())
                for (var stored : s.cells(series, g, start, end, null)) live.put(stored.cell(), stored.value());
            var keys = new TreeSet<Insights.Cell>(); keys.addAll(expected.keySet()); keys.addAll(live.keySet());
            long compared = 0, differing = 0;
            for (var k : keys) {
                if (k.bucket() < now - settings.retention(k.granularity())) continue;
                compared++;
                var want = expected.getOrDefault(k, Insights.CellValue.empty()); var have = live.getOrDefault(k, Insights.CellValue.empty());
                if (!want.sameAs(have)) { differing++; if (!dryRun) s.saveCell(k, want); }
            }
            return new InsightsRebuildResult(tenant.value(), start, end, compared, differing, !dryRun);
        });
    }
}
