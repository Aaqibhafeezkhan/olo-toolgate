// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsOutcome;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.Insights;
import io.ololabs.toolgate.control.application.InsightsStore;
import java.sql.*;
import java.util.*;

/** Operator Insights rows inside the caller's tenant transaction (ADR 031). Every statement is tenant-filtered and the
 *  tables carry forced row-level security; the same SQL serves PostgreSQL and SQLite. */
final class JdbcInsightsStore implements InsightsStore {
    private final Connection connection; private final String tenant;
    JdbcInsightsStore(Connection connection, String tenant) { this.connection = connection; this.tenant = tenant; }

    /** Binds the tenant first, then values in order. */
    private PreparedStatement query(String sql, Object... values) throws SQLException {
        var s = connection.prepareStatement(SqliteState.sql(connection, sql)); s.setString(1, tenant);
        for (int i = 0; i < values.length; i++) s.setObject(i + 2, values[i]); return s;
    }
    private static Long optional(ResultSet rows, int column) throws SQLException { long v = rows.getLong(column); return rows.wasNull() ? null : v; }

    public void insertFact(Insights.Fact f) {
        try (var s = query("INSERT INTO control_insights_facts(tenant_id,invocation_id,fact_seq,revision,from_state,to_state,occurred_at,execution_id,detail) VALUES(?,?,?,?,?,?,?,?,?)",
            f.invocationId(), f.seq(), f.revision(), f.fromState(), f.toState(), f.occurredAt(), f.executionId(), f.detail())) {
            if (s.executeUpdate() != 1) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public void declare(String invocationId, String contextJson) {
        try {
            String detail;
            try (var s = query("SELECT detail FROM control_insights_facts WHERE tenant_id=? AND invocation_id=? AND fact_seq=1", invocationId); var rows = s.executeQuery()) {
                if (!rows.next()) return; detail = rows.getString(1);
            }
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) Insights.JSON.readTree(detail);
            node.set("context", Insights.JSON.readTree(contextJson));
            try (var s = connection.prepareStatement("UPDATE control_insights_facts SET detail=? WHERE tenant_id=? AND invocation_id=? AND fact_seq=1 AND projected_at IS NULL")) {
                s.setString(1, node.toString()); s.setString(2, tenant); s.setString(3, invocationId); s.executeUpdate();
            }
        } catch (SQLException | java.io.IOException failure) { throw Failure.unavailable(); }
    }

    public List<Insights.Fact> unprojected(int limit) {
        try (var s = query("SELECT invocation_id,fact_seq,revision,from_state,to_state,occurred_at,execution_id,detail FROM control_insights_facts "
            + "WHERE tenant_id=? AND projected_at IS NULL ORDER BY invocation_id,fact_seq LIMIT ?", limit); var rows = s.executeQuery()) {
            var out = new ArrayList<Insights.Fact>();
            while (rows.next()) out.add(fact(rows));
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    private static Insights.Fact fact(ResultSet rows) throws SQLException {
        return new Insights.Fact(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4), rows.getString(5), rows.getLong(6), rows.getString(7), rows.getString(8));
    }

    public Backlog backlog() {
        try (var s = query("SELECT count(*),min(occurred_at) FROM control_insights_facts WHERE tenant_id=? AND projected_at IS NULL"); var rows = s.executeQuery()) {
            rows.next(); return new Backlog(rows.getLong(1), optional(rows, 2));
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public Checkpoint checkpoint(String id) {
        try (var s = query("SELECT last_fact_seq,contribution,final_at FROM control_insights_checkpoints WHERE tenant_id=? AND invocation_id=?", id); var rows = s.executeQuery()) {
            return rows.next() ? new Checkpoint(id, rows.getLong(1), rows.getString(2), optional(rows, 3)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void saveCheckpoint(Checkpoint c) {
        try (var s = query("INSERT INTO control_insights_checkpoints(tenant_id,invocation_id,last_fact_seq,contribution,final_at) VALUES(?,?,?,?,?) "
            + "ON CONFLICT(tenant_id,invocation_id) DO UPDATE SET last_fact_seq=excluded.last_fact_seq,contribution=excluded.contribution,final_at=excluded.final_at",
            c.invocationId(), c.lastSeq(), c.contribution(), c.finalAt())) { s.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }

    private static final String SUMMARY = "invocation_id,tool_id,action,client_id,user_id,agent_id,tool_call_id,session_id,agent_run_id,outcome,error_category,"
        + "attempts,accepted_at,approval_requested_at,approval_resolved_at,queued_at,first_started_at,terminal_at,partial_coverage";
    public Insights.Summary summary(String id) {
        try {
            Insights.Summary s;
            try (var q = query("SELECT " + SUMMARY + " FROM control_insights_invocations WHERE tenant_id=? AND invocation_id=?", id); var rows = q.executeQuery()) {
                if (!rows.next()) return null; s = summary(rows);
            }
            attempts(s); return s;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    /** The persisted state of a summary. The current ledger state is kept as the outcome, so it is re-derived from it. */
    private static Insights.Summary summary(ResultSet rows) throws SQLException {
        var s = new Insights.Summary();
        s.invocationId = rows.getString(1); s.toolId = rows.getString(2); s.action = rows.getString(3); s.clientId = rows.getString(4);
        s.userId = rows.getString(5); s.agentId = rows.getString(6); s.toolCallId = rows.getString(7); s.sessionId = rows.getString(8);
        s.agentRunId = rows.getString(9); s.state = state(rows.getString(10)); s.cause = cause(rows.getString(11));
        s.acceptedAt = rows.getLong(13); s.approvalRequestedAt = optional(rows, 14); s.approvalResolvedAt = optional(rows, 15);
        s.queuedAt = optional(rows, 16); s.firstStartedAt = optional(rows, 17); s.terminalAt = optional(rows, 18); s.partialCoverage = rows.getBoolean(19);
        return s;
    }
    /** Summary rows store the outcome; the projector keeps the ledger state, so map it back to a representative one. */
    private static String state(String outcome) {
        return switch (InsightsOutcome.valueOf(outcome)) {
            case PENDING_APPROVAL -> "PENDING_APPROVAL"; case IN_PROGRESS -> "EXECUTING"; default -> outcome;
        };
    }
    private static String cause(String category) {
        if (category == null) return null;
        return switch (category) { case "INFRASTRUCTURE" -> "NOT_STARTED"; case "TIMEOUT" -> "LEASE_EXPIRED"; default -> null; };
    }
    private void attempts(Insights.Summary s) throws SQLException {
        try (var q = query("SELECT attempt_number,execution_id,executor_id,started_at,ended_at,outcome FROM control_insights_attempts WHERE tenant_id=? AND invocation_id=? ORDER BY attempt_number",
            s.invocationId); var rows = q.executeQuery()) {
            while (rows.next()) {
                var a = new Insights.Attempt(); a.number = rows.getInt(1); a.executionId = rows.getString(2); a.executorId = rows.getString(3);
                a.startedAt = optional(rows, 4); a.endedAt = optional(rows, 5); a.outcome = rows.getString(6) == null ? null : InsightsOutcome.valueOf(rows.getString(6));
                s.attempts.add(a);
            }
        }
    }
    public void saveSummary(Insights.Summary s, boolean newRow) {
        var category = s.errorCategory();
        Object[] values = {s.invocationId, s.toolId, s.action, s.clientId, s.userId, s.agentId, s.toolCallId, s.sessionId, s.agentRunId, s.outcome().name(),
            category == null ? null : category.name(), s.attempts.size(), s.acceptedAt, s.approvalRequestedAt, s.approvalResolvedAt, s.queuedAt,
            s.firstStartedAt, s.terminalAt, s.approvalMs(), s.queueMs(), s.executionMs(), s.endToEndMs(), s.partialCoverage};
        String columns = SUMMARY.replace(",partial_coverage", ",approval_ms,queue_ms,execution_ms,end_to_end_ms,partial_coverage");
        var update = new StringBuilder();
        for (String c : columns.split(",")) if (!c.equals("invocation_id")) update.append(update.isEmpty() ? "" : ",").append(c).append("=excluded.").append(c);
        try (var q = query("INSERT INTO control_insights_invocations(tenant_id," + columns + ") VALUES(?" + ",?".repeat(values.length) + ") "
            + "ON CONFLICT(tenant_id,invocation_id) DO UPDATE SET " + update, values)) { q.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
        for (var a : s.attempts) {
            try (var q = query("INSERT INTO control_insights_attempts(tenant_id,invocation_id,attempt_number,execution_id,executor_id,started_at,ended_at,outcome,duration_ms) VALUES(?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(tenant_id,invocation_id,attempt_number) DO UPDATE SET execution_id=excluded.execution_id,executor_id=excluded.executor_id,"
                + "started_at=excluded.started_at,ended_at=excluded.ended_at,outcome=excluded.outcome,duration_ms=excluded.duration_ms",
                s.invocationId, a.number, a.executionId, a.executorId, a.startedAt, a.endedAt, a.outcome == null ? null : a.outcome.name(), a.durationMs())) {
                q.executeUpdate();
            } catch (SQLException failure) { throw Failure.unavailable(); }
        }
    }

    public void markProjected(String id, long throughSeq, long now) {
        try (var s = connection.prepareStatement("UPDATE control_insights_facts SET projected_at=? WHERE tenant_id=? AND invocation_id=? AND fact_seq<=? AND projected_at IS NULL")) {
            s.setLong(1, now); s.setString(2, tenant); s.setString(3, id); s.setLong(4, throughSeq); s.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public Insights.CellValue cell(Insights.Cell c) {
        try (var s = query("SELECT calls,duration_count,duration_sum,histogram FROM control_insights_cells WHERE tenant_id=? AND series=? AND granularity=? AND bucket_start=? AND dim=? AND outcome=?",
            c.series(), c.granularity().name(), c.bucket(), c.dim(), c.outcome()); var rows = s.executeQuery()) {
            return rows.next() ? new Insights.CellValue(rows.getLong(1), rows.getLong(2), rows.getLong(3), histogram(rows.getString(4))) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void saveCell(Insights.Cell c, Insights.CellValue v) {
        try {
            if (v.zero()) {
                try (var s = query("DELETE FROM control_insights_cells WHERE tenant_id=? AND series=? AND granularity=? AND bucket_start=? AND dim=? AND outcome=?",
                    c.series(), c.granularity().name(), c.bucket(), c.dim(), c.outcome())) { s.executeUpdate(); }
                return;
            }
            if (v.calls() < 0 || v.durationCount() < 0 || v.durationSum() < 0) throw new IllegalStateException("negative Insights cell " + c);
            try (var s = query("INSERT INTO control_insights_cells(tenant_id,series,granularity,bucket_start,dim,outcome,calls,duration_count,duration_sum,histogram) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(tenant_id,series,granularity,bucket_start,dim,outcome) DO UPDATE SET calls=excluded.calls,duration_count=excluded.duration_count,"
                + "duration_sum=excluded.duration_sum,histogram=excluded.histogram",
                c.series(), c.granularity().name(), c.bucket(), c.dim(), c.outcome(), v.calls(), v.durationCount(), v.durationSum(), histogram(v.histogram()))) {
                s.executeUpdate();
            }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    private static long[] histogram(String json) {
        long[] h = new long[Insights.BUCKETS];
        var node = Insights.parseArray(json);
        for (int i = 0; i < h.length && i < node.size(); i++) h[i] = node.get(i).asLong();
        return h;
    }
    private static String histogram(long[] h) { var sb = new StringBuilder("["); for (int i = 0; i < h.length; i++) sb.append(i == 0 ? "" : ",").append(h[i]); return sb.append(']').toString(); }

    public TenantState state() {
        try (var s = query("SELECT collecting_since,summaries_suspended_since,summary_rows FROM control_insights_tenant_state WHERE tenant_id=?"); var rows = s.executeQuery()) {
            return rows.next() ? new TenantState(rows.getLong(1), optional(rows, 2), rows.getLong(3)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void saveState(TenantState v) {
        try (var s = query("INSERT INTO control_insights_tenant_state(tenant_id,collecting_since,summaries_suspended_since,summary_rows) VALUES(?,?,?,?) "
            + "ON CONFLICT(tenant_id) DO UPDATE SET collecting_since=excluded.collecting_since,summaries_suspended_since=excluded.summaries_suspended_since,summary_rows=excluded.summary_rows",
            v.collectingSince(), v.summariesSuspendedSince(), v.summaryRows())) { s.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public int purgeFinal(long finalBefore, int limit) {
        try {
            var ids = new ArrayList<String>();
            try (var s = query("SELECT c.invocation_id FROM control_insights_checkpoints c WHERE c.tenant_id=? AND c.final_at IS NOT NULL AND c.final_at<? "
                + "AND NOT EXISTS(SELECT 1 FROM control_insights_facts f WHERE f.tenant_id=c.tenant_id AND f.invocation_id=c.invocation_id AND f.projected_at IS NULL) "
                + "ORDER BY c.final_at LIMIT ?", finalBefore, limit); var rows = s.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            for (String id : ids) {
                // Facts and checkpoint go together, so a fact never exists without the contribution it must be compared to.
                try (var s = query("DELETE FROM control_insights_facts WHERE tenant_id=? AND invocation_id=?", id)) { s.executeUpdate(); }
                try (var s = query("DELETE FROM control_insights_checkpoints WHERE tenant_id=? AND invocation_id=?", id)) { s.executeUpdate(); }
            }
            return ids.size();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public int purgeSummaries(long acceptedBefore, int limit) {
        try {
            var ids = new ArrayList<String>();
            try (var s = query("SELECT invocation_id FROM control_insights_invocations WHERE tenant_id=? AND accepted_at<? ORDER BY accepted_at LIMIT ?", acceptedBefore, limit);
                 var rows = s.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            for (String id : ids) {
                try (var s = query("DELETE FROM control_insights_attempts WHERE tenant_id=? AND invocation_id=?", id)) { s.executeUpdate(); }
                try (var s = query("DELETE FROM control_insights_invocations WHERE tenant_id=? AND invocation_id=?", id)) { s.executeUpdate(); }
            }
            return ids.size();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public int purgeCells(InsightsGranularity g, long bucketBefore, int limit) {
        // The projector never corrects a bucket past its granularity's retention, so expired cells can simply go.
        try (var s = query("DELETE FROM control_insights_cells WHERE tenant_id=? AND granularity=? AND bucket_start<?", g.name(), bucketBefore)) { return s.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public int purgeAdmissions(long bucketBefore, int limit) {
        try (var s = query("DELETE FROM control_insights_admissions WHERE tenant_id=? AND bucket_start<?", bucketBefore)) { return s.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public long countSummaries() {
        try (var s = query("SELECT count(*) FROM control_insights_invocations WHERE tenant_id=?"); var rows = s.executeQuery()) { rows.next(); return rows.getLong(1); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public Epoch epoch(String gatewayId, String epoch) {
        try (var s = query("SELECT last_sequence,missing,dropped,final,last_seen FROM control_insights_gateway_epochs WHERE tenant_id=? AND gateway_id=? AND epoch=?", gatewayId, epoch);
             var rows = s.executeQuery()) {
            return rows.next() ? new Epoch(gatewayId, epoch, rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getBoolean(4), rows.getLong(5)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void saveEpoch(Epoch e) {
        try (var s = query("INSERT INTO control_insights_gateway_epochs(tenant_id,gateway_id,epoch,last_sequence,missing,dropped,final,last_seen) VALUES(?,?,?,?,?,?,?,?) "
            + "ON CONFLICT(tenant_id,gateway_id,epoch) DO UPDATE SET last_sequence=excluded.last_sequence,missing=excluded.missing,dropped=excluded.dropped,"
            + "final=excluded.final,last_seen=excluded.last_seen", e.gatewayId(), e.epoch(), e.lastSequence(), e.missing(), e.dropped(), e.finished(), e.lastSeen())) {
            s.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void addAdmission(long bucket, String toolId, String category, long count) {
        try (var s = query("INSERT INTO control_insights_admissions(tenant_id,bucket_start,tool_id,category,count) VALUES(?,?,?,?,?) "
            + "ON CONFLICT(tenant_id,bucket_start,tool_id,category) DO UPDATE SET count=control_insights_admissions.count+excluded.count", bucket, toolId, category, count)) {
            s.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<Epoch> epochsWithGaps(long seenBefore, int limit) {
        // A gap: a skipped sequence, reported drops, or an epoch that went quiet without its final batch.
        try (var s = query("SELECT gateway_id,epoch,last_sequence,missing,dropped,final,last_seen FROM control_insights_gateway_epochs WHERE tenant_id=? "
            + "AND (missing>0 OR dropped>0 OR (NOT final AND last_seen<?)) ORDER BY last_seen DESC LIMIT ?", seenBefore, limit); var rows = s.executeQuery()) {
            var out = new ArrayList<Epoch>();
            while (rows.next()) out.add(new Epoch(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getLong(5), rows.getBoolean(6), rows.getLong(7)));
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    private static final String SPAN = "span_id,trace_id,parent_span_id,name,source,execution_id,start_at,duration_ms,status,route_id,http_status,error_code";
    public int saveSpans(String id, List<io.ololabs.toolgate.contracts.v2.InsightsSpan> spans, long now, int cap) {
        try {
            long held;
            try (var s = query("SELECT count(*) FROM control_insights_spans WHERE tenant_id=? AND invocation_id=?", id); var rows = s.executeQuery()) { rows.next(); held = rows.getLong(1); }
            int stored = 0;
            for (var span : spans) {
                if (held + stored >= cap) break;
                try (var s = query("INSERT INTO control_insights_spans(tenant_id,invocation_id," + SPAN + ",stored_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    id, span.spanId(), span.traceId(), span.parentSpanId(), span.name().name(), span.source() == null ? null : span.source().name(), span.executionId(),
                    span.startUnixMs(), span.durationMs(), span.status().name(), span.routeId(), span.httpStatus() == null ? null : span.httpStatus().intValue(), span.errorCode(), now)) {
                    stored += s.executeUpdate();
                }
            }
            return stored;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<io.ololabs.toolgate.contracts.v2.InsightsSpan> spans(String id) {
        try (var s = query("SELECT " + SPAN + " FROM control_insights_spans WHERE tenant_id=? AND invocation_id=? ORDER BY start_at,span_id LIMIT 512", id); var rows = s.executeQuery()) {
            var out = new ArrayList<io.ololabs.toolgate.contracts.v2.InsightsSpan>();
            while (rows.next()) {
                long http = rows.getLong(11); boolean noHttp = rows.wasNull();
                out.add(new io.ololabs.toolgate.contracts.v2.InsightsSpan(rows.getString(2), rows.getString(1), rows.getString(3),
                    io.ololabs.toolgate.contracts.v2.InsightsSpanName.valueOf(rows.getString(4)), io.ololabs.toolgate.contracts.v2.InsightsSpanSource.valueOf(rows.getString(5)), id,
                    rows.getString(6), rows.getLong(7), rows.getLong(8), io.ololabs.toolgate.contracts.v2.InsightsSpanStatus.valueOf(rows.getString(9)), rows.getString(10),
                    noHttp ? null : http, rows.getString(12)));
            }
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public boolean spanState(String id, String state) {
        try (var s = connection.prepareStatement("UPDATE control_insights_invocations SET span_state=? WHERE tenant_id=? AND invocation_id=?")) {
            s.setString(1, state); s.setString(2, tenant); s.setString(3, id); return s.executeUpdate() == 1;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public String spanState(String id) {
        try (var s = query("SELECT span_state FROM control_insights_invocations WHERE tenant_id=? AND invocation_id=?", id); var rows = s.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public Long claimMetrics(long through, long firstEver) {
        try {
            Long last;
            try (var s = query("SELECT metrics_exported_through FROM control_insights_tenant_state WHERE tenant_id=?"); var rows = s.executeQuery()) {
                if (!rows.next()) return null; last = optional(rows, 1);
            }
            long from = last == null ? firstEver : last;
            if (from >= through) return null;
            try (var s = connection.prepareStatement("UPDATE control_insights_tenant_state SET metrics_exported_through=? WHERE tenant_id=?")) {
                s.setLong(1, through); s.setString(2, tenant); s.executeUpdate();
            }
            return from;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public int purgeSpans(long storedBefore, int limit) {
        try (var s = query("DELETE FROM control_insights_spans WHERE tenant_id=? AND stored_at<?", storedBefore)) { return s.executeUpdate(); }
        catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public List<StoredCell> cells(String series, InsightsGranularity g, long from, long to, String dim) {
        String sql = "SELECT bucket_start,dim,outcome,calls,duration_count,duration_sum,histogram FROM control_insights_cells WHERE tenant_id=? AND series=? AND granularity=? "
            + "AND bucket_start>=? AND bucket_start<?" + (dim == null ? "" : " AND dim=?") + " ORDER BY bucket_start,dim,outcome";
        try (var s = dim == null ? query(sql, series, g.name(), from, to) : query(sql, series, g.name(), from, to, dim); var rows = s.executeQuery()) {
            var out = new ArrayList<StoredCell>();
            while (rows.next()) out.add(new StoredCell(new Insights.Cell(series, g, rows.getLong(1), rows.getString(2), rows.getString(3)),
                new Insights.CellValue(rows.getLong(4), rows.getLong(5), rows.getLong(6), histogram(rows.getString(7)))));
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public Map<String, Long> admissions(InsightsGranularity g, long from, long to) {
        try (var s = query("SELECT bucket_start,category,count FROM control_insights_admissions WHERE tenant_id=? AND bucket_start>=? AND bucket_start<?", from, to);
             var rows = s.executeQuery()) {
            var out = new TreeMap<String, Long>();
            while (rows.next()) out.merge(Insights.floor(rows.getLong(1), g) + "\t" + rows.getString(2), rows.getLong(3), Long::sum);
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<Insights.Summary> summaries(Filter f, String after, int limit) {
        var sql = new StringBuilder("SELECT " + SUMMARY + " FROM control_insights_invocations WHERE tenant_id=? AND accepted_at>=? AND accepted_at<?");
        var values = new ArrayList<Object>(List.of(f.from(), f.to()));
        if (f.toolId() != null) { sql.append(" AND tool_id=?"); values.add(f.toolId()); }
        if (f.clientId() != null) { sql.append(" AND client_id=?"); values.add(f.clientId()); }
        if (f.outcome() != null) { sql.append(" AND outcome=?"); values.add(f.outcome()); }
        if (f.search() != null) {
            sql.append(" AND (invocation_id=? OR tool_call_id=? OR session_id=? OR agent_run_id=? OR invocation_id IN("
                + "SELECT invocation_id FROM control_insights_attempts WHERE tenant_id=? AND execution_id=?))");
            values.addAll(List.of(f.search(), f.search(), f.search(), f.search(), tenant, f.search()));
        }
        if (after != null) {
            int colon = after.indexOf(':');
            if (colon < 1) throw Failure.validation();
            long accepted; try { accepted = Long.parseLong(after.substring(0, colon)); } catch (NumberFormatException invalid) { throw Failure.validation(); }
            sql.append(" AND (accepted_at<? OR (accepted_at=? AND invocation_id<?))"); values.addAll(List.of(accepted, accepted, after.substring(colon + 1)));
        }
        sql.append(" ORDER BY accepted_at DESC,invocation_id DESC LIMIT ?"); values.add(limit);
        try (var s = query(sql.toString(), values.toArray()); var rows = s.executeQuery()) {
            var out = new ArrayList<Insights.Summary>();
            while (rows.next()) out.add(summary(rows));
            for (var s2 : out) attempts(s2);
            return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public List<Insights.Fact> facts(String id) {
        try (var s = query("SELECT invocation_id,fact_seq,revision,from_state,to_state,occurred_at,execution_id,detail FROM control_insights_facts WHERE tenant_id=? AND invocation_id=? "
            + "ORDER BY fact_seq LIMIT 256", id); var rows = s.executeQuery()) {
            var out = new ArrayList<Insights.Fact>(); while (rows.next()) out.add(fact(rows)); return out;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
}
