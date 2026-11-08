// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.TriggerClassification;
import io.ololabs.toolgate.contracts.v2.TriggerDelivery;
import io.ololabs.toolgate.contracts.v2.TriggerDeliveryPage;
import io.ololabs.toolgate.contracts.v2.TriggerSettings;
import io.ololabs.toolgate.control.domain.Ids;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.ololabs.toolgate.control.application.TriggerStore.Kind.DELIVERY;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.DUE;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.EVENT;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.EXPIRY;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.KEY;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.LOG;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.OUTBOX;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.ROUTER;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.SETTINGS;

/**
 * The event router and retention (plan 12 and 12.1; triggers module 10).
 *
 * <ul>
 *   <li><b>Outbox.</b> Ingest writes an OUTBOX row in the same transaction as each stored event. {@link #route()} claims
 *       rows in id order (one source's records stay in order), gives each event the next position in the tenant's
 *       stream (LOG, the cursor space), creates one delivery per matching consumer and removes the outbox row, all in
 *       one transaction. A crash before commit routes the row again; nothing is routed twice.</li>
 *   <li><b>Deliveries.</b> {@code Pending → InFlight → Delivered}; {@code RetryWait} with full-jitter backoff
 *       (1 s → 5 min); {@code DeadLettered} after 10 attempts or 24 h, re-drivable by an admin; {@code Skipped} when a
 *       consumer declines. {@code Held} and {@code Superseded} belong to actions (module 14). Subscription deliveries
 *       are pulled by their socket (module 12); push consumers are dispatched by {@link #dispatch()}.</li>
 *   <li><b>Retention.</b> {@link #purge()} deletes events past the tenant's retention in bounded batches, never one with
 *       a non-terminal delivery unless it is past the hard ceiling (retention + 7 days), when those deliveries are
 *       dead-lettered {@code Expired}. Dedupe keys stay as tombstones for retention + 1 day; terminal deliveries are
 *       kept 30 days.</li>
 * </ul>
 * Delivery is at least once; consumers dedupe by {@code (source, id)}.
 */
public final class TriggerRouter {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int ROUTE_BATCH = 100, DISPATCH_BATCH = 50, PURGE_BATCH = 200, MAX_ATTEMPTS = 10;
    static final long DAY = 86_400_000L, MAX_AGE_MS = DAY, TERMINAL_KEEP_MS = 30 * DAY, TOMBSTONE_EXTRA_MS = DAY, HARD_EXTRA_MS = 7 * DAY;
    static final long BACKOFF_BASE_MS = 1000, BACKOFF_CAP_MS = 300_000;
    public static final int DEFAULT_RETENTION_DAYS = 7;
    static final Set<String> TERMINAL = Set.of("Delivered", "DeadLettered", "Skipped", "Superseded");

    /** A routed event, as consumers see it before redaction. */
    public record Routed(long seq, ObjectNode log, JsonNode event) {
        public String assignment() { return log.path("assignment").asText(); }
        public TriggerClassification label() { return TriggerClassification.valueOf(log.path("classification").asText()); }
    }
    /** Outcome of one push attempt. */
    public record Outcome(String state, String reason) {
        public static Outcome delivered() { return new Outcome("Delivered", null); }
        public static Outcome retry(String reason) { return new Outcome("RetryWait", reason); }
        public static Outcome skipped(String reason) { return new Outcome("Skipped", reason); }
    }
    /** An in-process push consumer (the router tests' TEST consumer; actions in module 14). */
    public interface Push {
        String name();
        TriggerClassification clearance();
        boolean matches(Routed event);
        Outcome deliver(JsonNode delivery, ObjectNode view);
    }
    /** A matching durable subscription: its name and its uid, the consumer key of its deliveries. */
    public record Match(String name, String uid) {}
    /** Durable subscriptions matching a routed event (module 12). */
    public interface Subscriptions { List<Match> matching(Store.Session tx, Routed event); }

    private final Store store;
    private final Codec codec;
    private final TriggerService triggers;
    private final Ids.TenantId tenant;
    private final Clock clock;
    private final int ceilingDays;
    private final List<Push> push = new ArrayList<>();
    private Subscriptions subscriptions = (tx, event) -> List.of();
    private final SecureRandom random = new SecureRandom();

    public TriggerRouter(Store store, Codec codec, TriggerService triggers, Clock clock, String tenant, int ceilingDays) {
        this.store = store; this.codec = codec; this.triggers = triggers; this.clock = clock;
        this.tenant = new Ids.TenantId(tenant);
        if (ceilingDays < 1 || ceilingDays > 365) throw new IllegalArgumentException("retention ceiling");
        this.ceilingDays = ceilingDays;
    }
    public TriggerRouter push(Push consumer) { push.add(consumer); return this; }
    public TriggerRouter subscriptions(Subscriptions value) { subscriptions = value; return this; }

    static String pad(long value) { return TriggerIngest.pad(value); }
    static JsonNode read(String text) { return TriggerService.read(text); }

    // ---- routing ----

    /** Routes up to one batch of outbox rows; returns how many. */
    public int route() {
        return store.transaction(tenant, true, tx -> {
            var rows = tx.triggers().page(OUTBOX, "", "", ROUTE_BATCH);
            if (rows.isEmpty()) return 0;
            long now = clock.millis();
            var state = tx.triggers().get(ROUTER, "state");
            var router = state == null ? JSON.createObjectNode() : (ObjectNode) read(state.document());
            long seq = router.path("nextSeq").asLong(1);
            for (var row : rows) {
                var stored = tx.triggers().get(EVENT, read(row.document()).path("event").asText());
                if (stored != null) route(tx, read(stored.document()), stored.id(), seq++, now);
                tx.triggers().delete(OUTBOX, row.id(), row.revision());
            }
            router.put("nextSeq", seq);
            tx.triggers().save(ROUTER, new TriggerStore.Row("state", state == null ? 1 : state.revision() + 1, codec.json(router)), state == null ? 0 : state.revision());
            return rows.size();
        });
    }

    private void route(Store.Session tx, JsonNode stored, String eventRow, long seq, long now) {
        var event = stored.path("event");
        var log = JSON.createObjectNode();
        log.put("seq", seq).put("event", eventRow).put("source", event.path("source").asText()).put("id", event.path("id").asText())
            .put("type", event.path("type").asText()).put("assignment", stored.path("producer").path("assignment").asText())
            .put("instanceId", eventRow.substring(0, eventRow.indexOf('/')))
            .put("receivedAtUnixMs", stored.path("receivedAtUnixMs").asLong(now))
            .put("classification", stored.path("classification").asText(TriggerClassification.RESTRICTED.name()));
        var routed = new Routed(seq, log, event);
        var deliveries = log.putArray("deliveries");
        for (var match : subscriptions.matching(tx, routed)) deliveries.add(create(tx, routed, "SUBSCRIPTION", match.name(), match.uid(), now, false));
        for (var consumer : push) {
            if (consumer.matches(routed)) deliveries.add(create(tx, routed, "TEST", consumer.name(), "test:" + consumer.name(), now, true));
        }
        log.put("open", deliveries.size());
        tx.triggers().save(LOG, new TriggerStore.Row(pad(seq), 1, codec.json(log)), 0);
    }

    private String create(Store.Session tx, Routed event, String kind, String name, String key, long now, boolean due) {
        String id = key + "/" + pad(event.seq());
        var delivery = JSON.createObjectNode();
        delivery.put("deliveryId", id);
        delivery.putObject("consumer").put("kind", kind).put("name", name);
        delivery.put("cursor", pad(event.seq())).put("eventSource", event.log().path("source").asText()).put("eventId", event.log().path("id").asText())
            .put("eventType", event.log().path("type").asText()).put("assignment", event.assignment())
            .put("state", "Pending").put("attempts", 0).put("createdAtUnixMs", now).put("updatedAtUnixMs", now);
        if (due) { delivery.put("nextAttemptAtUnixMs", now); tx.triggers().save(DUE, new TriggerStore.Row(pad(now) + "/" + id, 1, "{}"), 0); }
        tx.triggers().save(DELIVERY, new TriggerStore.Row(id, 1, codec.json(delivery)), 0);
        return id;
    }

    // ---- push dispatch ----

    /** Attempts up to one batch of due push deliveries; returns how many. */
    public int dispatch() {
        return store.transaction(tenant, true, tx -> {
            long now = clock.millis();
            int done = 0;
            for (var due : tx.triggers().page(DUE, "", "", DISPATCH_BATCH)) {
                if (Long.parseLong(due.id().substring(0, 16)) > now) break;
                tx.triggers().delete(DUE, due.id(), due.revision());
                var row = tx.triggers().get(DELIVERY, due.id().substring(17));
                if (row == null) continue;
                var delivery = (ObjectNode) read(row.document());
                if (TERMINAL.contains(delivery.path("state").asText())) continue;
                var consumer = push.stream().filter(c -> c.name().equals(delivery.path("consumer").path("name").asText())).findFirst().orElse(null);
                var log = tx.triggers().get(LOG, delivery.path("cursor").asText());
                var stored = log == null ? null : tx.triggers().get(EVENT, read(log.document()).path("event").asText());
                Outcome outcome;
                if (consumer == null) outcome = Outcome.retry("ConsumerUnavailable");
                else if (stored == null) outcome = new Outcome("DeadLettered", "Expired");
                else {
                    var logged = (ObjectNode) read(log.document());
                    var label = TriggerClassification.valueOf(logged.path("classification").asText());
                    try { outcome = consumer.deliver(delivery, TriggerLabels.view(read(stored.document()).path("event"), label, consumer.clearance())); }
                    catch (RuntimeException failed) { outcome = Outcome.retry("ConsumerFailed"); }
                }
                delivery.put("attempts", delivery.path("attempts").asInt() + 1);
                if ("RetryWait".equals(outcome.state())
                        && (delivery.path("attempts").asInt() >= MAX_ATTEMPTS || now - delivery.path("createdAtUnixMs").asLong() >= MAX_AGE_MS)) {
                    outcome = new Outcome("DeadLettered", outcome.reason());
                }
                transition(tx, row, delivery, outcome.state(), outcome.reason(), now);
                done++;
            }
            return done;
        });
    }

    /** Full jitter (AWS architecture blog): uniform in (0, min(cap, base · 2^(attempts-1))]. */
    long backoff(int attempts) {
        long ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(20, Math.max(0, attempts - 1)));
        return 1 + (long) (random.nextDouble() * ceiling);
    }

    /** Moves a delivery to a state, keeping its event's open count and the retention indexes in step. */
    void transition(Store.Session tx, TriggerStore.Row row, ObjectNode delivery, String state, String reason, long now) {
        boolean wasTerminal = TERMINAL.contains(delivery.path("state").asText());
        delivery.put("state", state).put("updatedAtUnixMs", now);
        if (reason != null) delivery.put("reason", reason); else delivery.remove("reason");
        delivery.remove("nextAttemptAtUnixMs");
        if ("RetryWait".equals(state) || "Pending".equals(state) && "TEST".equals(delivery.path("consumer").path("kind").asText())) {
            long at = "Pending".equals(state) ? now : now + backoff(delivery.path("attempts").asInt());
            delivery.put("nextAttemptAtUnixMs", at);
            tx.triggers().save(DUE, new TriggerStore.Row(pad(at) + "/" + row.id(), 1, "{}"), 0);
        }
        boolean terminal = TERMINAL.contains(state);
        if (terminal && !wasTerminal) {
            open(tx, delivery.path("cursor").asText(), -1);
            expire(tx, now + TERMINAL_KEEP_MS, DELIVERY, row.id());
        } else if (!terminal && wasTerminal) open(tx, delivery.path("cursor").asText(), 1);
        tx.triggers().save(DELIVERY, new TriggerStore.Row(row.id(), row.revision() + 1, codec.json(delivery)), row.revision());
    }

    private void open(Store.Session tx, String cursor, int delta) {
        var row = tx.triggers().get(LOG, cursor);
        if (row == null) return;
        var log = (ObjectNode) read(row.document());
        log.put("open", Math.max(0, log.path("open").asInt() + delta));
        tx.triggers().save(LOG, new TriggerStore.Row(row.id(), row.revision() + 1, codec.json(log)), row.revision());
    }

    void expire(Store.Session tx, long at, TriggerStore.Kind kind, String id) {
        String key = pad(at) + "/" + kind.name() + "/" + id;
        if (tx.triggers().get(EXPIRY, key) == null) tx.triggers().save(EXPIRY, new TriggerStore.Row(key, 1, "{}"), 0);
    }

    // ---- retention ----

    /** One bounded purge pass; returns the number of rows removed. */
    public int purge() {
        return store.transaction(tenant, true, tx -> {
            long now = clock.millis();
            long retention = retentionDays(settings(tx)) * DAY;
            int removed = 0;
            for (var expiry : tx.triggers().page(EXPIRY, "", "", PURGE_BATCH)) {
                if (Long.parseLong(expiry.id().substring(0, 16)) > now) break;
                String rest = expiry.id().substring(17);
                var kind = TriggerStore.Kind.valueOf(rest.substring(0, rest.indexOf('/')));
                String id = rest.substring(rest.indexOf('/') + 1);
                var target = tx.triggers().get(kind, id);
                if (target != null && tx.triggers().delete(kind, id, target.revision())) removed++;
                tx.triggers().delete(EXPIRY, expiry.id(), expiry.revision());
            }
            for (var row : tx.triggers().page(LOG, "", "", PURGE_BATCH)) {
                var log = (ObjectNode) read(row.document());
                long received = log.path("receivedAtUnixMs").asLong();
                if (received + retention > now) break;
                if (log.path("open").asInt() > 0) {
                    if (received + retention + HARD_EXTRA_MS > now) break;   // ordered: nothing later may go first
                    for (var id : log.path("deliveries")) {
                        var delivery = tx.triggers().get(DELIVERY, id.asText());
                        if (delivery == null) continue;
                        var document = (ObjectNode) read(delivery.document());
                        if (!TERMINAL.contains(document.path("state").asText())) transition(tx, delivery, document, "DeadLettered", "Expired", now);
                    }
                    row = tx.triggers().get(LOG, row.id());
                }
                var event = tx.triggers().get(EVENT, log.path("event").asText());
                if (event != null && tx.triggers().delete(EVENT, event.id(), event.revision())) removed++;
                // The dedupe key stays a tombstone for retention + 1 day, so a late duplicate cannot recreate the event.
                expire(tx, received + retention + TOMBSTONE_EXTRA_MS, KEY, log.path("instanceId").asText() + "/" + DirectoryService.digest(log.path("id").asText()));
                if (tx.triggers().delete(LOG, row.id(), row.revision())) removed++;
            }
            return removed;
        });
    }

    /** The first retained stream position, or 0 when nothing is retained. */
    static long first(Store.Session tx) {
        var rows = tx.triggers().page(LOG, "", "", 1);
        return rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().id());
    }
    /** The next stream position to be assigned. */
    static long next(Store.Session tx) {
        var state = tx.triggers().get(ROUTER, "state");
        return state == null ? 1 : read(state.document()).path("nextSeq").asLong(1);
    }

    // ---- settings ----

    public static JsonNode settings(Store.Session tx) {
        var row = tx.triggers().get(SETTINGS, "tenant");
        if (row != null) return read(row.document());
        var settings = JSON.createObjectNode().put("eventRetentionDays", DEFAULT_RETENTION_DAYS);
        settings.putObject("defaultClassification").put("sdkPackage", "INTERNAL").put("mcpProxy", "CONFIDENTIAL").put("external", "CONFIDENTIAL").put("unknown", "RESTRICTED");
        return settings;
    }
    long retentionDays(JsonNode settings) { return Math.min(ceilingDays, settings.path("eventRetentionDays").asLong(DEFAULT_RETENTION_DAYS)); }

    public Store.Reply settings(DirectoryService.Actor actor) {
        triggers.tenantOf(actor);
        return store.transaction(tenant, false, tx -> {
            new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "trigger.read", clock.millis());
            return reply(200, settings(tx), TriggerSettings.class);
        });
    }

    /**
     * Replaces the tenant settings. Retention is bounded by the operator ceiling; a default classification may be raised,
     * never lowered below the shipped value (lowering needs maker/checker, which trigger settings do not have yet).
     */
    public Store.Reply updateSettings(DirectoryService.Actor actor, String body, String requestId) {
        triggers.tenantOf(actor);
        var settings = codec.model(body == null ? "" : body, TriggerSettings.class);
        if (settings.eventRetentionDays() > ceilingDays) throw TriggerService.invalid("Event retention is at most " + ceilingDays + " days on this installation");
        var defaults = settings.defaultClassification();
        check(defaults.sdkPackage(), TriggerLabels.Integration.SDK_PACKAGE);
        check(defaults.mcpProxy(), TriggerLabels.Integration.MCP_PROXY);
        check(defaults.external(), TriggerLabels.Integration.EXTERNAL);
        check(defaults.unknown(), TriggerLabels.Integration.UNKNOWN);
        String text = codec.json(settings);
        return store.transaction(tenant, true, tx -> {
            new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "trigger.manage", clock.millis());
            var row = tx.triggers().get(SETTINGS, "tenant");
            long revision = row == null ? 1 : row.revision() + 1;
            tx.triggers().save(SETTINGS, new TriggerStore.Row("tenant", revision, text), row == null ? 0 : row.revision());
            tx.audit(actor.id(), "TRIGGER_SETTINGS_UPDATE", "trigger-settings:tenant", revision, requestId, DirectoryService.digest(text));
            return reply(200, read(text), TriggerSettings.class);
        });
    }
    private static void check(TriggerClassification value, TriggerLabels.Integration integration) {
        if (value.ordinal() < TriggerLabels.shipped(integration).ordinal())
            throw TriggerService.invalid("The default for " + integration + " cannot be lowered below " + TriggerLabels.shipped(integration));
    }

    // ---- deliveries API ----

    /** Deliveries of one consumer (a subscription name or a test consumer), optionally of one state. */
    public Store.Reply deliveries(DirectoryService.Actor actor, String consumerKey, String state, String pageToken, Integer pageSize) {
        triggers.tenantOf(actor);
        if (consumerKey == null || !consumerKey.matches("^[a-z0-9:-]{1,100}$")) throw TriggerService.notFound();
        int size = TriggerService.pageSize(pageSize);
        String after = TriggerService.after(pageToken);
        return store.transaction(tenant, false, tx -> {
            var page = JSON.createObjectNode();
            ArrayNode items = page.putArray("items");
            String cursor = after.isEmpty() ? "" : consumerKey + "/" + after;
            while (items.size() < size) {
                var rows = tx.triggers().page(DELIVERY, consumerKey + "/", cursor, size + 1);
                for (var row : rows) {
                    cursor = row.id();
                    var delivery = read(row.document());
                    if (state != null && !state.equals(delivery.path("state").asText())) continue;
                    var assignment = tx.triggers().get(TriggerStore.Kind.ASSIGNMENT, delivery.path("assignment").asText());
                    if (assignment == null) continue;
                    try { triggers.requireTargets(tx, actor, read(assignment.document()).path("assignment").path("spec").path("target"), "trigger.read"); }
                    catch (Failure denied) { if (denied.status() == 403) continue; throw denied; }
                    if (items.size() == size) { page.put("nextPageToken", TriggerService.token(cursor.substring(consumerKey.length() + 1))); return reply(200, page, TriggerDeliveryPage.class); }
                    items.add(delivery);
                }
                if (rows.size() <= size) break;
            }
            return reply(200, page, TriggerDeliveryPage.class);
        });
    }

    /** Re-drives a dead-lettered (or skipped) delivery: {@code trigger.manage} on its assignment's targets; audited. */
    public Store.Reply redrive(DirectoryService.Actor actor, String deliveryId, String requestId) {
        triggers.tenantOf(actor);
        if (deliveryId == null || !deliveryId.matches("^[a-z0-9:-]{1,100}/[0-9]{16}$")) throw TriggerService.notFound();
        return store.transaction(tenant, true, tx -> {
            var row = tx.triggers().get(DELIVERY, deliveryId);
            if (row == null) throw TriggerService.notFound();
            var delivery = (ObjectNode) read(row.document());
            var assignment = tx.triggers().get(TriggerStore.Kind.ASSIGNMENT, delivery.path("assignment").asText());
            if (assignment == null) throw TriggerService.notFound();
            triggers.requireTargets(tx, actor, read(assignment.document()).path("assignment").path("spec").path("target"), "trigger.manage");
            String state = delivery.path("state").asText();
            if (!Set.of("DeadLettered", "Skipped").contains(state)) throw TriggerService.conflict("Only dead-lettered or skipped deliveries can be re-driven");
            if (tx.triggers().get(LOG, delivery.path("cursor").asText()) == null) throw TriggerService.conflict("The event is past retention");
            delivery.put("attempts", 0);
            transition(tx, row, delivery, "Pending", null, clock.millis());
            tx.audit(actor.id(), "TRIGGER_DELIVERY_REDRIVE", "trigger-delivery:" + deliveryId, row.revision() + 1, requestId, DirectoryService.digest(deliveryId + "\n" + state));
            return reply(200, read(tx.triggers().get(DELIVERY, deliveryId).document()), TriggerDelivery.class);
        });
    }

    private Store.Reply reply(int status, JsonNode body, Class<?> contract) {
        String text = codec.json(body);
        codec.model(text, contract);
        return new Store.Reply(status, text, 0);
    }
}
