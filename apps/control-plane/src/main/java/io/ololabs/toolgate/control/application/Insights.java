// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.InsightsErrorCategory;
import io.ololabs.toolgate.contracts.v2.InsightsGranularity;
import io.ololabs.toolgate.contracts.v2.InsightsMode;
import io.ololabs.toolgate.contracts.v2.InsightsOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Operator Insights projection rules (ADR 031): how transition facts fold into an invocation summary and which aggregate
 *  cells an invocation contributes to. Pure functions; storage is {@link InsightsStore}. */
public final class Insights {
    private Insights() {}
    public static final ObjectMapper JSON = new ObjectMapper();

    /** Upper bounds (ms) of the fixed duration histogram buckets; one more bucket holds everything above the last. */
    public static final long[] BOUNDS_MS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 30_000, 60_000, 120_000,
        300_000, 600_000, 1_800_000, 3_600_000};
    public static final int BUCKETS = BOUNDS_MS.length + 1;
    public static final List<String> SERIES = List.of("TOOL", "CLIENT");

    public static InsightsMode mode(String value) {
        try { return InsightsMode.valueOf(value == null || value.isBlank() ? "NONE" : value.trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("TOOLGATE_INSIGHTS_MODE must be NONE, BASIC or DETAILED"); }
    }

    public static int histogramIndex(long ms) {
        for (int i = 0; i < BOUNDS_MS.length; i++) if (ms <= BOUNDS_MS[i]) return i;
        return BOUNDS_MS.length;
    }
    public static long width(InsightsGranularity g) {
        return switch (g) { case MINUTE -> 60_000L; case HOUR -> 3_600_000L; case DAY -> 86_400_000L; };
    }
    public static long floor(long time, InsightsGranularity g) { return Math.floorDiv(time, width(g)) * width(g); }

    static boolean terminal(String state) {
        return switch (state) { case "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "PARTIAL", "OUTCOME_UNKNOWN" -> true; default -> false; };
    }
    /** Final means nothing can change the outcome again: terminal and not awaiting reconciliation (T13). */
    static boolean finalState(String state) { return terminal(state) && !state.equals("OUTCOME_UNKNOWN"); }

    static InsightsOutcome outcome(String state) {
        return switch (state) {
            case "PENDING_APPROVAL" -> InsightsOutcome.PENDING_APPROVAL;
            case "SUCCEEDED" -> InsightsOutcome.SUCCEEDED;
            case "FAILED" -> InsightsOutcome.FAILED;
            case "PARTIAL" -> InsightsOutcome.PARTIAL;
            case "CANCELLED" -> InsightsOutcome.CANCELLED;
            case "EXPIRED" -> InsightsOutcome.EXPIRED;
            case "OUTCOME_UNKNOWN" -> InsightsOutcome.OUTCOME_UNKNOWN;
            default -> InsightsOutcome.IN_PROGRESS;
        };
    }

    /** ADR 031 §7. A FAILED fact carries the cause the ledger row showed when it was written. */
    static InsightsErrorCategory errorCategory(String state, String cause) {
        return switch (state) {
            case "FAILED" -> "NOT_STARTED".equals(cause) ? InsightsErrorCategory.INFRASTRUCTURE
                : "LEASE_EXPIRED".equals(cause) ? InsightsErrorCategory.TIMEOUT : InsightsErrorCategory.TOOL_ERROR;
            case "EXPIRED" -> InsightsErrorCategory.TIMEOUT;
            case "CANCELLED" -> InsightsErrorCategory.CANCELLED;
            case "PARTIAL" -> InsightsErrorCategory.PARTIAL;
            case "OUTCOME_UNKNOWN" -> InsightsErrorCategory.UNKNOWN;
            default -> null;
        };
    }

    /** The execution ID of an attempt (ADR 031 §1): a one-way digest, so the fencing token never reaches Insights. */
    public static String executionId(String tenant, String invocation, String fencingToken) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String[] parts = {"toolgate-execution-v1", tenant, invocation, fencingToken};
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) digest.update((byte) 0);
                digest.update(parts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return "ex-" + java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** One stored transition fact. detail is a small JSON object (identity on the first fact, cause on failures). */
    public record Fact(String invocationId, long seq, long revision, String fromState, String toState, long occurredAt,
                       String executionId, String detail) {}

    /** The mutable projection of one invocation; persisted as a summary row plus attempt rows. */
    public static final class Summary {
        public String invocationId, toolId = "", action = "", clientId, userId, agentId, toolCallId, sessionId, agentRunId, parentInvocationId;
        public String state, cause;
        public long acceptedAt;
        public Long approvalRequestedAt, approvalResolvedAt, queuedAt, firstStartedAt, terminalAt;
        public boolean partialCoverage;
        public final List<Attempt> attempts = new ArrayList<>();

        public InsightsOutcome outcome() { return Insights.outcome(state); }
        public InsightsErrorCategory errorCategory() { return Insights.errorCategory(state, cause); }
        public Long approvalMs() { return span(approvalRequestedAt, approvalResolvedAt); }
        public Long queueMs() { return span(queuedAt, firstStartedAt); }
        /** The attempt that produced the outcome; earlier lost attempts are in {@link #attempts}. */
        public Long executionMs() { return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).durationMs(); }
        public Long endToEndMs() { return partialCoverage ? null : span(acceptedAt, terminalAt); }
        private static Long span(Long from, Long to) { return from == null || to == null || to < from ? null : to - from; }
    }

    public static final class Attempt {
        public int number; public String executionId, executorId; public Long startedAt, endedAt; public InsightsOutcome outcome;
        public Long durationMs() { return startedAt == null || endedAt == null || endedAt < startedAt ? null : endedAt - startedAt; }
    }

    /** Applies one fact, in fact_seq order. The first fact fixes identity and acceptance; a first fact that is not T1
     *  (collection started mid-flight) marks the summary as partial coverage. */
    public static void apply(Summary s, Fact f) {
        JsonNode detail = parse(f.detail());
        if (s.state == null) {
            s.invocationId = f.invocationId();
            s.acceptedAt = f.occurredAt();
            s.partialCoverage = f.fromState() != null;
        }
        if (detail.has("toolId")) {
            s.toolId = text(detail, "toolId", ""); s.action = text(detail, "action", "");
            s.clientId = text(detail, "clientId", null); s.userId = text(detail, "userId", null); s.agentId = text(detail, "agentId", null);
        }
        JsonNode context = detail.path("context");
        if (context.isObject()) {
            s.toolCallId = text(context, "toolCallId", s.toolCallId); s.sessionId = text(context, "sessionId", s.sessionId);
            s.agentRunId = text(context, "agentRunId", s.agentRunId); s.parentInvocationId = text(context, "parentInvocationId", s.parentInvocationId);
        }
        String to = f.toState(); long at = f.occurredAt();
        if (to.equals("PENDING_APPROVAL") && s.approvalRequestedAt == null) s.approvalRequestedAt = at;
        if ("PENDING_APPROVAL".equals(f.fromState()) && s.approvalResolvedAt == null) s.approvalResolvedAt = at;
        if (to.equals("QUEUED") && s.queuedAt == null) s.queuedAt = at;
        Attempt current = s.attempts.isEmpty() ? null : s.attempts.get(s.attempts.size() - 1);
        boolean open = current != null && current.endedAt == null;
        if (to.equals("EXECUTING")) {
            if (open) end(current, at, InsightsOutcome.FAILED);
            var a = new Attempt(); a.number = s.attempts.size() + 1; a.executionId = f.executionId(); a.executorId = text(detail, "executorId", null);
            a.startedAt = at; a.outcome = InsightsOutcome.IN_PROGRESS; s.attempts.add(a);
            if (s.firstStartedAt == null) s.firstStartedAt = at;
        } else if (open && (terminal(to) || to.equals("QUEUED"))) {
            // A re-queue (T11) loses the attempt; a terminal transition ends it with the invocation's outcome.
            end(current, at, to.equals("QUEUED") ? InsightsOutcome.FAILED : outcome(to));
        } else if (current != null && "OUTCOME_UNKNOWN".equals(f.fromState()) && terminal(to)) {
            current.outcome = outcome(to); // T13 reconciliation settles the attempt that was in doubt.
        }
        if (terminal(to) && s.terminalAt == null) s.terminalAt = at;
        s.state = to;
        s.cause = text(detail, "cause", null);
    }
    private static void end(Attempt a, long at, InsightsOutcome outcome) { a.endedAt = at; a.outcome = outcome; }

    /** What an invocation adds to aggregates: one call in its outcome's cell for its tool and client, plus its
     *  execution duration once it has one. Stored as the checkpoint so a later change can subtract it exactly. */
    public record Contribution(String tool, String client, long acceptedAt, String outcome, Long durationMs) {
        public static Contribution of(Summary s) {
            Long ms = terminal(s.state) ? s.executionMs() : null;
            return new Contribution(s.toolId, s.clientId == null ? "" : s.clientId, s.acceptedAt, s.outcome().name(), ms);
        }
        public String json() {
            ObjectNode n = JSON.createObjectNode().put("tool", tool).put("client", client).put("acceptedAt", acceptedAt).put("outcome", outcome);
            if (durationMs != null) n.put("durationMs", durationMs);
            return n.toString();
        }
        public static Contribution parse(String json) {
            if (json == null || json.isEmpty() || json.equals("{}")) return null;
            JsonNode n = Insights.parse(json);
            return new Contribution(n.path("tool").asText(), n.path("client").asText(), n.path("acceptedAt").asLong(), n.path("outcome").asText(),
                n.hasNonNull("durationMs") ? n.get("durationMs").asLong() : null);
        }
        /** Adds this contribution, times sign, to every cell it touches. */
        public void addTo(Map<Cell, Delta> deltas, int sign) {
            for (var g : InsightsGranularity.values()) {
                long bucket = floor(acceptedAt, g);
                deltas.computeIfAbsent(new Cell("TOOL", g, bucket, tool, outcome), k -> new Delta()).add(sign, durationMs);
                deltas.computeIfAbsent(new Cell("CLIENT", g, bucket, client, outcome), k -> new Delta()).add(sign, durationMs);
            }
        }
    }

    /** One aggregate cell. Sorted so concurrent writers always upsert in the same order. */
    public record Cell(String series, InsightsGranularity granularity, long bucket, String dim, String outcome) implements Comparable<Cell> {
        public int compareTo(Cell o) {
            int c = series.compareTo(o.series); if (c != 0) return c;
            c = granularity.compareTo(o.granularity); if (c != 0) return c;
            c = Long.compare(bucket, o.bucket); if (c != 0) return c;
            c = dim.compareTo(o.dim); if (c != 0) return c;
            return outcome.compareTo(o.outcome);
        }
    }
    /** A signed change to one cell. */
    public static final class Delta {
        public long calls, durationCount, durationSum; public final long[] histogram = new long[BUCKETS];
        void add(int sign, Long durationMs) {
            calls += sign;
            if (durationMs != null) { durationCount += sign; durationSum += sign * durationMs; histogram[histogramIndex(durationMs)] += sign; }
        }
        public boolean empty() {
            if (calls != 0 || durationCount != 0 || durationSum != 0) return false;
            for (long h : histogram) if (h != 0) return false;
            return true;
        }
    }
    /** A stored cell's values. */
    public record CellValue(long calls, long durationCount, long durationSum, long[] histogram) {
        public CellValue plus(Delta d) {
            long[] h = histogram.clone(); for (int i = 0; i < h.length; i++) h[i] += d.histogram[i];
            return new CellValue(calls + d.calls, durationCount + d.durationCount, durationSum + d.durationSum, h);
        }
        public boolean zero() { return calls == 0 && durationCount == 0 && durationSum == 0; }
        public static CellValue empty() { return new CellValue(0, 0, 0, new long[BUCKETS]); }
        public boolean sameAs(CellValue o) {
            return calls == o.calls && durationCount == o.durationCount && durationSum == o.durationSum && java.util.Arrays.equals(histogram, o.histogram);
        }
    }
    public static Map<Cell, Delta> deltas() { return new TreeMap<>(); }

    /** Percentile from a merged histogram, as the upper bound of the bucket holding it; null below the sample floor
     *  (20 for p95, 100 for p99, 1 otherwise). The overflow bucket reports the last bound. */
    public static Long percentile(long[] histogram, double q) {
        long total = 0; for (long h : histogram) total += h;
        long floor = q >= 0.99 ? 100 : q >= 0.95 ? 20 : 1;
        if (total < floor) return null;
        long rank = (long) Math.ceil(q * total), seen = 0;
        for (int i = 0; i < histogram.length; i++) { seen += histogram[i]; if (seen >= rank) return BOUNDS_MS[Math.min(i, BOUNDS_MS.length - 1)]; }
        return BOUNDS_MS[BOUNDS_MS.length - 1];
    }

    public static JsonNode parseArray(String json) {
        JsonNode n = parse(json); return n.isArray() ? n : JSON.createArrayNode();
    }
    public static JsonNode parse(String json) {
        try { return json == null ? JSON.createObjectNode() : JSON.readTree(json); }
        catch (java.io.IOException invalid) { return JSON.createObjectNode(); }
    }
    private static String text(JsonNode n, String field, String otherwise) { return n.path(field).isTextual() ? n.get(field).asText() : otherwise; }
}
