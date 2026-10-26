// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.InsightsMode;
import io.ololabs.toolgate.control.application.InsightsProjector;
import io.ololabs.toolgate.control.application.InsightsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.concurrent.*;
import org.eclipse.microprofile.config.Config;

/** Runs the Operator Insights projector and retention (ADR 031). Off unless TOOLGATE_INSIGHTS_MODE is BASIC or
 *  DETAILED. Each tenant is projected in its own tenant transaction, so replicas never project one tenant at once and
 *  a failure in one tenant never stops the others; nothing here can delay or fail a tool invocation. */
@ApplicationScoped
public class InsightsWatchdog {
    @Inject PostgresStore store; @Inject TenantRegistry registry; @Inject Config config;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;
    private ScheduledExecutorService executor;
    private InsightsService service;
    private TenantSweep sweep;
    private volatile long lastRetention;
    private final java.util.Map<String, Long> reconciled = new java.util.concurrent.ConcurrentHashMap<>();

    static InsightsProjector.Settings settings(Config config) {
        var d = InsightsProjector.Settings.defaults(); long day = 86_400_000L;
        return new InsightsProjector.Settings(
            config.getOptionalValue("toolgate.control.insights.batch", Integer.class).orElse(d.batch()),
            days(config, "fact-retention-days", d.factRetentionMs(), 0, 30), days(config, "summary-retention-days", d.summaryRetentionMs(), 1, 90),
            config.getOptionalValue("toolgate.control.insights.minute-retention-hours", Long.class).map(h -> h * 3_600_000L).orElse(d.minuteRetentionMs()),
            days(config, "hour-retention-days", d.hourRetentionMs(), 1, 400), days(config, "day-retention-days", d.dayRetentionMs(), 1, 800),
            config.getOptionalValue("toolgate.control.insights.max-summary-rows", Long.class).orElse(d.maxSummaryRows()));
    }
    private static long days(Config config, String key, long otherwise, int min, int max) {
        var value = config.getOptionalValue("toolgate.control.insights." + key, Integer.class);
        if (value.isPresent() && (value.get() < min || value.get() > max)) throw new IllegalStateException("toolgate.control.insights." + key + " must be " + min + ".." + max);
        return value.map(v -> v * 86_400_000L).orElse(otherwise);
    }

    @Produces @ApplicationScoped InsightsService service() {
        var settings = settings(config);
        if (settings.factRetentionMs() > settings.summaryRetentionMs()) throw new IllegalStateException("Insights fact retention cannot exceed summary retention");
        return new InsightsService(store, settings, registry::activeAfter, store::insightsMode);
    }

    private InsightsOtlpExporter exporter;
    private volatile long lastMetrics;

    void start(@Observes StartupEvent event, InsightsService service) {
        if (store.insightsMode() == InsightsMode.NONE) return;
        this.service = service;
        var endpoint = config.getOptionalValue("toolgate.control.insights.otlp.endpoint", String.class).filter(s -> !s.isBlank());
        if (endpoint.isPresent()) {
            var uri = java.net.URI.create(endpoint.get().endsWith("/") ? endpoint.get() : endpoint.get() + "/");
            if (!uri.getScheme().equals("https") && !uri.getScheme().equals("http")) throw new IllegalStateException("toolgate.control.insights.otlp.endpoint must be http(s)");
            exporter = new InsightsOtlpExporter(new InsightsOtlpExporter.Settings(uri,
                config.getOptionalValue("toolgate.control.insights.otlp.service-name", String.class).orElse("olo-toolgate"),
                config.getOptionalValue("toolgate.control.insights.otlp.environment", String.class).orElse(null), 20_000, java.time.Duration.ofSeconds(10)));
            metrics.gauge("toolgate_control_insights_export_dropped", exporter, InsightsOtlpExporter::dropped);
            metrics.gauge("toolgate_control_insights_export_failures", exporter, InsightsOtlpExporter::failures);
        }
        if (store.insightsMode() == InsightsMode.DETAILED) {
            var d = io.ololabs.toolgate.control.application.InsightsSpanSink.Settings.defaults();
            var sink = new io.ololabs.toolgate.control.application.InsightsSpanSink(store, new io.ololabs.toolgate.control.application.InsightsSpanSink.Settings(
                config.getOptionalValue("toolgate.control.insights.spans.buffer-mib", Long.class).map(m -> m << 20).orElse(d.maxBytes()), d.maxSpansPerInvocation(),
                config.getOptionalValue("toolgate.control.insights.spans.slow-ms", Long.class).orElse(d.slowMs()),
                config.getOptionalValue("toolgate.control.insights.spans.sample-ratio", Double.class).orElse(d.sampleRatio()), d.graceMs(),
                config.getOptionalValue("toolgate.control.insights.spans.retention-hours", Long.class).map(h -> h * 3_600_000L).orElse(d.retentionMs())),
                exporter == null ? (tenant, summary, spans) -> { } : exporter);
            if (sink.settings().sampleRatio() < 0 || sink.settings().sampleRatio() > 1) throw new IllegalStateException("toolgate.control.insights.spans.sample-ratio must be 0..1");
            service.spans(sink);
            metrics.gauge("toolgate_control_insights_span_buffer_bytes", sink, s -> s.stats().bytes());
        }
        long interval = config.getOptionalValue("toolgate.control.insights.interval-ms", Long.class).orElse(2_000L);
        sweep = new TenantSweep(32, registry::activeAfter, this::tenant);
        executor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "toolgate-insights"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }
    private void tick() {
        long now = System.currentTimeMillis();
        try {
            var sink = service.spans();
            if (sink != null) sink.resolve(now);
            if (exporter != null) {
                exporter.flush(now);
                if (now - lastMetrics >= 60_000) { lastMetrics = now; exporter.metrics(store, registry::activeAfter, now); }
            }
        } catch (RuntimeException failed) { metrics.counter("toolgate_control_insights_export_cycles_total", "result", "failed").increment(); }
        try {
            var result = sweep.tick();
            if (result.cycleComplete() && System.currentTimeMillis() - lastRetention >= 60_000) lastRetention = System.currentTimeMillis();
            if (result.failed() > 0) metrics.counter("toolgate_control_insights_tenant_failures_total").increment(result.failed());
        } catch (RuntimeException unavailable) { metrics.counter("toolgate_control_insights_sweeps_total", "result", "failed").increment(); }
    }
    private void tenant(io.ololabs.toolgate.control.domain.Ids.TenantId tenant) {
        var projector = service.projector(); int batch = settings(config).batch();
        // Drain a bounded backlog per tick; a larger one continues next tick and is reported as lag.
        for (int round = 0; round < 10; round++) {
            var result = projector.project(tenant, System.currentTimeMillis());
            metrics.counter("toolgate_control_insights_facts_projected_total").increment(result.facts());
            if (result.gaps() > 0) metrics.counter("toolgate_control_insights_sequence_gaps_total").increment(result.gaps());
            if (result.drift() > 0) metrics.counter("toolgate_control_insights_cell_drift_total").increment(result.drift());
            if (result.facts() < batch) break;
        }
        long now = System.currentTimeMillis();
        if (now - lastRetention >= 60_000) {
            metrics.counter("toolgate_control_insights_rows_purged_total").increment(projector.retain(tenant, now));
            var sink = service.spans();
            if (sink != null) store.transaction(tenant, true, tx -> tx.insights().purgeSpans(now - sink.settings().retentionMs(), 10_000));
        }
        // Hourly, compare today's live aggregates with a recomputation from summaries. Drift is reported, never repaired
        // silently: an operator decides whether to apply a rebuild.
        long every = config.getOptionalValue("toolgate.control.insights.reconcile-interval-minutes", Long.class).orElse(60L) * 60_000L;
        if (every > 0 && now - reconciled.getOrDefault(tenant.value(), 0L) >= every) {
            reconciled.put(tenant.value(), now);
            try {
                var check = projector.rebuild(tenant, now - 1, now, true, now);
                if (check.cellsDiffering() > 0) {
                    metrics.counter("toolgate_control_insights_reconciliation_drift_total").increment(check.cellsDiffering());
                    org.jboss.logging.Logger.getLogger(InsightsWatchdog.class).warnf("insights_reconciliation_drift tenant_id=%s cells=%d of=%d",
                        tenant.value(), check.cellsDiffering(), check.cellsCompared());
                }
            } catch (io.ololabs.toolgate.control.application.Failure skipped) { /* over quota: summaries are incomplete */ }
        }
    }
    @PreDestroy void stop() { if (executor != null) executor.shutdownNow(); }
}
