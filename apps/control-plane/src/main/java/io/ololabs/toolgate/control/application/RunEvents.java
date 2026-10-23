// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.contracts.EnterpriseInvocationState;
import io.ololabs.toolgate.contracts.v2.ResultFrame;
import io.ololabs.toolgate.contracts.v2.RunEventBatch;
import io.ololabs.toolgate.contracts.v2.RunEventFrame;
import io.ololabs.toolgate.contracts.v2.RunEventKind;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Durable governed run-response events (docs/contracts/platform-requirements.md).
 *
 *  An executor forwards the redacted SDK frames of the attempt it admitted, bound to the fencing token its permit
 *  consumption returned; Control records them under the invocation, assigns the replay cursor, and records every ledger
 *  transition and the terminal result in the same stream. Frames never authorize or change the invocation: the ledger
 *  state stays authoritative. Readers are authorized exactly as for the invocation itself, on every page, so revocation
 *  ends access to a running stream. Bounds, drops, purges and missing sequences are reported, never hidden. */
public final class RunEvents {
    /** Events retained per invocation, executor and platform together. */
    public static final int MAX_EVENTS = 1000;
    /** Retained document bytes per invocation. */
    public static final long MAX_BYTES = 1024 * 1024;
    /** A retained result frame larger than this is replaced by an explicit truncation marker. */
    public static final int MAX_RESULT_BYTES = 96 * 1024;
    public static final long RETENTION_MS = 7L * 24 * 3600 * 1000;
    public static final int PAGE = 200;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<EnterpriseInvocationState> TERMINAL = EnumSet.of(EnterpriseInvocationState.SUCCEEDED, EnterpriseInvocationState.FAILED,
        EnterpriseInvocationState.CANCELLED, EnterpriseInvocationState.EXPIRED, EnterpriseInvocationState.PARTIAL, EnterpriseInvocationState.OUTCOME_UNKNOWN);

    private final Store store; private final Codec codec; private final EnterpriseOperations operations;
    public RunEvents(Store store, Codec codec, EnterpriseOperations operations) { this.store = store; this.codec = codec; this.operations = operations; }

    // Redaction before persistence. The SDK has already replaced declared secret and sensitive values; this second layer
    // masks credential-shaped text and removes terminal control sequences, so a forwarded frame cannot smuggle either.
    private static final Pattern ESCAPES = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)?|\\u001B.|\\u009B[0-?]*[ -/]*[@-~]");
    private static final Pattern CONTROLS = Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001F\\u007F-\\u009F]");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern ASSIGNED = Pattern.compile("(?i)\\b([A-Za-z0-9_.-]*(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|authorization)[A-Za-z0-9_.-]*)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)");
    private static final String MARK = "[REDACTED]";

    public static String redact(String text) {
        if (text == null) return null;
        String out = ESCAPES.matcher(text).replaceAll("");
        out = CONTROLS.matcher(out).replaceAll("");
        out = PEM.matcher(out).replaceAll(MARK);
        out = JWT.matcher(out).replaceAll(MARK);
        out = BEARER.matcher(out).replaceAll("$1 " + MARK);
        out = ASSIGNED.matcher(out).replaceAll("$1$3" + MARK);
        return out;
    }
    static JsonNode redact(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) return JSON.textNode(redact(node.asText()));
        if (node.isObject()) {
            var out = JSON.objectNode();
            node.properties().forEach(e -> {
                String key = redact(e.getKey());
                out.set(key, key.matches("(?i).*(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|authorization).*") && e.getValue().isValueNode() ? JSON.textNode(MARK) : redact(e.getValue()));
            });
            return out;
        }
        if (node.isArray()) { var out = JSON.arrayNode(); node.forEach(v -> out.add(redact(v))); return out; }
        return node;
    }
    private static int size(String document) { return document.getBytes(StandardCharsets.UTF_8).length; }

    /** Records an authoritative ledger transition; called by the store in the transaction that saves the transition. */
    public static void recordState(RunEventStore events, String invocationId, String state, long now) {
        String document = JSON.objectNode().put("source", "PLATFORM").put("kind", "STATE").put("state", state).put("recordedAtUnixMs", now).toString();
        events.append(invocationId, List.of(new RunEventStore.Event(0, "PLATFORM", "STATE", null, null, null, now, document)), size(document), 0, 0, now + RETENTION_MS);
    }

    /** Records the terminal frame an executor reported, in the transaction that accepts the report. */
    void recordResult(Store.Session tx, EnterpriseInvocation i, int attempt, String fencingToken, String executorId, ResultFrame result, long now) {
        if (result == null) return;
        var document = JSON.objectNode().put("source", "EXECUTOR").put("kind", "RESULT").put("attempt", attempt).put("executorId", executorId).put("recordedAtUnixMs", now);
        document.set("result", redact((JsonNode) codec.value(codec.json(result))));
        String text = document.toString(); long refused = 0;
        var events = tx.runEvents(); var stream = events.stream(i.id());
        if (size(text) > MAX_RESULT_BYTES || stream != null && (stream.events() >= MAX_EVENTS || stream.bytes() + size(text) > MAX_BYTES + MAX_RESULT_BYTES)) {
            var marker = JSON.objectNode().put("kind", "RESULT").put("resultType", "complete").put("isError", result.isError());
            if (result.provenance() != null) marker.put("provenance", result.provenance().name());
            marker.putArray("content").addObject().put("type", "text").put("text", "The result exceeds the retained run-event size; it is not shown here.");
            document.set("result", marker); text = document.toString(); refused = 1;
        }
        events.append(i.id(), List.of(new RunEventStore.Event(0, "EXECUTOR", "RESULT", attempt, fencingToken, null, now, text)), size(text), 0, refused, now + RETENTION_MS);
    }

    private static void requireShape(RunEventFrame f) {
        boolean ok = switch (f.kind()) {
            case LOG -> f.level() != null && f.message() != null && f.fraction() == null && f.requestId() == null && f.request() == null;
            case PROGRESS -> f.fraction() != null && f.level() == null && f.requestId() == null && f.request() == null;
            case INPUT_REQUEST -> f.requestId() != null && f.request() != null && f.level() == null && f.fraction() == null && f.message() == null;
            default -> false;
        };
        if (!ok) throw Failure.validation();
    }

    /** Frames from the executor that admitted the current attempt. Redelivered sequences are acknowledged, not stored twice. */
    public Store.Reply ingest(Ids.TenantId tenant, Function<Store.Session, String> device, String invocationId, String body) {
        var batch = codec.model(body, RunEventBatch.class);
        if (!batch.invocationId().equals(invocationId)) throw Failure.validation();
        batch.events().forEach(RunEvents::requireShape);
        int attempt = batch.attempt().intValue(); long dropped = batch.droppedFrames() == null ? 0 : batch.droppedFrames();
        return store.transaction(tenant, true, tx -> {
            String executor = device.apply(tx); var i = operations.invocationIn(tx, invocationId);
            if (!executor.equals(i.evaluation().context().deviceId()) || i.reservedNonce() == null || !i.reservedNonce().equals(batch.fencingToken())) throw ManagementAccess.denied();
            long now = operations.clock(tx); var events = tx.runEvents(); var stream = events.stream(invocationId);
            var present = new TreeSet<>(events.sequences(invocationId, batch.fencingToken()));
            int count = stream == null ? 0 : stream.events(); long bytes = stream == null ? 0 : stream.bytes();
            var accepted = new ArrayList<RunEventStore.Event>(); int duplicates = 0, refused = 0; long added = 0;
            var ordered = new ArrayList<>(batch.events()); ordered.sort(Comparator.comparingLong(RunEventFrame::sequence));
            for (var f : ordered) {
                int sequence = f.sequence().intValue();
                if (!present.add(sequence)) { duplicates++; continue; }
                ObjectNode document = JSON.objectNode().put("source", "EXECUTOR").put("kind", f.kind().name()).put("attempt", attempt).put("sequence", sequence)
                    .put("executorId", executor).put("emittedAtUnixMs", f.emittedAtUnixMs()).put("recordedAtUnixMs", now);
                if (f.level() != null) document.put("level", f.level().name().toLowerCase(Locale.ROOT));
                if (f.message() != null) document.put("message", redact(f.message()));
                if (f.fraction() != null) document.put("fraction", f.fraction());
                if (f.requestId() != null) document.put("requestId", f.requestId());
                if (f.request() != null) document.set("request", redact((JsonNode) codec.value(codec.json(f.request()))));
                if (document.has("message") && document.get("message").asText().isEmpty()) document.remove("message");
                String text = document.toString();
                if (count + accepted.size() >= MAX_EVENTS || bytes + added + size(text) > MAX_BYTES) { refused++; present.remove(sequence); continue; }
                accepted.add(new RunEventStore.Event(0, "EXECUTOR", f.kind().name(), attempt, batch.fencingToken(), sequence, now, text)); added += size(text);
            }
            if (!accepted.isEmpty() || dropped > 0 || refused > 0) events.append(invocationId, accepted, added, dropped, refused, now + RETENTION_MS);
            int through = 0; for (int s : present) { if (s != through + 1) break; through = s; }
            var ack = JSON.objectNode().put("invocationId", invocationId).put("attempt", attempt).put("acceptedThrough", through).put("duplicates", duplicates).put("refused", refused);
            return new Store.Reply(200, ack.toString(), 0);
        });
    }

    /** One replay page after a cursor, authorized like the invocation itself. */
    public Store.Reply page(DirectoryService.Actor actor, String invocationId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > PAGE) throw Failure.validation();
        return store.transaction(actor.tenant(), true, tx -> {
            var i = operations.invocationIn(tx, invocationId); long now = operations.clock(tx);
            operations.requireRead(tx, actor, i, now);
            var events = tx.runEvents(); var stream = events.stream(invocationId);
            var rows = events.page(invocationId, after, limit + 1); boolean more = rows.size() > limit;
            var page = JSON.objectNode().put("invocationId", invocationId).put("state", i.state().name()).put("terminal", TERMINAL.contains(i.state()));
            if (i.evaluation().toolId() != null) page.put("toolId", i.evaluation().toolId());
            if (i.evaluation().context().deviceId() != null) page.put("executorId", i.evaluation().context().deviceId());
            var items = page.putArray("events"); long next = after;
            for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
                var event = (ObjectNode) codec.value(row.document()); var ordered = JSON.objectNode().put("cursor", row.cursor());
                event.properties().forEach(e -> ordered.set(e.getKey(), e.getValue()));
                items.add(ordered); next = row.cursor();
            }
            page.put("nextCursor", next).put("more", more)
                .put("truncated", stream != null && stream.refusedFrames() > 0).put("droppedFrames", stream == null ? 0 : stream.droppedFrames())
                .put("expired", stream != null && stream.purgedAt() != null);
            var gaps = page.putArray("gaps");
            if (i.reservedNonce() != null && stream != null && stream.purgedAt() == null) {
                Integer attempt = events.attempt(invocationId, i.reservedNonce()); int expected = 1;
                for (int s : events.sequences(invocationId, i.reservedNonce())) {
                    if (s > expected && attempt != null && gaps.size() < 64) gaps.addObject().put("attempt", attempt).put("fromSequence", expected).put("toSequence", s - 1);
                    expected = s + 1;
                }
            }
            return new Store.Reply(200, page.toString(), i.revision());
        });
    }

    /** Bounded retention sweep. */
    public int purge(Ids.TenantId tenant, long now) { return store.transaction(tenant, true, tx -> tx.runEvents().purge(now, 100)); }
}
