// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Operator Insights rows of one tenant, inside the caller's tenant transaction (ADR 031). Facts are written by the
 *  ledger's own transaction; everything else belongs to the projector, retention and the operator queries. */
public interface InsightsStore {
    record Checkpoint(String invocationId, long lastSeq, String contribution, Long finalAt) {}
    record Filter(long from, long to, String toolId, String clientId, String outcome, String search) {}
    record Epoch(String gatewayId, String epoch, long lastSequence, long missing, long dropped, boolean finished, long lastSeen) {}
    record TenantState(long collectingSince, Long summariesSuspendedSince, long summaryRows) {}
    record Backlog(long facts, Long oldest) {}
    record StoredCell(Insights.Cell cell, Insights.CellValue value) {}
    record SessionRow(String sessionId, long firstAccepted, long lastActivity, long calls, long succeeded, long failed, long unfinished,
                      long agentRuns, long tools) {}

    // Capture (inside the ledger transaction)
    void insertFact(Insights.Fact fact);
    /** Merges the caller's declared correlation into the invocation's first fact. */
    void declare(String invocationId, String contextJson);

    // Projection
    List<Insights.Fact> unprojected(int limit);
    Backlog backlog();
    Checkpoint checkpoint(String invocationId);
    void saveCheckpoint(Checkpoint checkpoint);
    Insights.Summary summary(String invocationId);
    void saveSummary(Insights.Summary summary, boolean newRow);
    void markProjected(String invocationId, long throughSeq, long now);
    Insights.CellValue cell(Insights.Cell cell);
    void saveCell(Insights.Cell cell, Insights.CellValue value);
    TenantState state();
    void saveState(TenantState state);

    // Retention
    /** Deletes facts and checkpoints of invocations final before cutoff whose facts are all projected. */
    int purgeFinal(long finalBefore, int limit);
    int purgeSummaries(long acceptedBefore, int limit);
    int purgeCells(InsightsGranularity granularity, long bucketBefore, int limit);
    int purgeAdmissions(long bucketBefore, int limit);
    long countSummaries();

    // Admission counters
    Epoch epoch(String gatewayId, String epoch);
    void saveEpoch(Epoch epoch);
    void addAdmission(long bucket, String toolId, String category, long count);
    List<Epoch> epochsWithGaps(long seenBefore, int limit);

    // Spans (DETAILED)
    /** Stores retained spans, at most {@code cap} per invocation; returns how many were stored. */
    int saveSpans(String invocationId, List<io.ololabs.toolgate.contracts.v2.InsightsSpan> spans, long now, int cap);
    List<io.ololabs.toolgate.contracts.v2.InsightsSpan> spans(String invocationId);
    /** RETAINED, SAMPLED_OUT or DROPPED on the invocation's summary; null when undecided or never collected. */
    /** Returns false when the summary row does not exist yet. */
    boolean spanState(String invocationId, String state);
    String spanState(String invocationId);
    int purgeSpans(long storedBefore, int limit);
    /** Claims the next closed minutes for metric export, up to {@code through}; returns the first unclaimed bucket
     *  start, or null when the tenant has nothing to claim. At most once: a claimed minute is never offered again. */
    Long claimMetrics(long through, long firstEver);

    // Queries
    List<StoredCell> cells(String series, InsightsGranularity granularity, long from, long to, String dim);
    Map<String, Long> admissions(InsightsGranularity granularity, long from, long to);
    List<Insights.Summary> summaries(Filter filter, String afterCursor, int limit);
    List<Insights.Fact> facts(String invocationId);
    /** Sessions with calls accepted in [from, to), most recent activity first; afterCursor is {@code lastActivity:sessionId}. */
    List<SessionRow> sessions(long from, long to, String afterCursor, int limit);
    long callsWithoutSession(long from, long to);
    /** One session's summaries, oldest first. */
    List<Insights.Summary> session(String sessionId, int limit);
    /** Summaries that declared one of {@code parents} as their parent, oldest first. */
    List<Insights.Summary> children(Collection<String> parents, int limit);
}
