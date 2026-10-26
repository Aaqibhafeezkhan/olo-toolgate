// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsMode;
import io.ololabs.toolgate.contracts.v2.InsightsSeries;

/** Tenant Admin analytics. Reads only tenant-tagged cells and admissions, never installation-wide status.
 * The verified actor is the only source of tenant identity; no caller-selected tenant or global fallback exists. */
public final class TenantAnalytics {
    private final Store store;
    private final Codec codec;
    private final InsightsService insights;
    public TenantAnalytics(Store store, Codec codec, InsightsService insights) {
        this.store = store; this.codec = codec; this.insights = insights;
    }
    public record Overview(String tenantId, InsightsMode mode, InsightsSeries series, InsightsSeries tools) {}
    public Overview overview(DirectoryService.Actor actor, InsightsMode mode, long from, long to, long now) {
        actor.requireAdmin();
        store.transaction(actor.tenant(), false, tx -> {
            new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "audit.read", now);
            return null;
        });
        if (from < 0 || to <= from || to > now || to - from > 31L * 86_400_000) throw Failure.validation();
        var granularity = to - from <= 48L * 3_600_000 ? InsightsGranularity.HOUR : InsightsGranularity.DAY;
        String tenant = actor.tenant().value();
        return new Overview(tenant, mode,
            insights.series(granularity, from, to, null, tenant, null, null, now),
            insights.series(granularity, from, to, "TOOL", tenant, null, null, now));
    }
}
