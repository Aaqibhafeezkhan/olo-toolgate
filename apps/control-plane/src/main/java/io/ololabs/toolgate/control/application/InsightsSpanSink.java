// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.InsightsOutcome;
import io.ololabs.toolgate.contracts.v2.InsightsSpan;
import io.ololabs.toolgate.control.domain.Ids;
import java.util.*;

/** DETAILED Insights spans (ADR 031 §9): buffered per invocation in this replica's memory until the invocation's
 *  outcome is known, then kept or discarded by a rule every replica evaluates identically.
 *
 *  <p>Kept: failed, partial, unknown, cancelled after an attempt started, and slow invocations, plus a fixed share of
 *  the rest chosen by a hash of the invocation ID. An invocation still running past the slow threshold is already
 *  "slow", so its buffer is written at once and later spans go straight to the store; long runs never sit in memory.
 *  Memory is bounded by one global byte budget: under pressure the oldest buffers are decided early by the hash alone,
 *  and a discarded one is marked DROPPED. Nothing here is on a tool call's path. */
public final class InsightsSpanSink {
    public record Settings(long maxBytes, int maxSpansPerInvocation, long slowMs, double sampleRatio, long graceMs, long retentionMs) {
        public static Settings defaults() { return new Settings(64L << 20, 512, 5_000, 0.1, 600_000, 72 * 3_600_000L); }
    }
    /** Exports retained spans; the summary supplies the tool and correlation. */
    public interface Export { void spans(Ids.TenantId tenant, Insights.Summary summary, List<InsightsSpan> spans); }
    public record Stats(long buffered, long bytes, long retained, long sampledOut, long dropped) {}

    /** A span's memory estimate: the fixed-size fields dominate. */
    static final int SPAN_BYTES = 320;
    private record Key(String tenant, String invocation) {}
    private static final class Buffer {
        final List<InsightsSpan> spans = new ArrayList<>(); final long firstSeen; boolean streaming;
        /** Streamed spans already stored but not yet exported: export needs the finished summary. */
        final List<InsightsSpan> unexported = new ArrayList<>();
        Buffer(long now) { firstSeen = now; }
    }

    private final Store store; private final Settings settings; private final Export export;
    private final LinkedHashMap<Key, Buffer> buffers = new LinkedHashMap<>();
    private long bytes, retained, sampledOut, dropped;
    /** Decisions made before the projector created the summary row; applied on later passes. Bounded. */
    private final LinkedHashMap<Key, String> unrecorded = new LinkedHashMap<>();

    public InsightsSpanSink(Store store, Settings settings, Export export) { this.store = store; this.settings = settings; this.export = export; }

    /** The deterministic keep rule. Also used for early decisions, where the outcome is not known yet. */
    public boolean sampled(String invocationId) {
        long h = Integer.toUnsignedLong(invocationId.hashCode() * 0x9E3779B1);
        return h % 10_000 < Math.round(settings.sampleRatio() * 10_000);
    }
    boolean keep(Insights.Summary s) {
        var outcome = s.outcome();
        if (outcome == InsightsOutcome.FAILED || outcome == InsightsOutcome.PARTIAL || outcome == InsightsOutcome.OUTCOME_UNKNOWN) return true;
        if (outcome == InsightsOutcome.CANCELLED && !s.attempts.isEmpty()) return true;
        Long total = s.endToEndMs();
        return (total != null && total >= settings.slowMs()) || sampled(s.invocationId);
    }

    /** Adds checked spans for one invocation. Returns how many were taken; the rest exceeded the per-invocation cap. */
    public int accept(Ids.TenantId tenant, String invocationId, List<InsightsSpan> spans, long now) {
        List<InsightsSpan> writeThrough = null; int taken;
        synchronized (this) {
            var buffer = buffers.computeIfAbsent(new Key(tenant.value(), invocationId), k -> new Buffer(now));
            int room = Math.max(0, settings.maxSpansPerInvocation() - buffer.spans.size());
            taken = Math.min(room, spans.size());
            var accepted = spans.subList(0, taken);
            if (buffer.streaming) { writeThrough = List.copyOf(accepted); buffer.unexported.addAll(accepted); }
            else buffer.spans.addAll(accepted);
            bytes += (long) taken * SPAN_BYTES;
        }
        if (writeThrough != null && !writeThrough.isEmpty()) write(tenant, invocationId, writeThrough, now);
        relieve(now);
        return taken;
    }

    /** Decides buffered invocations whose outcome is now known or that turned slow; run every few seconds. */
    public void resolve(long now) {
        Map<Key, String> retry; synchronized (this) { retry = new LinkedHashMap<>(unrecorded); unrecorded.clear(); }
        retry.forEach((k, state) -> state(new Ids.TenantId(k.tenant()), k.invocation(), state));
        Map<String, List<Key>> byTenant = new TreeMap<>();
        synchronized (this) { for (var k : buffers.keySet()) byTenant.computeIfAbsent(k.tenant(), t -> new ArrayList<>()).add(k); }
        for (var entry : byTenant.entrySet()) {
            var tenant = new Ids.TenantId(entry.getKey());
            Map<String, Insights.Summary> summaries = new HashMap<>();
            try { store.transaction(tenant, false, tx -> { for (var k : entry.getValue()) summaries.put(k.invocation(), tx.insights().summary(k.invocation())); return null; }); }
            catch (RuntimeException unavailable) { continue; }
            for (var k : entry.getValue()) {
                Buffer buffer; synchronized (this) { buffer = buffers.get(k); }
                if (buffer == null) continue;
                var summary = summaries.get(k.invocation());
                boolean terminal = summary != null && Insights.terminal(summary.state);
                if (terminal) {
                    boolean keep = buffer.streaming || keep(summary);
                    finish(tenant, k, keep ? "RETAINED" : "SAMPLED_OUT", keep, summary, now);
                } else if (!buffer.streaming && now - buffer.firstSeen >= settings.slowMs()) {
                    // Still running past the slow threshold: retained whatever the outcome; stream from now on.
                    // Exported when the call finishes, with the spans that stream in until then.
                    List<InsightsSpan> flush; synchronized (this) { flush = List.copyOf(buffer.spans); buffer.unexported.addAll(flush); buffer.spans.clear(); buffer.streaming = true; }
                    write(tenant, k.invocation(), flush, now); state(tenant, k.invocation(), "RETAINED");
                } else if (now - buffer.firstSeen >= settings.graceMs()) {
                    finish(tenant, k, "RETAINED", true, summary, now); // never finished in time: kept as incomplete
                }
            }
        }
    }

    private void finish(Ids.TenantId tenant, Key k, String state, boolean keep, Insights.Summary summary, long now) {
        List<InsightsSpan> spans, streamed;
        synchronized (this) {
            var b = buffers.remove(k); if (b == null) return; spans = b.spans; streamed = b.unexported;
            bytes -= (long) (spans.size() + streamed.size()) * SPAN_BYTES; if (keep) retained++; else sampledOut++;
        }
        if (keep && !spans.isEmpty()) write(tenant, k.invocation(), spans, now);
        if (keep && summary != null && spans.size() + streamed.size() > 0) {
            var all = new ArrayList<>(streamed); all.addAll(spans); export.spans(tenant, summary, all);
        }
        state(tenant, k.invocation(), state);
    }

    /** Over budget: decide the oldest buffers now, by the hash alone. Streamed spans waiting for export go first. */
    private void relieve(long now) {
        while (true) {
            Key oldest; Buffer buffer;
            synchronized (this) {
                if (bytes <= settings.maxBytes()) return;
                var waiting = buffers.values().stream().filter(b -> !b.unexported.isEmpty()).findFirst().orElse(null);
                if (waiting != null) { bytes -= (long) waiting.unexported.size() * SPAN_BYTES; waiting.unexported.clear(); continue; } // stored, not exported
                var first = buffers.entrySet().stream().filter(e -> !e.getValue().spans.isEmpty()).findFirst().orElse(null);
                if (first == null) return;
                oldest = first.getKey(); buffer = first.getValue();
            }
            var tenant = new Ids.TenantId(oldest.tenant());
            if (sampled(oldest.invocation())) {
                List<InsightsSpan> flush; synchronized (this) { flush = List.copyOf(buffer.spans); bytes -= (long) buffer.spans.size() * SPAN_BYTES; buffer.spans.clear(); buffer.streaming = true; }
                write(tenant, oldest.invocation(), flush, now); state(tenant, oldest.invocation(), "RETAINED");
            } else {
                synchronized (this) { buffers.remove(oldest); bytes -= (long) buffer.spans.size() * SPAN_BYTES; dropped++; }
                state(tenant, oldest.invocation(), "DROPPED");
            }
        }
    }

    private void write(Ids.TenantId tenant, String invocation, List<InsightsSpan> spans, long now) {
        try { store.transaction(tenant, true, tx -> tx.insights().saveSpans(invocation, spans, now, settings.maxSpansPerInvocation())); }
        catch (RuntimeException unavailable) { synchronized (this) { dropped++; } }
    }
    private void state(Ids.TenantId tenant, String invocation, String state) {
        boolean recorded;
        try { recorded = store.transaction(tenant, true, tx -> tx.insights().spanState(invocation, state)); } catch (RuntimeException unavailable) { recorded = false; }
        if (!recorded) synchronized (this) {
            unrecorded.put(new Key(tenant.value(), invocation), state);
            if (unrecorded.size() > 10_000) unrecorded.remove(unrecorded.keySet().iterator().next());
        }
    }

    public synchronized Stats stats() { return new Stats(buffers.size(), bytes, retained, sampledOut, dropped); }
    public Settings settings() { return settings; }
}
