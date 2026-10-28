// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsSpan;
import io.ololabs.toolgate.contracts.v2.InsightsSpanName;
import io.ololabs.toolgate.control.application.Insights;
import io.ololabs.toolgate.control.application.InsightsSpanSink;
import io.ololabs.toolgate.control.application.Store;
import io.ololabs.toolgate.control.domain.Ids;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/** Operator Insights export over OTLP/HTTP JSON (ADR 031 §6), separate from platform tracing and its redaction: only
 *  the allowlisted span fields and aggregate metrics leave Control, to one configured endpoint (normally a Collector
 *  that fans out to Datadog, New Relic or both). Asynchronous and bounded: a slow or absent endpoint delays and then
 *  drops export, and never affects tool calls, projection or the local Insights store. */
public final class InsightsOtlpExporter implements InsightsSpanSink.Export {
    public record Settings(URI endpoint, String serviceName, String environment, int maxQueuedSpans, Duration timeout) {}
    static final int BATCH = 512;
    private final Settings settings; private final HttpClient http;
    private final ArrayDeque<ObjectNode> queue = new ArrayDeque<>();
    private final AtomicLong exported = new AtomicLong(), dropped = new AtomicLong(), failures = new AtomicLong();
    private long backoffUntil, backoff = 1_000;

    public InsightsOtlpExporter(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public long exported() { return exported.get(); }
    public long dropped() { return dropped.get(); }
    public long failures() { return failures.get(); }

    /** Queues retained spans; when the queue is full the oldest are dropped and counted. */
    @Override public void spans(Ids.TenantId tenant, Insights.Summary summary, List<InsightsSpan> spans) {
        synchronized (queue) {
            for (var span : spans) {
                if (queue.size() >= settings.maxQueuedSpans()) { queue.pollFirst(); dropped.incrementAndGet(); }
                queue.addLast(span(tenant, summary, span));
            }
        }
    }

    /** Sends one batch of queued spans; called by the Insights worker. Failed batches stay queued with backoff. */
    public void flush(long now) {
        if (now < backoffUntil) return;
        List<ObjectNode> batch = new ArrayList<>();
        synchronized (queue) { var it = queue.iterator(); while (it.hasNext() && batch.size() < BATCH) batch.add(it.next()); }
        if (batch.isEmpty()) return;
        var body = envelope("resourceSpans", "spans", batch);
        if (post("/v1/traces", body)) {
            synchronized (queue) { for (int i = 0; i < batch.size() && !queue.isEmpty(); i++) queue.pollFirst(); }
            exported.addAndGet(batch.size()); backoff = 1_000;
        } else { failures.incrementAndGet(); backoffUntil = now + backoff; backoff = Math.min(backoff * 2, 60_000); }
    }

    /** Exports each tenant's closed minutes once (at most once across replicas: a tenant claims the minutes under its
     *  own lock before they are sent). Corrections that arrive after a minute was exported are not re-sent. */
    public int metrics(Store store, BiFunction<String, Integer, List<Ids.TenantId>> tenants, long now) {
        long minute = Insights.width(InsightsGranularity.MINUTE), through = Insights.floor(now, InsightsGranularity.MINUTE) - minute; // one minute of lateness
        int sent = 0; String cursor = "";
        while (true) {
            var page = tenants.apply(cursor, 256);
            for (var tenant : page) {
                try {
                    var claimed = store.transaction(tenant, true, tx -> {
                        Long from = tx.insights().claimMetrics(through, through - minute);
                        if (from == null) return null;
                        long start = Math.max(from, through - 60 * minute); // never backfill more than an hour after an outage
                        return new Object[]{start, tx.insights().cells("TOOL", InsightsGranularity.MINUTE, start, through, null), tx.insights().admissions(InsightsGranularity.MINUTE, start, through)};
                    });
                    if (claimed == null) continue;
                    @SuppressWarnings("unchecked") var cells = (List<io.ololabs.toolgate.control.application.InsightsStore.StoredCell>) claimed[1];
                    @SuppressWarnings("unchecked") var admissions = (Map<String, Long>) claimed[2];
                    if (cells.isEmpty() && admissions.isEmpty()) continue;
                    if (post("/v1/metrics", metrics(tenant, cells, admissions))) sent++; else { failures.incrementAndGet(); dropped.incrementAndGet(); }
                } catch (RuntimeException unavailable) { failures.incrementAndGet(); }
            }
            if (page.size() < 256) return sent;
            cursor = page.get(page.size() - 1).value();
        }
    }

    ObjectNode span(Ids.TenantId tenant, Insights.Summary summary, InsightsSpan s) {
        var node = Insights.JSON.createObjectNode();
        node.put("traceId", s.traceId()).put("spanId", s.spanId());
        if (s.parentSpanId() != null) node.put("parentSpanId", s.parentSpanId());
        boolean execute = s.name() == InsightsSpanName.HOST_EXECUTE || s.name() == InsightsSpanName.CLIENT_EXECUTE;
        // OpenTelemetry GenAI conventions name a tool execution span "execute_tool {gen_ai.tool.name}".
        node.put("name", execute ? "execute_tool " + summary.toolId : "toolgate." + s.name().name().toLowerCase(Locale.ROOT));
        node.put("kind", switch (s.name()) { case GATEWAY_REQUEST -> 2; case HOST_EGRESS, GATEWAY_AUTHORIZE, GATEWAY_DISPATCH -> 3; default -> 1; });
        long start = s.startUnixMs() * 1_000_000L;
        node.put("startTimeUnixNano", Long.toString(start)).put("endTimeUnixNano", Long.toString(start + s.durationMs() * 1_000_000L));
        var attributes = node.putArray("attributes");
        attribute(attributes, "toolgate.tenant.id", tenant.value());
        attribute(attributes, "toolgate.invocation.id", s.invocationId());
        attribute(attributes, "toolgate.span.source", s.source() == null ? null : s.source().name());
        attribute(attributes, "toolgate.execution.id", s.executionId());
        attribute(attributes, "toolgate.route.id", s.routeId());
        attribute(attributes, "error.type", s.errorCode());
        if (s.httpStatus() != null) attributes.addObject().put("key", "http.response.status_code").putObject("value").put("intValue", Long.toString(s.httpStatus()));
        if (execute) {
            attribute(attributes, "gen_ai.operation.name", "execute_tool");
            attribute(attributes, "gen_ai.tool.name", summary.toolId);
            attribute(attributes, "gen_ai.tool.call.id", summary.toolCallId);
        }
        node.putObject("status").put("code", s.status().name().equals("ERROR") ? 2 : 1);
        return node;
    }

    ObjectNode metrics(Ids.TenantId tenant, List<io.ololabs.toolgate.control.application.InsightsStore.StoredCell> cells, Map<String, Long> admissions) {
        var metrics = new ArrayList<ObjectNode>(); long minute = Insights.width(InsightsGranularity.MINUTE);
        var calls = Insights.JSON.createObjectNode().put("name", "toolgate.insights.calls").put("unit", "{call}")
            .put("description", "Logical tool calls by acceptance minute, tool and current outcome");
        var callSum = calls.putObject("sum").put("aggregationTemporality", 1).put("isMonotonic", true); var callPoints = callSum.putArray("dataPoints");
        var duration = Insights.JSON.createObjectNode().put("name", "toolgate.insights.duration").put("unit", "ms")
            .put("description", "Execution duration of finished calls");
        var histogram = duration.putObject("histogram").put("aggregationTemporality", 1); var durationPoints = histogram.putArray("dataPoints");
        for (var stored : cells) {
            var c = stored.cell(); var v = stored.value();
            String start = Long.toString(c.bucket() * 1_000_000L), end = Long.toString((c.bucket() + minute) * 1_000_000L);
            var p = callPoints.addObject().put("startTimeUnixNano", start).put("timeUnixNano", end).put("asInt", Long.toString(v.calls()));
            labels(p.putArray("attributes"), tenant, c.dim(), c.outcome());
            if (v.durationCount() > 0) {
                var h = durationPoints.addObject().put("startTimeUnixNano", start).put("timeUnixNano", end)
                    .put("count", Long.toString(v.durationCount())).put("sum", (double) v.durationSum());
                var counts = h.putArray("bucketCounts"); for (long n : v.histogram()) counts.add(Long.toString(n));
                var bounds = h.putArray("explicitBounds"); for (long b : Insights.BOUNDS_MS) bounds.add((double) b);
                labels(h.putArray("attributes"), tenant, c.dim(), c.outcome());
            }
        }
        metrics.add(calls); if (!durationPoints.isEmpty()) metrics.add(duration);
        if (!admissions.isEmpty()) {
            var denied = Insights.JSON.createObjectNode().put("name", "toolgate.insights.admissions").put("unit", "{request}")
                .put("description", "Requests the Gateway answered without starting an execution");
            var points = denied.putObject("sum").put("aggregationTemporality", 1).put("isMonotonic", true).putArray("dataPoints");
            admissions.forEach((key, count) -> {
                String[] k = key.split("\t"); long bucket = Long.parseLong(k[0]);
                var p = points.addObject().put("startTimeUnixNano", Long.toString(bucket * 1_000_000L)).put("timeUnixNano", Long.toString((bucket + minute) * 1_000_000L))
                    .put("asInt", Long.toString(count));
                var a = p.putArray("attributes"); attribute(a, "toolgate.tenant.id", tenant.value()); attribute(a, "toolgate.admission.category", k[1]);
            });
            metrics.add(denied);
        }
        return envelope("resourceMetrics", "metrics", metrics);
    }
    private static void labels(ArrayNode a, Ids.TenantId tenant, String tool, String outcome) {
        attribute(a, "toolgate.tenant.id", tenant.value()); attribute(a, "gen_ai.tool.name", tool); attribute(a, "toolgate.outcome", outcome);
    }
    private static void attribute(ArrayNode attributes, String key, String value) {
        if (value != null && !value.isEmpty()) attributes.addObject().put("key", key).putObject("value").put("stringValue", value);
    }
    private ObjectNode envelope(String resourceField, String itemsField, List<ObjectNode> items) {
        var root = Insights.JSON.createObjectNode(); var resource = root.putArray(resourceField).addObject();
        var attributes = resource.putObject("resource").putArray("attributes");
        attribute(attributes, "service.name", settings.serviceName()); attribute(attributes, "deployment.environment.name", settings.environment());
        var scope = resource.putArray(resourceField.equals("resourceSpans") ? "scopeSpans" : "scopeMetrics").addObject();
        scope.putObject("scope").put("name", "io.ololabs.toolgate.insights").put("version", "1");
        var out = scope.putArray(itemsField); items.forEach(out::add);
        return root;
    }
    private boolean post(String path, ObjectNode body) {
        try {
            var request = HttpRequest.newBuilder(settings.endpoint().resolve(path)).timeout(settings.timeout())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (java.io.IOException failure) { return false; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
}
