// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.TriggerClassification;
import io.ololabs.toolgate.contracts.v2.TriggerSubscription;
import io.ololabs.toolgate.contracts.v2.TriggerSubscriptionCreate;
import io.ololabs.toolgate.contracts.v2.TriggerSubscriptionPage;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.ololabs.toolgate.control.application.TriggerStore.Kind.CURSOR;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.SUBSCRIPTION;

/**
 * {@code TriggerSubscription} (plan 13.1; triggers module 12): a durable, owner-scoped subscription to explicit sources.
 *
 * <ul>
 *   <li>Creating one needs {@code trigger.subscribe} on the targets of every source assignment. The owner is the
 *       caller; {@code expiresAtUnixMs} is required and at most 90 days ahead; 1000 subscriptions per tenant.</li>
 *   <li>The filter is compiled against the owner's readable view at create time (module 11): when the owner's clearance
 *       is below a source's event label, {@code event.data} and {@code event.subject} are not in the environment, so a
 *       filter that reads them is refused.</li>
 *   <li>{@link #matching} is the router's hook: an event gets a delivery for each Active, unexpired subscription naming
 *       its assignment whose filter matches the owner's current view. Grants are re-checked when the socket delivers.</li>
 * </ul>
 */
public final class TriggerSubscriptions implements TriggerRouter.Subscriptions {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_SUBSCRIPTIONS = 1000;
    static final long MAX_LIFETIME_MS = 90L * 86_400_000L;
    private static final Map<String, TriggerFilters.Filter> FILTERS = new ConcurrentHashMap<>();

    private final Store store;
    private final Codec codec;
    private final TriggerService triggers;
    private final Clock clock;
    private final Ids.TenantId tenant;
    private final SecureRandom random = new SecureRandom();

    public TriggerSubscriptions(Store store, Codec codec, TriggerService triggers, Clock clock, String tenant) {
        this.store = store; this.codec = codec; this.triggers = triggers; this.clock = clock; this.tenant = new Ids.TenantId(tenant);
    }

    record Sub(TriggerStore.Row row, ObjectNode document) {
        ObjectNode resource() { return (ObjectNode) document.get("subscription"); }
        boolean removed() { return document.path("removed").asBoolean(); }
        String name() { return resource().path("metadata").path("name").asText(); }
        String uid() { return resource().path("metadata").path("uid").asText(); }
        JsonNode spec() { return resource().path("spec"); }
        String owner() { return document.path("ownerUserId").asText(); }
        String phase() { return resource().path("status").path("phase").asText(); }
        List<String> sources() { return TriggerService.strings(spec().path("sources").path("assignments")); }
    }

    static String ownerOf(String userId) { return "user:" + userId; }

    Sub stored(Store.Session tx, String name) {
        if (name == null || !name.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")) throw TriggerService.notFound();
        var row = tx.triggers().get(SUBSCRIPTION, name);
        if (row == null) return null;
        var sub = new Sub(row, (ObjectNode) TriggerService.read(row.document()));
        return sub.removed() ? null : sub;
    }

    /** The owner's clearance now (module 11). */
    TriggerClassification clearance(Store.Session tx, String userId) { return TriggerLabels.clearance(tx.load(), codec, userId, null); }

    /** Whether the owner may read every source's event data at this clearance. */
    boolean readable(Store.Session tx, List<String> sources, TriggerClassification clearance) {
        var settings = TriggerRouter.settings(tx);
        for (String name : sources) {
            var stored = triggers.existing(tx, name);
            var definition = triggers.definition(tx, stored.spec().path("trigger").asText(), stored.spec().path("packageVersion").asText());
            if (!TriggerLabels.covers(clearance, TriggerLabels.eventLabel(definition.descriptor(), settings))) return false;
        }
        return true;
    }

    /** {@code trigger.subscribe} on every source's targets, for the owner. */
    void requireSources(Store.Session tx, DirectoryService.Actor owner, List<String> sources) {
        if (new java.util.HashSet<>(sources).size() != sources.size()) throw TriggerService.invalid("A source is listed twice");
        for (String name : sources) triggers.requireTargets(tx, owner, triggers.existing(tx, name).spec().path("target"), "trigger.subscribe");
    }

    public Store.Reply create(DirectoryService.Actor actor, String body, String key, String requestId) {
        triggers.tenantOf(actor);
        if (body == null || body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > 16384) throw TriggerService.invalid("A JSON body of at most 16 KiB is required");
        var create = codec.model(body, TriggerSubscriptionCreate.class);
        String digest = DirectoryService.digest("trigger.subscription.create\n" + body);
        return store.transaction(tenant, true, tx -> {
            long now = triggers.now(tx);
            if (key != null) {
                var replay = tx.replay(actor.id(), Ids.valid(key), digest);
                if (replay != null) return replay;
            }
            var spec = (ObjectNode) JSON.valueToTree(create.spec());
            String owner = ownerOf(actor.userId());
            if (spec.hasNonNull("owner") && !owner.equals(spec.path("owner").asText())) throw TriggerService.invalid("A subscription is owned by its caller");
            spec.put("owner", owner);
            long expires = spec.path("expiresAtUnixMs").asLong();
            if (expires <= now || expires > now + MAX_LIFETIME_MS) throw TriggerService.invalid("expiresAtUnixMs must be in the next 90 days");
            var sources = TriggerService.strings(spec.path("sources").path("assignments"));
            requireSources(tx, actor, sources);
            var clearance = clearance(tx, actor.userId());
            TriggerFilters.compile(spec.path("filter").asText(null), readable(tx, sources, clearance));
            var before = tx.triggers().get(SUBSCRIPTION, create.name());
            if (before != null && !TriggerService.read(before.document()).path("removed").asBoolean()) throw TriggerService.conflict("Trigger subscription " + create.name() + " exists");
            if (before == null && tx.triggers().count(SUBSCRIPTION) >= MAX_SUBSCRIPTIONS) throw TriggerService.conflict("At most " + MAX_SUBSCRIPTIONS + " trigger subscriptions per tenant");
            byte[] bytes = new byte[16]; random.nextBytes(bytes);
            long revision = before == null ? 1 : before.revision() + 1;
            var metadata = JSON.createObjectNode();
            metadata.put("name", create.name()).put("uid", "ts-" + HexFormat.of().formatHex(bytes)).put("generation", 1).put("configRevision", 1)
                .put("resourceVersion", TriggerService.etag(revision)).putArray("labels");
            metadata.put("createdAtUnixMs", now).put("updatedAtUnixMs", now);
            var resource = JSON.createObjectNode().put("apiVersion", TriggerService.API_VERSION).put("kind", "TriggerSubscription");
            resource.set("metadata", metadata);
            resource.set("spec", spec);
            resource.putObject("status").put("phase", "Active").put("clearance", clearance.name());
            var document = JSON.createObjectNode().put("ownerUserId", actor.userId()).put("removed", false);
            document.set("subscription", resource);
            var row = new TriggerStore.Row(create.name(), revision, codec.json(document));
            tx.triggers().save(SUBSCRIPTION, row, before == null ? 0 : before.revision());
            tx.audit(actor.id(), "TRIGGER_SUBSCRIPTION_CREATE", "trigger-subscription:" + create.name(), revision, requestId, digest);
            var reply = reply(201, view(tx, new Sub(row, document)), revision, TriggerSubscription.class);
            if (key != null) tx.remember(actor.id(), key, digest, reply);
            return reply;
        });
    }

    public Store.Reply get(DirectoryService.Actor actor, String name) {
        triggers.tenantOf(actor);
        return store.transaction(tenant, false, tx -> {
            var sub = owned(tx, actor, name);
            return reply(200, view(tx, sub), sub.row().revision(), TriggerSubscription.class);
        });
    }

    public Store.Reply list(DirectoryService.Actor actor, String pageToken, Integer pageSize) {
        triggers.tenantOf(actor);
        int size = TriggerService.pageSize(pageSize);
        String after = TriggerService.after(pageToken);
        return store.transaction(tenant, false, tx -> {
            var page = JSON.createObjectNode();
            ArrayNode items = page.putArray("items");
            String cursor = after;
            while (true) {
                var rows = tx.triggers().page(SUBSCRIPTION, "", cursor, 101);
                for (var row : rows) {
                    cursor = row.id();
                    var sub = new Sub(row, (ObjectNode) TriggerService.read(row.document()));
                    if (sub.removed() || !sub.owner().equals(actor.userId())) continue;
                    if (items.size() == size) { page.put("nextPageToken", TriggerService.token(items.get(size - 1).path("metadata").path("name").asText())); return reply(200, page, 0, TriggerSubscriptionPage.class); }
                    items.add(view(tx, sub));
                }
                if (rows.size() < 101) break;
            }
            return reply(200, page, 0, TriggerSubscriptionPage.class);
        });
    }

    /** Deletes a subscription and its cursor; its open socket is told {@code revoked} on its next turn. */
    public Store.Reply delete(DirectoryService.Actor actor, String name, String ifMatch, String requestId) {
        triggers.tenantOf(actor);
        return store.transaction(tenant, true, tx -> {
            var sub = owned(tx, actor, name);
            if (ifMatch == null) throw TriggerService.preconditionRequired();
            if (!ifMatch.equals(TriggerService.etag(sub.row().revision()))) throw TriggerService.stale();
            var document = sub.document().deepCopy();
            document.put("removed", true);
            ((ObjectNode) document.path("subscription").path("status")).put("phase", "Expired").put("reason", "Deleted");
            long revision = sub.row().revision() + 1;
            tx.triggers().save(SUBSCRIPTION, new TriggerStore.Row(name, revision, codec.json(document)), sub.row().revision());
            var cursor = tx.triggers().get(CURSOR, sub.uid());
            if (cursor != null) tx.triggers().delete(CURSOR, sub.uid(), cursor.revision());
            tx.audit(actor.id(), "TRIGGER_SUBSCRIPTION_DELETE", "trigger-subscription:" + name, revision, requestId, DirectoryService.digest("trigger.subscription.delete\n" + name));
            return new Store.Reply(204, "", revision);
        });
    }

    private Sub owned(Store.Session tx, DirectoryService.Actor actor, String name) {
        var sub = stored(tx, name);
        if (sub == null || !sub.owner().equals(actor.userId())) throw TriggerService.notFound();
        return sub;
    }

    ObjectNode view(Store.Session tx, Sub sub) {
        var resource = sub.resource().deepCopy();
        var cursor = tx.triggers().get(CURSOR, sub.uid());
        if (cursor != null) ((ObjectNode) resource.path("status")).put("ackedCursor", TriggerService.read(cursor.document()).path("acked").asText());
        if (sub.spec().path("expiresAtUnixMs").asLong() <= clock.millis() && "Active".equals(sub.phase()))
            ((ObjectNode) resource.path("status")).put("phase", "Expired").put("reason", "Expired");
        return resource;
    }

    /** Marks a subscription Suspended (a revoked grant) once; its socket gets {@code revoked}. */
    void suspend(Store.Session tx, Sub sub, String reason) {
        if (!"Active".equals(sub.phase())) return;
        var document = sub.document().deepCopy();
        ((ObjectNode) document.path("subscription").path("status")).put("phase", "Suspended").put("reason", reason);
        tx.triggers().save(SUBSCRIPTION, new TriggerStore.Row(sub.name(), sub.row().revision() + 1, codec.json(document)), sub.row().revision());
    }

    /** The owner as an actor for grant checks. */
    DirectoryService.Actor owner(Sub sub) {
        return new DirectoryService.Actor(tenant, DirectoryService.digest("trigger-subscription\n" + sub.owner()), true, false, sub.owner());
    }

    static TriggerFilters.Filter filter(Sub sub, boolean readable) {
        String expression = sub.spec().path("filter").asText(null);
        if (expression == null) return null;
        String key = sub.uid() + "/" + sub.row().revision() + "/" + readable;
        return FILTERS.computeIfAbsent(key, ignored -> {
            if (FILTERS.size() > 4096) FILTERS.clear();
            try { return TriggerFilters.compile(expression, readable); }
            catch (Failure refused) { return new TriggerFilters.Filter(expression, null); }
        });
    }

    /** True when an event's owner view passes the subscription's filter (a filter that no longer compiles never matches). */
    static boolean passes(TriggerFilters.Filter filter, ObjectNode view) { return filter == null || filter.program() != null && filter.matches(view); }

    @Override public List<TriggerRouter.Match> matching(Store.Session tx, TriggerRouter.Routed event) {
        var out = new ArrayList<TriggerRouter.Match>();
        long now = clock.millis();
        String cursor = "";
        while (true) {
            var rows = tx.triggers().page(SUBSCRIPTION, "", cursor, 101);
            for (var row : rows) {
                cursor = row.id();
                var sub = new Sub(row, (ObjectNode) TriggerService.read(row.document()));
                if (sub.removed() || !"Active".equals(sub.phase()) || sub.spec().path("expiresAtUnixMs").asLong() <= now) continue;
                if (!sub.sources().contains(event.assignment())) continue;
                if ("state".equals(event.event().path("tgkind").asText()) && !sub.spec().path("includeState").asBoolean()) continue;
                var clearance = clearance(tx, sub.owner());
                boolean readable = TriggerLabels.covers(clearance, event.label());
                if (!passes(filter(sub, readable), TriggerLabels.view(event.event(), event.label(), clearance))) continue;
                out.add(new TriggerRouter.Match(sub.name(), sub.uid()));
            }
            if (rows.size() < 101) break;
        }
        return out;
    }

    private Store.Reply reply(int status, JsonNode body, long revision, Class<?> contract) {
        String text = codec.json(body);
        codec.model(text, contract);
        return new Store.Reply(status, text, revision);
    }

}
