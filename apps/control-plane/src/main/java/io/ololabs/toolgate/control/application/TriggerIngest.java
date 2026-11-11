// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.EndpointDeviceRecord;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointLevel;
import io.ololabs.toolgate.contracts.v2.TriggerCheckpointReport;
import io.ololabs.toolgate.contracts.v2.TriggerDisposition;
import io.ololabs.toolgate.contracts.v2.TriggerDispositionKind;
import io.ololabs.toolgate.contracts.v2.TriggerInstanceReport;
import io.ololabs.toolgate.contracts.v2.TriggerOutboxAck;
import io.ololabs.toolgate.contracts.v2.TriggerOutboxRecord;
import io.ololabs.toolgate.contracts.v2.TriggerRejection;
import io.ololabs.toolgate.contracts.v2.TriggerRejectionReason;
import io.ololabs.toolgate.contracts.v2.TriggerRetryLater;
import io.ololabs.toolgate.contracts.v2.TriggerSnapshotPageAck;
import io.ololabs.toolgate.contracts.v2.TriggerSync;
import io.ololabs.toolgate.contracts.v2.TriggerSyncAck;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.ololabs.toolgate.control.application.TriggerStore.Kind.CHECKPOINT;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.EPOCH;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.EVENT;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.INSTANCE;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.KEY;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.LEASE;

/**
 * Trigger Sync ingest (ADR 058, plan 6.6.1 T6–T9, plan 8.2–8.4): the Control side of {@code docs/specs/trigger_delivery.tla}.
 * It runs inside the device's check-in transaction, so an event row, its dedupe key and the recomputed
 * {@code acceptedOutboxSeq} commit together or not at all (T6).
 *
 * <ul>
 *   <li>A record is accepted only from the device the instance resolves to, at its current lease epoch, or at an older
 *       epoch of the same device for 7 days after the current one began (a non-revocative epoch end drains). Anything
 *       else is fenced (T9): rejected in the ack, with no row and no dedupe key, so it never suppresses a later replay.</li>
 *   <li>Each accepted record is re-validated: identity binding of {@code source}, the type, the size and the signed event
 *       schema. A failure is a terminal disposition. It resolves the record only under {@code SKIP_AND_RECORD}; under
 *       {@code HALT} the accepted sequence stops before it and the instance is {@code Failed (TerminalRejection)}.</li>
 *   <li>A stored event takes the dedupe key {@code (source, id)} (D); a duplicate resolves its record without a row.</li>
 *   <li>{@code acceptedOutboxSeq} is the highest n with every record 1..n resolved (A). A record over the instance's
 *       event rate is not resolved; the ack asks to retry it later.</li>
 *   <li>An {@code ACCEPTED} checkpoint is kept only when its {@code afterOutboxSeq} is within {@code acceptedOutboxSeq} (T7, C).</li>
 * </ul>
 */
public final class TriggerIngest {
    public static final String LOSS_RECORD_TYPE = "io.ololabs.toolgate.trigger.event.rejected.v1";
    static final long DRAIN_MS = 7L * 24 * 60 * 60 * 1000;
    static final int MAX_EVENT_BYTES = 16 * 1024;
    static final int MAX_INLINE_CHECKPOINT = 4096;
    /** Plan 8.5: the client check-in budget the executor sizes its batches to. */
    static final long MAX_CHECK_IN_BYTES = 196_608;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TriggerService triggers;
    private final Codec codec;

    public TriggerIngest(TriggerService triggers, Codec codec) {
        this.triggers = triggers;
        this.codec = codec;
    }

    public boolean enabled() { return triggers.enabled(); }

    /** The leases of a replayed check-in: renewed, with fresh tokens (the stored acknowledgment keeps none). */
    public List<io.ololabs.toolgate.contracts.v2.TriggerLease> leases(Store.Session tx, EndpointDeviceRecord device, String requestId) {
        return triggers.leases(tx, device, requestId).leases();
    }

    /** Ingests one check-in's {@code triggers} block and builds its acknowledgement (plan 8.3). */
    public TriggerSyncAck sync(Store.Session tx, EndpointDeviceRecord device, TriggerSync sync, String requestId) {
        triggers.available();
        long now = triggers.now(tx);
        var leases = triggers.leases(tx, device, requestId);
        var resolved = triggers.resolvedOn(tx, device, now);
        var pages = new ArrayList<TriggerSnapshotPageAck>();
        if (sync.snapshot() != null) {
            for (var report : sync.snapshot().instances()) {
                var instance = resolved.get(report.instanceId());
                if (instance != null && report.configRevision().equals(instance.desired().configRevision())) {
                    report(tx, instance, report, now);
                }
            }
            pages.add(new TriggerSnapshotPageAck(sync.snapshot().snapshotGeneration(), sync.snapshot().page()));
        }
        var batch = new Batch();
        for (var record : sync.events() == null ? List.<TriggerOutboxRecord>of() : sync.events()) ingest(tx, device, resolved, record, batch, now);
        // One write per (instance, epoch) per check-in, in the same transaction as its rows (T6).
        for (var epoch : batch.touched.values()) save(tx, epoch);
        for (var checkpoint : sync.checkpoints() == null ? List.<TriggerCheckpointReport>of() : sync.checkpoints()) checkpoint(tx, resolved, checkpoint, now);
        var acked = new ArrayList<TriggerOutboxAck>();
        for (var epoch : batch.touched.values()) if (epoch.accepted > 0) acked.add(new TriggerOutboxAck(epoch.instanceId, epoch.leaseEpoch, epoch.accepted));
        return new TriggerSyncAck(triggers.generation(tx, device.deviceId()), MAX_CHECK_IN_BYTES, sync.pending() > 0 ? Boolean.TRUE : null,
                list(acked), list(batch.dispositions), list(batch.retryLater), list(leases.leases()), list(leases.revoked()), list(batch.rejected),
                list(pages), null);
    }

    private static <T> List<T> list(List<T> items) { return items == null || items.isEmpty() ? null : List.copyOf(items); }

    // ---- events ----

    private static final class Batch {
        final Map<String, Epoch> touched = new LinkedHashMap<>();
        final List<TriggerDisposition> dispositions = new ArrayList<>();
        final List<TriggerRetryLater> retryLater = new ArrayList<>();
        final List<TriggerRejection> rejected = new ArrayList<>();
        final java.util.Set<String> fenced = new java.util.HashSet<>(), throttled = new java.util.HashSet<>();
        JsonNode settings;
    }

    /** {@code acceptedOutboxSeq} state of one (instance, lease epoch). */
    private static final class Epoch {
        String instanceId; long leaseEpoch; long accepted; TreeSet<Long> resolved = new TreeSet<>();
        double tokens = -1; long tokensAt; long halted; long revision;
    }

    private Epoch epoch(Store.Session tx, Batch batch, String instanceId, long leaseEpoch) {
        String id = instanceId + "/" + pad(leaseEpoch);
        var cached = batch.touched.get(id);
        if (cached != null) return cached;
        var epoch = new Epoch();
        epoch.instanceId = instanceId;
        epoch.leaseEpoch = leaseEpoch;
        var row = tx.triggers().get(EPOCH, id);
        if (row != null) {
            var document = TriggerService.read(row.document());
            epoch.accepted = document.path("acceptedOutboxSeq").asLong();
            for (var seq : document.path("resolved")) epoch.resolved.add(seq.asLong());
            epoch.tokens = document.path("tokens").asDouble(-1);
            epoch.tokensAt = document.path("tokensAtUnixMs").asLong();
            epoch.halted = document.path("haltedOutboxSeq").asLong();
            epoch.revision = row.revision();
        }
        batch.touched.put(id, epoch);
        return epoch;
    }

    private void save(Store.Session tx, Epoch epoch) {
        var document = JSON.createObjectNode().put("instanceId", epoch.instanceId).put("leaseEpoch", epoch.leaseEpoch).put("acceptedOutboxSeq", epoch.accepted);
        var resolved = document.putArray("resolved");
        epoch.resolved.forEach(resolved::add);
        document.put("tokens", epoch.tokens).put("tokensAtUnixMs", epoch.tokensAt);
        if (epoch.halted > 0) document.put("haltedOutboxSeq", epoch.halted);
        String text = codec.json(document);
        tx.triggers().save(EPOCH, new TriggerStore.Row(epoch.instanceId + "/" + pad(epoch.leaseEpoch), epoch.revision + 1, text), epoch.revision);
        epoch.revision++;
    }

    private void ingest(Store.Session tx, EndpointDeviceRecord device, Map<String, TriggerService.Resolved> resolved, TriggerOutboxRecord record, Batch batch, long now) {
        var event = record.event();
        long leaseEpoch = event.tgleaseepoch(), seq = event.tgsequence();
        String key = record.instanceId() + "/" + leaseEpoch;
        if (batch.fenced.contains(key)) return;
        var instance = resolved.get(record.instanceId());
        var lease = tx.triggers().get(LEASE, TriggerService.leaseId(device.deviceId(), record.instanceId()));
        TriggerRejectionReason fence = instance == null ? TriggerRejectionReason.NotAssigned : lease == null ? TriggerRejectionReason.UnknownInstance : fence(TriggerService.read(lease.document()), leaseEpoch, now);
        if (fence != null) {
            // T9: no state change, no dedupe key; the executor drops that epoch's records and resyncs.
            batch.fenced.add(key);
            batch.rejected.add(new TriggerRejection(record.instanceId(), leaseEpoch, fence));
            return;
        }
        if (batch.throttled.contains(key)) return;
        var epoch = epoch(tx, batch, record.instanceId(), leaseEpoch);
        if (seq <= epoch.accepted || epoch.resolved.contains(seq)) return; // a re-sent record: acknowledged again below
        if (epoch.halted > 0 && seq >= epoch.halted) return;               // HALT: nothing at or after the halted record resolves
        var descriptor = instance.definition().descriptor();
        if (!take(epoch, descriptor.path("limits").path("maxEventsPerHour").asLong(1), now)) {
            batch.throttled.add(key);
            batch.retryLater.add(new TriggerRetryLater(record.instanceId(), leaseEpoch, seq, retryAfter(epoch, descriptor)));
            return;
        }
        boolean skip = "SKIP_AND_RECORD".equals(descriptor.path("onTerminalRejection").asText());
        var view = codec.json(event);
        JsonNode stored = TriggerService.read(view);
        String reason = invalid(instance, device, event.source(), event.type(), event.tgkind().name(), stored, skip);
        var row = JSON.createObjectNode();
        row.set("event", stored);
        row.set("producer", producer(instance, device, leaseEpoch));
        row.put("receivedAtUnixMs", now);
        if (batch.settings == null) batch.settings = TriggerRouter.settings(tx);
        // Module 11, coarse mode: one label per event type, computed here in Control from the signed descriptor.
        row.put("classification", TriggerLabels.eventLabel(descriptor, batch.settings).name());
        String eventId = record.instanceId() + "/" + pad(leaseEpoch) + "/" + pad(seq);
        long purgeAt = now + batch.settings.path("eventRetentionDays").asLong(TriggerRouter.DEFAULT_RETENTION_DAYS) * TriggerRouter.DAY;
        if (reason != null) {
            // A Control-side terminal rejection: a disposition, recorded once per record.
            batch.dispositions.add(new TriggerDisposition(record.instanceId(), leaseEpoch, seq, event.id(), TriggerDispositionKind.REJECTED_TERMINAL, reason));
            row.putObject("disposition").put("disposition", "REJECTED_TERMINAL").put("reason", reason);
            disposition(tx, eventId, row, purgeAt);
            if (skip) epoch.resolved.add(seq);
            else {
                epoch.halted = seq;
                halt(tx, instance, event.id(), reason, now);
            }
        } else if ("rejected".equals(stored.path("tgkind").asText())) {
            // An executor loss record under SKIP_AND_RECORD: stored as an explicit disposition, with no dedupe key.
            row.putObject("disposition").put("disposition", "REJECTED_TERMINAL").put("reason", "LossRecord");
            disposition(tx, eventId, row, purgeAt);
            epoch.resolved.add(seq);
        } else {
            String dedupe = record.instanceId() + "/" + DirectoryService.digest(event.id());
            if (tx.triggers().get(KEY, dedupe) == null) {
                tx.triggers().save(KEY, new TriggerStore.Row(dedupe, 1, codec.json(JSON.createObjectNode().put("event", eventId))), 0);
                tx.triggers().save(EVENT, new TriggerStore.Row(eventId, 1, codec.json(row)), 0);
                // Transactional outbox (plan 12): routed after commit, in the same transaction as the event.
                tx.triggers().save(TriggerStore.Kind.OUTBOX, new TriggerStore.Row(eventId, 1, codec.json(JSON.createObjectNode().put("event", eventId))), 0);
                lastEvent(tx, instance, now);
            }
            epoch.resolved.add(seq);
        }
        while (epoch.resolved.contains(epoch.accepted + 1)) epoch.resolved.remove(++epoch.accepted);
    }

    /**
     * Why a record from this device's lease is fenced at {@code leaseEpoch}, or null when it is accepted. The current
     * epoch is accepted while the lease is active. An epoch that ended without a revocation, or by a Stop, drains for
     * 7 days from its end (plan 6.6.1); removal and every other revocation fence it at once.
     */
    private static TriggerRejectionReason fence(JsonNode lease, long leaseEpoch, long now) {
        long current = lease.path("leaseEpoch").asLong();
        String state = lease.path("state").asText();
        if (leaseEpoch > current) return TriggerRejectionReason.StaleLeaseEpoch;
        if ("ACTIVE".equals(state) && leaseEpoch == current) return null;
        boolean drains = "ACTIVE".equals(state) || ("REVOKED".equals(state) && "AssignmentStopped".equals(lease.path("reason").asText()));
        if (!drains) return TriggerRejectionReason.StaleLeaseEpoch;
        for (var ended : lease.path("endedEpochs")) {
            if (ended.path("leaseEpoch").asLong() == leaseEpoch) return now - ended.path("endedAtUnixMs").asLong() <= DRAIN_MS ? null : TriggerRejectionReason.StaleLeaseEpoch;
        }
        return TriggerRejectionReason.StaleLeaseEpoch;
    }

    /** Re-validation (plan 8.4); null when the record is acceptable, else the disposition reason. */
    private static String invalid(TriggerService.Resolved instance, EndpointDeviceRecord device, String source, String type, String kind, JsonNode event, boolean skip) {
        var descriptor = instance.definition().descriptor();
        if (!source.equals(source(device, instance))) return "IdentityMismatch";
        if ("rejected".equals(kind)) return skip && LOSS_RECORD_TYPE.equals(type) ? null : "UnexpectedLossRecord";
        if (!"fired".equals(kind)) return "UnsupportedKind";
        if (!type.equals(descriptor.path("eventType").asText())) return "TypeMismatch";
        int bytes = event.path("data").toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > Math.min(MAX_EVENT_BYTES, descriptor.path("limits").path("maxEventBytes").asLong(MAX_EVENT_BYTES))) return "EventTooLarge";
        return TriggerConfigs.validate(descriptor.path("eventSchema"), event.path("data")) == null ? null : "SchemaInvalid";
    }

    /** The CloudEvent {@code source} an executor must use for an instance (plan 6.4); never taken from the payload. */
    static String source(EndpointDeviceRecord device, TriggerService.Resolved instance) {
        return "toolgate:tenant:" + device.tenantId() + ":trigger-instance:" + instance.desired().instanceId();
    }

    private static ObjectNode producer(TriggerService.Resolved instance, EndpointDeviceRecord device, long leaseEpoch) {
        return JSON.createObjectNode().put("kind", "DEVICE").put("executorId", device.deviceId()).put("assignment", instance.stored().name())
                .put("instanceId", instance.desired().instanceId()).put("leaseEpoch", leaseEpoch)
                .put("packageDigest", instance.desired().packageDigest()).put("triggerDescriptorDigest", instance.desired().triggerDescriptorDigest());
    }

    /** A token bucket of {@code maxEventsPerHour} per (instance, epoch) (plan 8.4). */
    private static boolean take(Epoch epoch, long perHour, long now) {
        double capacity = Math.max(1, perHour);
        if (epoch.tokens < 0) { epoch.tokens = capacity; epoch.tokensAt = now; }
        epoch.tokens = Math.min(capacity, epoch.tokens + (now - epoch.tokensAt) * capacity / 3_600_000d);
        epoch.tokensAt = now;
        if (epoch.tokens < 1) return false;
        epoch.tokens -= 1;
        return true;
    }

    private static long retryAfter(Epoch epoch, JsonNode descriptor) {
        double perHour = Math.max(1, descriptor.path("limits").path("maxEventsPerHour").asLong(1));
        return Math.max(1000, (long) Math.ceil((1 - epoch.tokens) * 3_600_000d / perHour));
    }

    // ---- instance status ----

    private ObjectNode instanceRow(Store.Session tx, TriggerService.Resolved instance) {
        var row = tx.triggers().get(INSTANCE, instance.recordId());
        return row == null ? null : (ObjectNode) TriggerService.read(row.document());
    }

    private void saveInstance(Store.Session tx, TriggerService.Resolved instance, ObjectNode document) {
        var row = tx.triggers().get(INSTANCE, instance.recordId());
        if (row == null) return;
        String text = codec.json(document);
        if (text.equals(row.document())) return;
        tx.triggers().save(INSTANCE, new TriggerStore.Row(row.id(), row.revision() + 1, text), row.revision());
    }

    /** The executor's snapshot entry (plan 8.2) updates the instance's reported status and its server-side lastSeenAt. */
    private void report(Store.Session tx, TriggerService.Resolved instance, TriggerInstanceReport report, long now) {
        var document = instanceRow(tx, instance);
        if (document == null) return;
        var view = (ObjectNode) document.get("view");
        // A Control-side halt is Control's decision; a report cannot clear it.
        if (!document.path("haltedByControl").asBoolean()) view.put("phase", report.phase().name());
        ArrayNode conditions = JSON.createArrayNode();
        for (var condition : report.conditions()) {
            if (!"Stale".equals(condition.type().name())) conditions.add(TriggerService.read(codec.json(condition)));
        }
        view.set("conditions", conditions);
        view.set("counters", TriggerService.read(codec.json(report.counters())));
        view.set("outbox", TriggerService.read(codec.json(report.outbox())));
        if (report.lastEventAt() != null) view.put("lastEventAt", report.lastEventAt());
        if (report.lastReconcileAt() != null) view.put("lastReconcileAt", report.lastReconcileAt());
        document.put("lastSeenAtUnixMs", now);
        saveInstance(tx, instance, document);
    }

    private void lastEvent(Store.Session tx, TriggerService.Resolved instance, long now) {
        var document = instanceRow(tx, instance);
        if (document == null) return;
        document.put("lastStoredAtUnixMs", now);
        saveInstance(tx, instance, document);
    }

    /** Under HALT a Control-side rejection fails the instance (plan 6.6 item 5). */
    private void halt(Store.Session tx, TriggerService.Resolved instance, String eventId, String reason, long now) {
        var document = instanceRow(tx, instance);
        if (document == null) return;
        var view = (ObjectNode) document.get("view");
        view.put("phase", "Failed");
        document.put("haltedByControl", true);
        var conditions = view.withArray("conditions");
        for (int i = conditions.size() - 1; i >= 0; i--) if ("Ready".equals(conditions.get(i).path("type").asText())) conditions.remove(i);
        conditions.addObject().put("type", "Ready").put("status", "False").put("reason", "TerminalRejection")
                .put("message", bounded("Control rejected event " + eventId + ": " + reason, 256))
                .put("lastTransitionTime", java.time.Instant.ofEpochMilli(now).toString());
        saveInstance(tx, instance, document);
    }

    // ---- checkpoints ----

    /** T7: an inline ACCEPTED checkpoint is kept only within {@code acceptedOutboxSeq} of its epoch and newer than the last. */
    private void checkpoint(Store.Session tx, Map<String, TriggerService.Resolved> resolved, TriggerCheckpointReport report, long now) {
        if (report.level() != TriggerCheckpointLevel.ACCEPTED || report.inline() == null || !resolved.containsKey(report.instanceId())) return;
        byte[] body;
        try {
            body = Base64.getDecoder().decode(report.inline());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (body.length > MAX_INLINE_CHECKPOINT || body.length != report.sizeBytes() || !("sha256:" + DirectoryService.digest(body)).equals(report.digest())) return;
        var epochRow = tx.triggers().get(EPOCH, report.instanceId() + "/" + pad(report.leaseEpoch()));
        long accepted = epochRow == null ? 0 : TriggerService.read(epochRow.document()).path("acceptedOutboxSeq").asLong();
        if (report.afterOutboxSeq() > accepted) return; // C: never past acceptedOutboxSeq
        var row = tx.triggers().get(CHECKPOINT, report.instanceId());
        if (row != null && TriggerService.read(row.document()).path("version").asLong() >= report.version()) return;
        var document = JSON.createObjectNode().put("instanceId", report.instanceId()).put("leaseEpoch", report.leaseEpoch())
                .put("afterOutboxSeq", report.afterOutboxSeq()).put("level", "ACCEPTED").put("version", report.version())
                .put("digest", report.digest()).put("sizeBytes", report.sizeBytes()).put("inline", report.inline()).put("storedAtUnixMs", now);
        tx.triggers().save(CHECKPOINT, new TriggerStore.Row(report.instanceId(), row == null ? 1 : row.revision() + 1, codec.json(document)), row == null ? 0 : row.revision());
    }

    static String pad(long value) { return String.format("%016d", value); }

    /** A disposition is stored once and purged with retention; it is not an event and is never routed. */
    private void disposition(Store.Session tx, String eventId, ObjectNode row, long purgeAt) {
        if (tx.triggers().get(EVENT, eventId) != null) return;
        tx.triggers().save(EVENT, new TriggerStore.Row(eventId, 1, codec.json(row)), 0);
        String key = pad(purgeAt) + "/EVENT/" + eventId;
        tx.triggers().save(TriggerStore.Kind.EXPIRY, new TriggerStore.Row(key, 1, "{}"), 0);
    }

    private static String bounded(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
}
