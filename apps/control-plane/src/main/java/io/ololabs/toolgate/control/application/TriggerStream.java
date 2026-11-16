// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.TriggerStreamClientMessage;
import io.ololabs.toolgate.contracts.v2.TriggerStreamServerMessage;
import io.ololabs.toolgate.contracts.v2.TriggerStreamTicket;
import io.ololabs.toolgate.control.domain.Ids;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.ololabs.toolgate.control.application.TriggerStore.Kind.CURSOR;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.DELIVERY;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.LOG;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.TICKET;

/**
 * The {@code toolgate.triggers.v1} protocol (plan 13.2; triggers module 12), independent of the socket transport.
 *
 * <p><b>Tickets are credentials.</b> 256 random bits, stored only as their SHA-256, valid for 30 s and used once; bound to
 * the tenant, the principal, the issuing request's {@code Origin} and the directory revision at issue (the authorization
 * epoch), so any grant change before use invalidates them. Neither the ticket nor its hash is written to the audit.
 *
 * <p><b>Sessions.</b> {@code subscribe} attaches a durable subscription, optionally from {@code resumeAfter}, otherwise
 * from its acknowledged cursor; a cursor older than retention gets an explicit {@code gap}. {@code watch} is ephemeral:
 * live events only, no cursor. {@code credit} grants more events (window 100). {@code ack} marks deliveries up to a
 * cursor {@code Delivered} and stores the cursor. Every turn re-checks the owner's grants: a revoked grant suspends the
 * subscription, sends {@code revoked} and closes with 1008. Every delivery and replay re-checks current authorization
 * and clearance; an event the owner can no longer read is {@code Skipped}, never delivered on old authority.
 */
public final class TriggerStream {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long TICKET_TTL_MS = 30_000, CREDIT_STALL_MS = 60_000;
    static final int WINDOW = 100, MAX_CREDIT = 1000;

    private final Store store;
    private final Codec codec;
    private final TriggerService triggers;
    private final TriggerSubscriptions subscriptions;
    private final TriggerRouter router;
    private final Clock clock;
    private final Ids.TenantId tenant;
    private final SecureRandom random = new SecureRandom();

    public TriggerStream(Store store, Codec codec, TriggerService triggers, TriggerSubscriptions subscriptions, TriggerRouter router, Clock clock, String tenant) {
        this.store = store; this.codec = codec; this.triggers = triggers; this.subscriptions = subscriptions; this.router = router; this.clock = clock;
        this.tenant = new Ids.TenantId(tenant);
    }

    static Failure refused() { return new Failure(ErrorCode.UNAUTHORIZED, 401, "Invalid or expired stream ticket"); }

    /** POST /triggers/stream/tickets: the response body is excluded from request logging (it is a credential). */
    public Store.Reply ticket(DirectoryService.Actor actor, String origin) {
        triggers.tenantOf(actor);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return store.transaction(tenant, true, tx -> {
            long now = clock.millis();
            var record = JSON.createObjectNode().put("actorId", actor.id()).put("userId", actor.userId()).put("admin", actor.admin())
                .put("superAdmin", actor.superAdmin()).put("epoch", tx.load().revision()).put("expiresAtUnixMs", now + TICKET_TTL_MS);
            if (origin != null) record.put("origin", origin);
            String hash = DirectoryService.digest(ticket);
            tx.triggers().save(TICKET, new TriggerStore.Row(hash, 1, codec.json(record)), 0);
            router.expire(tx, now + TICKET_TTL_MS, TICKET, hash);
            var body = JSON.createObjectNode().put("ticket", ticket).put("expiresAtUnixMs", now + TICKET_TTL_MS);
            String text = codec.json(body);
            codec.model(text, TriggerStreamTicket.class);
            return new Store.Reply(201, text, 0);
        });
    }

    /** Redeems a ticket once; 401 when unknown, used, expired, from another origin or issued before a grant change. */
    public Session open(String ticket, String origin) {
        if (ticket == null || !ticket.matches("^[A-Za-z0-9_-]{43}$")) throw refused();
        String hash = DirectoryService.digest(ticket);
        var actor = store.transaction(tenant, true, tx -> {
            var row = tx.triggers().get(TICKET, hash);
            if (row == null) return null;
            tx.triggers().delete(TICKET, hash, row.revision());   // single use, whatever the outcome
            var record = TriggerService.read(row.document());
            if (record.path("expiresAtUnixMs").asLong() <= clock.millis()) return null;
            if (!Objects.equals(record.path("origin").asText(null), origin)) return null;
            if (record.path("epoch").asLong() != tx.load().revision()) return null;
            return new DirectoryService.Actor(tenant, record.path("actorId").asText(), record.path("admin").asBoolean(), record.path("superAdmin").asBoolean(), record.path("userId").asText());
        });
        if (actor == null) throw refused();
        return new Session(actor);
    }

    /** One socket's protocol state. Not thread safe: the transport serializes calls. */
    public final class Session {
        final DirectoryService.Actor actor;
        final Map<String, String> attached = new LinkedHashMap<>();   // subscription name -> last cursor sent
        ObjectNode watch;                                               // sources, filter source, after
        int credit = WINDOW;
        long stalledSince = -1;
        Integer closeCode;
        String closeReason;

        Session(DirectoryService.Actor actor) { this.actor = actor; }
        public String principal() { return actor.userId(); }
        public Integer closeCode() { return closeCode; }
        public String closeReason() { return closeReason; }

        /** Handles one client text message; returns the messages to send. */
        public List<String> receive(String text) {
            var out = new ArrayList<String>();
            TriggerStreamClientMessage message;
            try { message = codec.model(text, TriggerStreamClientMessage.class); }
            catch (RuntimeException invalid) { out.add(error(400, "VALIDATION", "Not a toolgate.triggers.v1 message")); return out; }
            try {
                switch (message.type()) {
                    case subscribe -> subscribe(message, out);
                    case watch -> watch(message, out);
                    case ack -> ack(message, out);
                    case credit -> { credit = (int) Math.min(MAX_CREDIT, credit + (message.n() == null ? 0L : message.n())); if (credit > 0) stalledSince = -1; }
                    case unsubscribe -> { if (message.subscription() != null) attached.remove(message.subscription()); }
                }
            } catch (Failure failure) { out.add(error(failure.status(), failure.code().name(), failure.getMessage())); }
            return out;
        }

        private void subscribe(TriggerStreamClientMessage message, List<String> out) {
            String name = message.subscription();
            if (name == null) throw TriggerService.invalid("subscribe names a subscription");
            store.transaction(tenant, false, tx -> {
                var sub = subscriptions.stored(tx, name);
                if (sub == null || !sub.owner().equals(actor.userId())) throw TriggerService.notFound();
                if (!"Active".equals(sub.phase())) { out.add(revoked(name, sub.document().path("subscription").path("status").path("reason").asText("Suspended"))); return null; }
                String after = message.resumeAfter();
                if (after == null) {
                    var cursor = tx.triggers().get(CURSOR, sub.uid());
                    after = cursor == null ? "" : TriggerService.read(cursor.document()).path("acked").asText("");
                }
                long first = TriggerRouter.first(tx);
                long from = after.isEmpty() ? 0 : Long.parseLong(after) + 1;
                if (!after.isEmpty() && first > from) out.add(gap(name, from, first - 1));
                attached.put(name, after);
                out.add(server(JSON.createObjectNode().put("type", "subscribed").put("subscription", name)));
                return null;
            });
        }

        private void watch(TriggerStreamClientMessage message, List<String> out) {
            if (message.sources() == null) throw TriggerService.invalid("watch names its sources");
            store.transaction(tenant, false, tx -> {
                var sources = message.sources().assignments();
                subscriptions.requireSources(tx, actor, sources);
                var clearance = subscriptions.clearance(tx, actor.userId());
                TriggerFilters.compile(message.filter(), subscriptions.readable(tx, sources, clearance));
                watch = JSON.createObjectNode().put("after", TriggerRouter.pad(TriggerRouter.next(tx) - 1));
                watch.set("sources", JSON.valueToTree(sources));
                if (message.filter() != null) watch.put("filter", message.filter());
                out.add(server(JSON.createObjectNode().put("type", "subscribed")));
                return null;
            });
        }

        private void ack(TriggerStreamClientMessage message, List<String> out) {
            String name = message.subscription(), cursor = message.cursor();
            if (name == null || cursor == null || !attached.containsKey(name)) throw TriggerService.invalid("ack names an attached subscription and a cursor");
            store.transaction(tenant, true, tx -> {
                var sub = subscriptions.stored(tx, name);
                if (sub == null) throw TriggerService.notFound();
                long now = clock.millis();
                for (var row : tx.triggers().page(DELIVERY, sub.uid() + "/", "", 513)) {
                    if (row.id().substring(sub.uid().length() + 1).compareTo(cursor) > 0) break;
                    var delivery = (ObjectNode) TriggerService.read(row.document());
                    String state = delivery.path("state").asText();
                    if (state.equals("InFlight") || state.equals("Pending")) router.transition(tx, row, delivery, "Delivered", null, now);
                }
                var stored = tx.triggers().get(CURSOR, sub.uid());
                String previous = stored == null ? "" : TriggerService.read(stored.document()).path("acked").asText("");
                if (cursor.compareTo(previous) > 0) {
                    String document = codec.json(JSON.createObjectNode().put("acked", cursor));
                    tx.triggers().save(CURSOR, new TriggerStore.Row(sub.uid(), stored == null ? 1 : stored.revision() + 1, document), stored == null ? 0 : stored.revision());
                }
                return null;
            });
        }

        /** Sends what is ready within the credit window, and enforces revocation and the credit-stall limit. */
        public List<String> pump() {
            var out = new ArrayList<String>();
            if (closeCode != null) return out;
            boolean waiting = false;
            for (var name : List.copyOf(attached.keySet())) {
                waiting |= store.transaction(tenant, true, tx -> pumpSubscription(tx, name, out));
                if (closeCode != null) return out;
            }
            if (watch != null) waiting |= store.transaction(tenant, false, tx -> pumpWatch(tx, out));
            long now = clock.millis();
            if (waiting && credit == 0) {
                if (stalledSince < 0) stalledSince = now;
                else if (now - stalledSince >= CREDIT_STALL_MS) { closeCode = 1013; closeReason = "No credit for 60 s"; }
            } else if (credit > 0) stalledSince = -1;
            return out;
        }

        private boolean pumpSubscription(Store.Session tx, String name, List<String> out) {
            var sub = subscriptions.stored(tx, name);
            if (sub == null) { attached.remove(name); out.add(revoked(name, "Deleted")); return false; }
            if (!"Active".equals(sub.phase())) { attached.remove(name); out.add(revoked(name, sub.document().path("subscription").path("status").path("reason").asText("Suspended"))); return false; }
            if (sub.spec().path("expiresAtUnixMs").asLong() <= clock.millis()) { attached.remove(name); out.add(revoked(name, "Expired")); return false; }
            try { subscriptions.requireSources(tx, subscriptions.owner(sub), sub.sources()); }
            catch (Failure denied) {
                if (denied.status() != 403 && denied.status() != 404) throw denied;
                subscriptions.suspend(tx, sub, "GrantRevoked");
                attached.remove(name);
                out.add(revoked(name, "GrantRevoked"));
                closeCode = 1008; closeReason = "Subscription grant revoked";
                return false;
            }
            var clearance = subscriptions.clearance(tx, sub.owner());
            String after = attached.get(name);
            long now = clock.millis();
            boolean more = false;
            for (var row : tx.triggers().page(DELIVERY, sub.uid() + "/", after.isEmpty() ? "" : sub.uid() + "/" + after, Math.max(1, Math.min(credit, 200) + 1))) {
                if (credit == 0) { more = true; break; }
                String cursor = row.id().substring(sub.uid().length() + 1);
                var delivery = (ObjectNode) TriggerService.read(row.document());
                attached.put(name, cursor);
                if (delivery.path("state").asText().equals("Skipped")) continue;
                var log = tx.triggers().get(LOG, cursor);
                var event = log == null ? null : tx.triggers().get(TriggerStore.Kind.EVENT, TriggerService.read(log.document()).path("event").asText());
                if (event == null) { if (!TriggerRouter.TERMINAL.contains(delivery.path("state").asText())) router.transition(tx, row, delivery, "DeadLettered", "Expired", now); continue; }
                // Replay re-authorization (plan 12.1): the owner must still be able to read this event's source now,
                // the device that produced it, not only the assignment it was produced for.
                if (!readsSource(tx, sub.owner(), TriggerService.read(event.document()).path("producer"))) {
                    router.transition(tx, row, delivery, "Skipped", "Unauthorized", now);
                    continue;
                }
                var label = io.ololabs.toolgate.contracts.v2.TriggerClassification.valueOf(TriggerService.read(log.document()).path("classification").asText());
                var view = TriggerLabels.view(TriggerService.read(event.document()).path("event"), label, clearance);
                out.add(server(JSON.createObjectNode().put("type", "event").put("subscription", name).put("cursor", cursor).set("event", view)));
                if (delivery.path("state").asText().equals("Pending")) router.transition(tx, row, delivery, "InFlight", null, now);
                credit--;
            }
            return more;
        }

        private boolean pumpWatch(Store.Session tx, List<String> out) {
            var sources = TriggerService.strings(watch.path("sources"));
            try { subscriptions.requireSources(tx, actor, sources); }
            catch (Failure denied) {
                if (denied.status() != 403 && denied.status() != 404) throw denied;
                watch = null; out.add(revoked(null, "GrantRevoked")); closeCode = 1008; closeReason = "Watch grant revoked";
                return false;
            }
            var clearance = subscriptions.clearance(tx, actor.userId());
            var filter = TriggerFilters.compile(watch.path("filter").asText(null), subscriptions.readable(tx, sources, clearance));
            for (var row : tx.triggers().page(LOG, "", watch.path("after").asText(), Math.max(1, Math.min(credit, 200) + 1))) {
                if (credit == 0) return true;
                watch.put("after", row.id());
                var log = TriggerService.read(row.document());
                if (!sources.contains(log.path("assignment").asText())) continue;
                var event = tx.triggers().get(TriggerStore.Kind.EVENT, log.path("event").asText());
                if (event == null) continue;
                var label = io.ololabs.toolgate.contracts.v2.TriggerClassification.valueOf(log.path("classification").asText());
                var view = TriggerLabels.view(TriggerService.read(event.document()).path("event"), label, clearance);
                if (filter != null && !filter.matches(view)) continue;
                out.add(server(JSON.createObjectNode().put("type", "event").put("cursor", row.id()).set("event", view)));
                credit--;
            }
            return false;
        }
    }

    /**
     * Whether a user may read an event's source now: {@code trigger.subscribe} on a Device Group the producing device is
     * in at this moment. A device moved out of every group the user may read takes its stored events out of replay.
     */
    boolean readsSource(Store.Session tx, String userId, JsonNode producer) {
        var directory = tx.load();
        String device = producer.path("executorId").asText();
        if (directory.entries().get(Ids.Kind.DEVICE.id(device)) == null) return false;
        var access = new ManagementAccess(codec);
        if (access.superAdministrator(directory, userId)) return true;
        for (String group : GroupGraph.memberships(directory, codec, Ids.Kind.DEVICE, device, true)) {
            try { access.require(directory, userId, "trigger.subscribe", Ids.Kind.DEVICE_GROUP, group, clock.millis()); return true; }
            catch (Failure denied) { if (denied.status() != 403) throw denied; }
        }
        return false;
    }

    /** Resolve an in-flight delivery under its current owner and source authority in the admission transaction. */
    public record DispatchEvent(String assignmentUid, JsonNode event) {}

    public DispatchEvent dispatchEvent(Store.Session tx, DirectoryService.Actor actor, String subscriptionUid, String cursor) {
        triggers.tenantOf(actor);
        if (subscriptionUid == null || !subscriptionUid.matches("ts-[a-f0-9]{32}")
                || cursor == null || !cursor.matches("[0-9]{16}")) throw Failure.validation();
        var delivery = tx.triggers().get(DELIVERY, subscriptionUid + "/" + cursor);
        if (delivery == null) throw TriggerService.notFound();
        var record = TriggerService.read(delivery.document());
        var consumer = record.path("consumer");
        if (!"SUBSCRIPTION".equals(consumer.path("kind").asText()) || !"InFlight".equals(record.path("state").asText()))
            throw TriggerService.notFound();
        var sub = subscriptions.stored(tx, consumer.path("name").asText());
        if (sub == null || !sub.uid().equals(subscriptionUid) || !sub.owner().equals(actor.userId())
                || !"Active".equals(sub.phase()) || sub.spec().path("expiresAtUnixMs").asLong() <= clock.millis())
            throw TriggerService.notFound();
        subscriptions.requireSources(tx, subscriptions.owner(sub), sub.sources());

        var logRow = tx.triggers().get(LOG, cursor);
        if (logRow == null) throw TriggerService.notFound();
        var log = TriggerService.read(logRow.document());
        var eventRow = tx.triggers().get(TriggerStore.Kind.EVENT, log.path("event").asText());
        if (eventRow == null) throw TriggerService.notFound();
        var stored = TriggerService.read(eventRow.document());
        var producer = stored.path("producer");
        String assignmentName = producer.path("assignment").asText();
        if (!sub.sources().contains(assignmentName) || !readsSource(tx, sub.owner(), producer))
            throw ManagementAccess.denied();
        var assignment = triggers.existing(tx, assignmentName);
        String instance = TriggerService.instanceId(assignment.uid(), producer.path("executorId").asText());
        if (!instance.equals(producer.path("instanceId").asText()) || !"Running".equals(assignment.spec().path("desiredState").asText()))
            throw ManagementAccess.denied();

        var classification = io.ololabs.toolgate.contracts.v2.TriggerClassification.valueOf(log.path("classification").asText());
        var clearance = subscriptions.clearance(tx, sub.owner());
        if (!TriggerLabels.covers(clearance, classification)) throw ManagementAccess.denied();
        var event = TriggerLabels.view(stored.path("event"), classification, clearance);
        if (!TriggerSubscriptions.passes(TriggerSubscriptions.filter(sub, true), event)) throw ManagementAccess.denied();
        return new DispatchEvent(assignment.uid(), event);
    }

    String server(JsonNode message) {
        String text = codec.json(message);
        codec.model(text, TriggerStreamServerMessage.class);
        return text;
    }
    String error(int status, String code, String detail) {
        var message = JSON.createObjectNode().put("type", "error");
        message.putObject("error").put("status", Math.max(400, Math.min(599, status))).put("code", code).put("detail", detail == null ? "" : detail.length() > 512 ? detail.substring(0, 512) : detail);
        return server(message);
    }
    String revoked(String name, String reason) {
        var message = JSON.createObjectNode().put("type", "revoked").put("reason", reason);
        if (name != null) message.put("subscription", name);
        return server(message);
    }
    String gap(String name, long from, long to) {
        return server(JSON.createObjectNode().put("type", "gap").put("subscription", name).put("fromCursor", TriggerRouter.pad(from)).put("toCursor", TriggerRouter.pad(to)).put("reason", "RetentionExpired"));
    }
}
