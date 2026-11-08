// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsMode;
import io.ololabs.toolgate.contracts.v2.InsightsSeries;
import io.ololabs.toolgate.contracts.v2.InsightsInvocationPage;

/** Tenant Admin analytics. Reads only tenant-tagged cells and admissions, never installation-wide status.
 * The verified actor is the only source of tenant identity; no caller-selected tenant or global fallback exists. */
public final class TenantAnalytics {
    private final Store store;
    private final Codec codec;
    private final InsightsService insights;
    public TenantAnalytics(Store store, Codec codec, InsightsService insights) {
        this.store = store; this.codec = codec; this.insights = insights;
    }
    /** ADR 060: Control-derived evidence grades, separate from Gateway-recorded call states. */
    public record Evidence(java.util.Map<String, Long> grades, java.util.Map<String, Long> coverage,
                           long fencedEffects, long unfencedEffects, long operations, boolean truncated) {}
    public record Overview(String tenantId, InsightsMode mode, InsightsSeries series, InsightsSeries tools, InsightsInvocationPage invocations,
                           Evidence evidence) {}
    public static final int EVIDENCE_LIMIT = 10_000;
    public Overview overview(DirectoryService.Actor actor, InsightsMode mode, long from, long to, long now) {
        return overview(actor, mode, from, to, now, null, null);
    }
    public Overview overview(DirectoryService.Actor actor, InsightsMode mode, long from, long to, long now, String tool, String after) {
        actor.requireAdmin();
        store.transaction(actor.tenant(), false, tx -> {
            new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "audit.read", now);
            return null;
        });
        // The window end comes from the viewer's clock, which may run ahead of the server's; clamp rather than reject.
        to = Math.min(to, now);
        if (from < 0 || to <= from || to - from > 31L * 86_400_000) throw Failure.validation();
        if (tool != null && (tool.isBlank() || tool.length() > 256) || after != null && tool == null) throw Failure.validation();
        var granularity = to - from <= 48L * 3_600_000 ? InsightsGranularity.HOUR : InsightsGranularity.DAY;
        String tenant = actor.tenant().value();
        long start = from, end = to;
        var outcomes = store.transaction(actor.tenant(), false, tx -> tx.enterprise().latestOutcomes(start, end + 1, EVIDENCE_LIMIT + 1));
        return new Overview(tenant, mode,
            insights.series(granularity, from, to, null, tenant, tool, null, now),
            insights.series(granularity, from, to, "TOOL", tenant, null, null, now),
            tool == null ? null : insights.invocations(tenant, from, to, tool, null, null, null, after, 50),
            evidence(outcomes));
    }
    public static Evidence evidence(java.util.List<io.ololabs.toolgate.contracts.EnterpriseOperationOutcome> outcomes) {
        boolean truncated = outcomes.size() > EVIDENCE_LIMIT;
        var grades = new java.util.TreeMap<String, Long>();var coverage = new java.util.TreeMap<String, Long>();
        for (var grade : io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade.values()) grades.put(grade.name(), 0L);
        for (var value : io.ololabs.toolgate.contracts.EnterpriseExecutionCoverage.values()) coverage.put(value.name(), 0L);
        long fenced = 0, unfenced = 0, count = 0;
        for (var outcome : outcomes.subList(0, Math.min(outcomes.size(), EVIDENCE_LIMIT))) {
            grades.merge(outcome.grade().name(), 1L, Long::sum);coverage.merge(outcome.coverage().name(), 1L, Long::sum);
            fenced += outcome.fencedEffectCount();unfenced += outcome.unfencedEffectCount();count++;
        }
        return new Evidence(grades, coverage, fenced, unfenced, count, truncated);
    }
}
