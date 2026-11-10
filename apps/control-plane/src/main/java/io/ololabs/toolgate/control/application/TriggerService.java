// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.EndpointDeviceRecord;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.TriggerAssignment;
import io.ololabs.toolgate.contracts.v2.TriggerAssignmentCreate;
import io.ololabs.toolgate.contracts.v2.TriggerDesiredDocument;
import io.ololabs.toolgate.contracts.v2.TriggerDesiredInstance;
import io.ololabs.toolgate.contracts.v2.TriggerDesiredState;
import io.ololabs.toolgate.contracts.v2.TriggerExecutor;
import io.ololabs.toolgate.contracts.v2.TriggerLease;
import io.ololabs.toolgate.contracts.v2.TriggerRevocation;
import io.ololabs.toolgate.contracts.v2.TriggerRevocationReason;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.ololabs.toolgate.control.application.TriggerStore.Kind.ASSIGNMENT;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.DESIRED;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.INSTANCE;
import static io.ololabs.toolgate.control.application.TriggerStore.Kind.LEASE;

/**
 * Control core for client triggers (issue #274, module 4; ADR 058; plan sections 7, 8.1, 9 and 10).
 *
 * <ul>
 *   <li><b>Definitions</b> are the trigger exports of the package versions this tenant uploaded, each with its security
 *       delta against the same trigger in the version uploaded before.</li>
 *   <li><b>Assignments</b> ({@code TriggerAssignment}) target devices and Device Groups. Every mutation is checked against
 *       {@code trigger.manage} on each target, takes {@code If-Match} and {@code Idempotency-Key}, supports a dry run,
 *       and commits with its audit record.</li>
 *   <li><b>Instances</b> are one per assignment and resolved device. A device's resolved set is recomputed from the
 *       current assignments and group membership, so devices that join a group later get its instances; any change
 *       advances that device's desired generation.</li>
 *   <li><b>The desired document</b> is signed per device by the organization deployment key (plan 8.1).</li>
 *   <li><b>Client leases</b> (plan 7): a monotonic epoch per instance and a 256-bit bearer token, stored only as its
 *       digest. A lease authorizes running the trigger and reporting its events, nothing else.</li>
 * </ul>
 *
 * Unknown and other tenants' assignments give the same 404. Behind {@code toolgate.triggers.control-core.enabled}.
 */
public final class TriggerService {
    public static final String API_VERSION = "toolgate.olo/v1";
    static final long MAX = 9007199254740991L;
    /** Bounds that keep every resolution and document inside the contract (plan 8.5): a client holds at most 32 instances. */
    static final int MAX_ASSIGNMENTS = 256, MAX_INSTANCES_PER_ASSIGNMENT = 4096, MAX_INSTANCES_PER_CLIENT = 32;
    static final int MAX_CONFIG_BYTES = 8192, MAX_BODY_BYTES = 65536, MAX_PAGE = 200, DEFAULT_PAGE = 50;
    /** A client lease lasts as long as the desired document, so a device keeps observing offline (plan 7). */
    static final long DESIRED_TTL_MS = 24L * 60 * 60 * 1000;
    static final long STALE_MIN_MS = 5 * 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Store store;
    private final Codec codec;
    private final TriggerCrypto crypto;
    private final EndpointService endpoints;
    private final Clock clock;
    private final boolean enabled;
    private final Ids.TenantId tenant;

    public TriggerService(Store store, Codec codec, TriggerCrypto crypto, EndpointService endpoints, Clock clock, boolean enabled, String tenant) {
        this.store = store;
        this.codec = codec;
        this.crypto = crypto;
        this.endpoints = endpoints;
        this.clock = clock;
        this.enabled = enabled && crypto != null;
        this.tenant = new Ids.TenantId(tenant);
    }

    // ---- failures ----

    void available() { if (!enabled) throw Failure.unavailable(); }
    public boolean enabled() { return enabled; }
    static Failure notFound() { return new Failure(ErrorCode.NOT_FOUND, 404, "Trigger resource not found"); }
    static Failure invalid(String message) { return new Failure(ErrorCode.VALIDATION, 400, message); }
    static Failure stale() { return new Failure(ErrorCode.CONFLICT, 412, "The resource changed; read it again and retry with its ETag"); }
    static Failure preconditionRequired() { return new Failure(ErrorCode.CONFLICT, 428, "If-Match with the resource's ETag is required"); }
    static Failure conflict(String message) { return new Failure(ErrorCode.CONFLICT, 409, message); }

    /** Another tenant's actor sees exactly what an unknown name gives. */
    void tenantOf(DirectoryService.Actor actor) {
        available();
        actor.requireAdmin();
        if (!actor.tenant().equals(tenant)) throw notFound();
    }

    long now(Store.Session tx) {
        long now = clock.millis();
        if (now < 0 || now > MAX - DESIRED_TTL_MS || tx.approvalClock(now) > now) throw Failure.unavailable();
        return now;
    }

    // ---- definitions ----

    /** One trigger export of a stored package version. */
    record Definition(String triggerId, String packageId, String version, String packageDigest, String descriptorDigest, JsonNode descriptor, long uploadedAt) {}

    /** Every trigger export of this tenant's stored package versions, oldest upload first. */
    List<Definition> definitions(Store.Session tx) {
        var out = new ArrayList<Definition>();
        for (var version : tx.packages().versions(100)) {
            JsonNode document = read(version.document());
            for (JsonNode trigger : document.path("manifest").path("triggers")) {
                JsonNode descriptor = trigger.path("descriptor");
                out.add(new Definition(descriptor.path("triggerId").asText(), version.packageId(), version.version(), version.digest(),
                        trigger.path("triggerDescriptorDigest").asText(), descriptor, version.uploadedAt()));
            }
        }
        out.sort((a, b) -> a.uploadedAt() != b.uploadedAt() ? Long.compare(a.uploadedAt(), b.uploadedAt()) : a.packageDigest().compareTo(b.packageDigest()));
        return out;
    }

    Definition definition(Store.Session tx, String triggerId, String version) {
        Definition found = null;
        for (var d : definitions(tx)) if (d.triggerId().equals(triggerId) && d.version().equals(version)) found = d;
        if (found == null) throw invalid("No uploaded package version " + version + " exports trigger " + triggerId);
        return found;
    }

    public Store.Reply definitions(DirectoryService.Actor actor, String pageToken, Integer pageSize) {
        return definitions(actor, null, pageToken, pageSize);
    }

    public Store.Reply definitions(DirectoryService.Actor actor, String deviceId, String pageToken, Integer pageSize) {
        tenantOf(actor);
        int size = pageSize(pageSize);
        String after = after(pageToken);
        return store.transaction(tenant, false, tx -> {
            var access = new ManagementAccess(codec);
            var all = definitions(tx);
            if (deviceId == null) {
                access.requireAll(tx.load(), actor.userId(), "trigger.read", clock.millis());
            } else {
                var device = tx.load().entries().get(Ids.Kind.DEVICE.id(Ids.valid(deviceId)));
                access.requireEntry(tx.load(), actor.userId(), "trigger.read", device, clock.millis());
                var row = tx.fleet().get(FleetStore.Kind.DESIRED, deviceId);
                var assigned = row == null ? Set.<String>of()
                        : codec.model(row.document(), io.ololabs.toolgate.contracts.FleetDesiredSnapshot.class)
                            .assignments().stream().filter(a -> a.desiredPresence())
                            .map(a -> a.release().packageId() + "@" + a.release().version())
                            .collect(java.util.stream.Collectors.toSet());
                all = all.stream().filter(d -> assigned.contains(d.packageId() + "@" + d.version())
                        && strings(d.descriptor().path("executors")).contains("CLIENT")).toList();
            }
            var previous = new HashMap<String, Definition>();
            var records = new TreeMap<String, ObjectNode>();
            for (var d : all) {
                records.put(d.triggerId() + "@" + d.packageDigest(), record(d, previous.get(d.triggerId())));
                previous.put(d.triggerId(), d);
            }
            var page = JSON.createObjectNode();
            ArrayNode items = page.putArray("items");
            String last = null;
            for (var e : records.tailMap(after, false).entrySet()) {
                if (items.size() == size) {
                    page.put("nextPageToken", token(last));
                    break;
                }
                items.add(e.getValue());
                last = e.getKey();
            }
            return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerDefinitionPage.class);
        });
    }

    private ObjectNode record(Definition d, Definition previous) {
        var record = JSON.createObjectNode();
        record.put("triggerId", d.triggerId()).put("packageId", d.packageId()).put("packageVersion", d.version())
                .put("packageDigest", d.packageDigest()).put("triggerDescriptorDigest", d.descriptorDigest());
        record.set("descriptor", d.descriptor());
        record.set("securityDelta", securityDelta(d, previous));
        return record;
    }

    /**
     * What changed in a trigger's reviewable capabilities against the same trigger in the version uploaded before
     * (platform-requirements.md §8.3 kinds). A first appearance is one widening KIND item plus its permissions.
     */
    static ArrayNode securityDelta(Definition d, Definition previous) {
        var items = JSON.createArrayNode();
        JsonNode now = d.descriptor();
        JsonNode before = previous == null ? null : previous.descriptor();
        String id = d.triggerId();
        if (before == null) {
            delta(items, "KIND", id, true, "New " + now.path("mode").asText() + " " + now.path("kind").asText() + " export on "
                    + String.join(", ", strings(now.path("executors"))) + "; source " + now.path("sourceReplay").asText());
        } else {
            if (!now.path("mode").equals(before.path("mode")) || !now.path("kind").equals(before.path("kind"))) {
                delta(items, "KIND", id, true, "Changed to " + now.path("mode").asText() + " " + now.path("kind").asText());
            }
            var added = new TreeSet<>(strings(now.path("executors")));
            added.removeAll(strings(before.path("executors")));
            if (!added.isEmpty()) delta(items, "KIND", id, true, "Runs on more executors: " + String.join(", ", added));
        }
        for (String field : List.of("permissions", "routes", "tcpDestinations", "secrets", "tiers")) {
            var widened = new TreeSet<>(strings(now.path(field)));
            if (before != null) widened.removeAll(strings(before.path(field)));
            String kind = switch (field) {
                case "permissions" -> "PERMISSION";
                case "routes" -> "ROUTE";
                case "tcpDestinations" -> "DESTINATION";
                case "secrets" -> "SECRET";
                default -> "RUNTIME_TIER";
            };
            for (String value : widened) delta(items, kind, id, true, "Adds " + field + " " + value);
        }
        if (!"BLOCK".equals(now.path("overflow").asText()) && (before == null || "BLOCK".equals(before.path("overflow").asText()))) {
            delta(items, "UNCLASSIFIED", id, true, "Drops events on overflow (" + now.path("overflow").asText() + ")");
        }
        if ("SKIP_AND_RECORD".equals(now.path("onTerminalRejection").asText()) && (before == null || !"SKIP_AND_RECORD".equals(before.path("onTerminalRejection").asText()))) {
            delta(items, "UNCLASSIFIED", id, true, "Skips terminally rejected events and records the loss");
        }
        if (before != null && !now.path("eventType").equals(before.path("eventType"))) {
            delta(items, "UNCLASSIFIED", id, true, "Event type " + before.path("eventType").asText() + " becomes " + now.path("eventType").asText());
        }
        if (before != null && now.path("limits").path("maxEventsPerHour").asLong() > before.path("limits").path("maxEventsPerHour").asLong()) {
            delta(items, "UNCLASSIFIED", id, true, "Raises maxEventsPerHour to " + now.path("limits").path("maxEventsPerHour").asLong());
        }
        while (items.size() > 64) items.remove(items.size() - 1);
        return items;
    }

    private static void delta(ArrayNode items, String kind, String id, boolean widening, String summary) {
        items.addObject().put("kind", kind).put("toolId", id).put("widening", widening).put("summary", summary.length() > 1024 ? summary.substring(0, 1024) : summary);
    }

    // ---- assignments ----

    /** The stored form of an assignment: the resource plus what Control resolved when it was written. */
    record Stored(TriggerStore.Row row, ObjectNode document) {
        ObjectNode assignment() { return (ObjectNode) document.get("assignment"); }
        boolean removed() { return document.path("removed").asBoolean(); }
        String uid() { return assignment().path("metadata").path("uid").asText(); }
        String name() { return assignment().path("metadata").path("name").asText(); }
        JsonNode spec() { return assignment().path("spec"); }
    }

    private Stored stored(Store.Session tx, String name) {
        if (name == null || !name.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")) throw notFound();
        var row = tx.triggers().get(ASSIGNMENT, name);
        if (row == null) return null;
        return new Stored(row, (ObjectNode) read(row.document()));
    }

    Stored existing(Store.Session tx, String name) {
        var stored = stored(tx, name);
        if (stored == null || stored.removed()) throw notFound();
        return stored;
    }

    public Store.Reply create(DirectoryService.Actor actor, String body, String key, boolean dryRun, String requestId) {
        tenantOf(actor);
        String text = body(body);
        var create = codec.model(text, TriggerAssignmentCreate.class);
        String digest = DirectoryService.digest("trigger.create\n" + text);
        return store.transaction(tenant, true, tx -> {
            long now = now(tx);
            var directory = tx.load();
            ObjectNode spec = (ObjectNode) JSON.valueToTree(create.spec());
            requireTargets(tx, actor, spec.path("target"), "trigger.manage");
            if (!dryRun && key != null) {
                var replay = tx.replay(actor.id(), Ids.valid(key), digest);
                if (replay != null) return replay;
            }
            var before = stored(tx, create.name());
            if (before != null && !before.removed()) throw conflict("Trigger assignment " + create.name() + " exists");
            if (before == null && tx.triggers().count(ASSIGNMENT) >= MAX_ASSIGNMENTS) throw conflict("At most " + MAX_ASSIGNMENTS + " trigger assignments per tenant");
            var definition = validateSpec(tx, spec);
            String uid = "ta-" + DirectoryService.digest(tenant.value() + "\n" + create.name() + "\n" + now + "\n" + randomToken()).substring(0, 32);
            var metadata = JSON.createObjectNode();
            metadata.put("name", create.name()).put("uid", uid).put("generation", 1).put("configRevision", 1)
                    .put("resourceVersion", etag(before == null ? 1 : before.row().revision() + 1));
            metadata.set("labels", labels(create.labels() == null ? JSON.createArrayNode() : JSON.valueToTree(create.labels())));
            metadata.put("createdAtUnixMs", now).put("updatedAtUnixMs", now);
            var assignment = JSON.createObjectNode();
            assignment.put("apiVersion", API_VERSION).put("kind", "TriggerAssignment");
            assignment.set("metadata", metadata);
            assignment.set("spec", spec);
            var document = document(assignment, definition, false);
            var row = new TriggerStore.Row(create.name(), before == null ? 1 : before.row().revision() + 1, codec.json(document));
            var changes = reconcile(tx, directory, before == null || before.removed() ? null : before, new Stored(row, document), now, dryRun);
            if (!dryRun) {
                tx.triggers().save(ASSIGNMENT, row, before == null ? 0 : before.row().revision());
                tx.audit(actor.id(), "TRIGGER_ASSIGNMENT_CREATE", "trigger-assignment:" + create.name(), row.revision(), requestId, digest);
            }
            var result = result(tx, new Stored(row, document), dryRun, changes, dryRun ? 200 : 201);
            if (!dryRun && key != null) tx.remember(actor.id(), key, digest, result);
            return result;
        });
    }

    public Store.Reply get(DirectoryService.Actor actor, String name) {
        tenantOf(actor);
        return store.transaction(tenant, false, tx -> {
            var stored = existing(tx, name);
            requireTargets(tx, actor, stored.spec().path("target"), "trigger.read");
            return reply(200, withStatus(tx, stored), stored.row().revision(), TriggerAssignment.class);
        });
    }

    public Store.Reply list(DirectoryService.Actor actor, String pageToken, Integer pageSize, String labelSelector) {
        tenantOf(actor);
        int size = pageSize(pageSize);
        String after = after(pageToken);
        var selector = selector(labelSelector);
        return store.transaction(tenant, false, tx -> {
            var page = JSON.createObjectNode();
            ArrayNode items = page.putArray("items");
            String cursor = after;
            while (true) {
                var rows = tx.triggers().page(ASSIGNMENT, "", cursor, 101);
                for (var row : rows) {
                    cursor = row.id();
                    var stored = new Stored(row, (ObjectNode) read(row.document()));
                    if (stored.removed() || !matches(stored, selector)) continue;
                    try {
                        requireTargets(tx, actor, stored.spec().path("target"), "trigger.read");
                    } catch (Failure denied) {
                        if (denied.status() != 403) throw denied;
                        continue;
                    }
                    if (items.size() == size) {
                        page.put("nextPageToken", token(items.get(items.size() - 1).path("metadata").path("name").asText()));
                        return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerAssignmentPage.class);
                    }
                    items.add(withStatus(tx, stored));
                }
                if (rows.size() < 101) break;
            }
            return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerAssignmentPage.class);
        });
    }

    /** JSON Merge Patch (RFC 7386) of {@code labels} and {@code spec}; a spec change starts a new generation. */
    public Store.Reply patch(DirectoryService.Actor actor, String name, String body, String ifMatch, String key, boolean dryRun, String requestId) {
        tenantOf(actor);
        String text = body(body);
        JsonNode patch = read(text);
        if (!patch.isObject()) throw invalid("A merge patch is a JSON object");
        for (var field : iterable(patch.fieldNames())) {
            if (!field.equals("labels") && !field.equals("spec")) throw invalid("Only labels and spec can be patched; " + field + " is server-owned");
        }
        String digest = DirectoryService.digest("trigger.patch\n" + name + "\n" + ifMatch + "\n" + text);
        return store.transaction(tenant, true, tx -> {
            long now = now(tx);
            var directory = tx.load();
            var before = existing(tx, name);
            requireTargets(tx, actor, before.spec().path("target"), "trigger.manage");
            if (!dryRun && key != null) {
                var replay = tx.replay(actor.id(), Ids.valid(key), digest);
                if (replay != null) return replay;
            }
            match(before, ifMatch);
            var current = JSON.createObjectNode();
            current.set("labels", before.assignment().path("metadata").path("labels").deepCopy());
            current.set("spec", before.spec().deepCopy());
            JsonNode merged = merge(current, patch);
            var create = codec.model(codec.json(Map.of("name", name, "labels", merged.path("labels").isArray() ? merged.path("labels") : JSON.createArrayNode(),
                    "spec", merged.path("spec"))), TriggerAssignmentCreate.class);
            ObjectNode spec = (ObjectNode) JSON.valueToTree(create.spec());
            requireTargets(tx, actor, spec.path("target"), "trigger.manage");
            var definition = validateSpec(tx, spec);
            var metadata = before.assignment().path("metadata").deepCopy();
            var meta = (ObjectNode) metadata;
            boolean specChanged = !spec.equals(before.spec());
            boolean configChanged = !spec.path("trigger").equals(before.spec().path("trigger")) || !spec.path("packageVersion").equals(before.spec().path("packageVersion"))
                    || !spec.path("config").equals(before.spec().path("config")) || !spec.path("globals").equals(before.spec().path("globals"));
            if (specChanged) meta.put("generation", next(meta.path("generation").asLong()));
            if (configChanged) meta.put("configRevision", next(meta.path("configRevision").asLong()));
            meta.set("labels", labels(JSON.valueToTree(create.labels() == null ? List.of() : create.labels())));
            long revision = before.row().revision() + 1;
            meta.put("resourceVersion", etag(revision)).put("updatedAtUnixMs", now);
            var assignment = before.assignment().deepCopy();
            assignment.set("metadata", meta);
            assignment.set("spec", spec);
            var document = document(assignment, definition, false);
            var row = new TriggerStore.Row(name, revision, codec.json(document));
            var changes = reconcile(tx, directory, before, new Stored(row, document), now, dryRun);
            if (!dryRun) {
                tx.triggers().save(ASSIGNMENT, row, before.row().revision());
                tx.audit(actor.id(), "TRIGGER_ASSIGNMENT_UPDATE", "trigger-assignment:" + name, revision, requestId, digest);
            }
            var result = result(tx, new Stored(row, document), dryRun, changes, 200);
            if (!dryRun && key != null) tx.remember(actor.id(), key, digest, result);
            return result;
        });
    }

    /** Removes the assignment everywhere: its instances are removed and their leases revoked (plan 10.3 Remove). */
    public Store.Reply delete(DirectoryService.Actor actor, String name, String ifMatch, String key, boolean dryRun, String requestId) {
        tenantOf(actor);
        String digest = DirectoryService.digest("trigger.delete\n" + name + "\n" + ifMatch);
        return store.transaction(tenant, true, tx -> {
            long now = now(tx);
            var directory = tx.load();
            var before = existing(tx, name);
            requireTargets(tx, actor, before.spec().path("target"), "trigger.manage");
            if (!dryRun && key != null) {
                var replay = tx.replay(actor.id(), Ids.valid(key), digest);
                if (replay != null) return replay;
            }
            match(before, ifMatch);
            long revision = before.row().revision() + 1;
            var assignment = before.assignment().deepCopy();
            ((ObjectNode) assignment.get("metadata")).put("resourceVersion", etag(revision)).put("updatedAtUnixMs", now);
            var document = before.document().deepCopy();
            document.set("assignment", assignment);
            document.put("removed", true);
            var row = new TriggerStore.Row(name, revision, codec.json(document));
            var after = new Stored(row, document);
            var changes = reconcile(tx, directory, before, after, now, dryRun);
            if (!dryRun) {
                tx.triggers().save(ASSIGNMENT, row, before.row().revision());
                tx.audit(actor.id(), "TRIGGER_ASSIGNMENT_DELETE", "trigger-assignment:" + name, revision, requestId, digest);
            }
            var result = result(tx, after, dryRun, changes, 200);
            if (!dryRun && key != null) tx.remember(actor.id(), key, digest, result);
            return result;
        });
    }

    private ObjectNode document(ObjectNode assignment, Definition definition, boolean removed) {
        var document = JSON.createObjectNode();
        document.set("assignment", assignment);
        document.put("packageDigest", definition.packageDigest()).put("triggerDescriptorDigest", definition.descriptorDigest());
        document.put("removed", removed);
        return document;
    }

    private void match(Stored before, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw preconditionRequired();
        if (!ifMatch.strip().equals(etag(before.row().revision()))) throw stale();
    }

    static String etag(long revision) { return "W/\"" + revision + "\""; }

    private static long next(long value) {
        if (value >= MAX) throw Failure.conflict();
        return value + 1;
    }

    /** The definition the spec names, a CLIENT target this SDK version can run, and a config that matches its schema. */
    private Definition validateSpec(Store.Session tx, ObjectNode spec) {
        var target = spec.path("target");
        if (!"CLIENT".equals(target.path("executor").asText())) {
            throw invalid("Only CLIENT targets are supported: server trigger assignments need Tool Host placement");
        }
        var devices = strings(target.path("devices"));
        var groups = strings(target.path("deviceGroups"));
        var excluded = strings(target.path("excludeDevices"));
        for (var list : List.of(devices, groups, excluded)) if (new HashSet<>(list).size() != list.size()) throw invalid("Target entries are listed twice");
        if (devices.isEmpty() && groups.isEmpty()) throw invalid("A client target names devices or Device Groups");
        var definition = definition(tx, spec.path("trigger").asText(), spec.path("packageVersion").asText());
        if (!strings(definition.descriptor().path("executors")).contains("CLIENT")) throw invalid("Trigger " + definition.triggerId() + " cannot run on CLIENT executors");
        if (!spec.has("config") || !spec.path("config").isObject()) throw invalid("config is a JSON object");
        if (bytes(spec.path("config")) > MAX_CONFIG_BYTES) throw invalid("config is larger than " + MAX_CONFIG_BYTES + " bytes");
        if (!spec.has("globals")) spec.set("globals", JSON.createObjectNode());
        if (bytes(spec.path("globals")) > MAX_CONFIG_BYTES) throw invalid("globals is larger than " + MAX_CONFIG_BYTES + " bytes");
        String problem = TriggerConfigs.validate(definition.descriptor().path("configSchema"), spec.path("config"));
        if (problem != null) throw invalid("config does not match the trigger's configSchema: " + problem);
        return definition;
    }

    void requireTargets(Store.Session tx, DirectoryService.Actor actor, JsonNode target, String permission) {
        var directory = tx.load();
        var access = new ManagementAccess(codec);
        long now = clock.millis();
        for (String group : strings(target.path("deviceGroups"))) {
            if (directory.entries().get(Ids.Kind.DEVICE_GROUP.id(group)) == null) throw invalid("Unknown Device Group " + group);
            access.require(directory, actor.userId(), permission, Ids.Kind.DEVICE_GROUP, group, now);
        }
        var devices = new LinkedHashSet<String>();
        devices.addAll(strings(target.path("devices")));
        devices.addAll(strings(target.path("excludeDevices")));
        for (String device : devices) {
            var entry = directory.entries().get(Ids.Kind.DEVICE.id(device));
            if (entry == null) throw invalid("Unknown device " + device);
            access.requireEntry(directory, actor.userId(), permission, entry, now);
        }
    }

    // ---- instances and desired state ----

    static String instanceId(String uid, String device) {
        return "ti-" + DirectoryService.digest(uid + "\n" + device).substring(0, 32);
    }

    /** The devices an assignment resolves to now: its devices and the members of its enabled groups, minus exclusions. */
    private Set<String> devices(io.ololabs.toolgate.control.domain.Directory directory, Stored stored) {
        var out = new TreeSet<String>();
        if (stored == null || stored.removed()) return out;
        var target = stored.spec().path("target");
        for (String device : strings(target.path("devices"))) if (directory.entries().containsKey(Ids.Kind.DEVICE.id(device))) out.add(device);
        for (String group : strings(target.path("deviceGroups"))) {
            var entry = directory.entries().get(Ids.Kind.DEVICE_GROUP.id(group));
            if (entry == null || !entry.enabled()) continue;
            out.addAll(GroupGraph.members(entry, codec));
        }
        strings(target.path("excludeDevices")).forEach(out::remove);
        out.removeIf(d -> !directory.entries().containsKey(Ids.Kind.DEVICE.id(d)));
        return out;
    }

    /** Every active assignment of the tenant, with an override for the one being written. */
    List<Stored> assignments(Store.Session tx, Stored override) {
        var out = new ArrayList<Stored>();
        String cursor = "";
        while (true) {
            var rows = tx.triggers().page(ASSIGNMENT, "", cursor, 101);
            for (var row : rows) {
                cursor = row.id();
                var stored = override != null && row.id().equals(override.name()) ? override : new Stored(row, (ObjectNode) read(row.document()));
                if (!stored.removed()) out.add(stored);
            }
            if (rows.size() < 101) break;
        }
        if (override != null && !override.removed() && out.stream().noneMatch(s -> s.name().equals(override.name()))) out.add(override);
        return out;
    }

    /** The resolved instance of one assignment on one device, as the desired document carries it. */
    private ObjectNode desiredInstance(Stored stored, String device) {
        var spec = stored.spec();
        var instance = JSON.createObjectNode();
        instance.put("instanceId", instanceId(stored.uid(), device));
        instance.put("triggerId", spec.path("trigger").asText());
        instance.put("packageDigest", stored.document().path("packageDigest").asText());
        instance.put("triggerDescriptorDigest", stored.document().path("triggerDescriptorDigest").asText());
        instance.put("configRevision", stored.assignment().path("metadata").path("configRevision").asLong());
        instance.set("config", spec.path("config"));
        instance.put("bindingDigest", "sha256:" + DirectoryService.digest(codec.json(Map.of("globals", spec.path("globals")))));
        // Overrides at group, device or instance scope arrive with lifecycle actions; until then the assignment decides.
        instance.put("desiredState", spec.path("desiredState").asText());
        return instance;
    }

    /** A device's resolved instances, sorted by instance id. */
    private List<ObjectNode> resolved(io.ololabs.toolgate.control.domain.Directory directory, List<Stored> all, String device) {
        var out = new TreeMap<String, ObjectNode>();
        for (var stored : all) if (devices(directory, stored).contains(device)) {
            var instance = desiredInstance(stored, device);
            out.put(instance.path("instanceId").asText(), instance);
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Writes what a mutation from {@code before} to {@code after} changes: the instance rows of the devices either
     * resolves to, the desired generation of every device whose resolved set changed, and leases of removed or stopped
     * instances. Returns the instance changes, in instance order. With {@code dryRun}, nothing is written.
     */
    private List<ObjectNode> reconcile(Store.Session tx, io.ololabs.toolgate.control.domain.Directory directory, Stored before, Stored after, long now, boolean dryRun) {
        var oldDevices = devices(directory, before);
        var newDevices = devices(directory, after);
        if (newDevices.size() > MAX_INSTANCES_PER_ASSIGNMENT) throw conflict("An assignment resolves to at most " + MAX_INSTANCES_PER_ASSIGNMENT + " devices");
        var all = assignments(tx, after);
        var affected = new TreeSet<String>(oldDevices);
        affected.addAll(newDevices);
        var changes = new TreeMap<String, ObjectNode>();
        String uid = after.uid();
        for (String device : affected) {
            var instances = resolved(directory, all, device);
            if (instances.size() > MAX_INSTANCES_PER_CLIENT) throw conflict("Device " + device + " would hold more than " + MAX_INSTANCES_PER_CLIENT + " trigger instances");
            String id = instanceId(uid, device);
            String oldState = oldDevices.contains(device) && before != null ? before.spec().path("desiredState").asText() : null;
            String newState = newDevices.contains(device) ? after.spec().path("desiredState").asText() : null;
            var change = JSON.createObjectNode().put("instanceId", id).put("executorId", device);
            if (oldState == null) change.put("change", "Added");
            else if (newState == null) change.put("change", "Removed");
            else change.put("change", Objects.equals(desiredDigest(before, device), desiredDigest(after, device)) ? "Unchanged" : "Updated");
            if (oldState != null) change.put("oldDesiredState", oldState);
            if (newState != null) change.put("newDesiredState", newState);
            changes.put(id, change);
            if (dryRun) continue;
            writeInstance(tx, after, device, newState == null, now);
            refreshDesired(tx, device, instances);
            if (newState == null) revoke(tx, device, id, TriggerRevocationReason.AssignmentRemoved);
            else if (newState.equals("Stopped")) revoke(tx, device, id, TriggerRevocationReason.AssignmentStopped);
        }
        return new ArrayList<>(changes.values());
    }

    private String desiredDigest(Stored stored, String device) {
        return stored == null || stored.removed() ? null : DirectoryService.digest(codec.json(desiredInstance(stored, device)));
    }

    private void writeInstance(Store.Session tx, Stored stored, String device, boolean removed, long now) {
        String recordId = stored.uid() + "/" + device;
        var row = tx.triggers().get(INSTANCE, recordId);
        var view = JSON.createObjectNode();
        var spec = stored.spec();
        view.put("instanceId", instanceId(stored.uid(), device)).put("assignment", stored.name()).put("triggerId", spec.path("trigger").asText())
                .put("executor", "CLIENT").put("executorId", device)
                .put("packageDigest", stored.document().path("packageDigest").asText())
                .put("triggerDescriptorDigest", stored.document().path("triggerDescriptorDigest").asText())
                .put("configRevision", stored.assignment().path("metadata").path("configRevision").asLong())
                .put("desiredState", spec.path("desiredState").asText()).put("desiredStateSource", "Assignment");
        // Executors report phases from check-in ingest (module 5); until then an instance waits for its executor.
        String phase = removed ? "Removed" : row == null ? "Pending" : read(row.document()).path("view").path("phase").asText("Pending");
        if (!removed && "Removed".equals(phase)) phase = "Pending";
        view.put("phase", phase);
        view.set("conditions", row == null ? JSON.createArrayNode() : read(row.document()).path("view").path("conditions").deepCopy());
        // What the executor last reported (module 5 ingest) survives a change of the assignment.
        var old = row == null ? JSON.createObjectNode() : read(row.document());
        for (String reported : List.of("counters", "outbox", "lastEventAt", "lastReconcileAt")) if (!removed && old.path("view").has(reported)) view.set(reported, old.path("view").get(reported).deepCopy());
        var document = JSON.createObjectNode();
        document.set("view", view);
        document.put("updatedAtUnixMs", now);
        if (old.has("lastSeenAtUnixMs")) document.put("lastSeenAtUnixMs", old.path("lastSeenAtUnixMs").asLong());
        if (!removed && old.path("haltedByControl").asBoolean()) document.put("haltedByControl", true);
        String text = codec.json(document);
        if (row != null && read(row.document()).path("view").equals(view)) return;
        tx.triggers().save(INSTANCE, new TriggerStore.Row(recordId, row == null ? 1 : row.revision() + 1, text), row == null ? 0 : row.revision());
    }

    /** Advances the device's desired generation when its resolved set changed; returns the current generation. */
    private long refreshDesired(Store.Session tx, String device, List<ObjectNode> instances) {
        String digest = DirectoryService.digest(codec.json(instances));
        var row = tx.triggers().get(DESIRED, device);
        if (row != null && read(row.document()).path("digest").asText().equals(digest)) return row.revision();
        long generation = row == null ? 1 : row.revision() + 1;
        var document = JSON.createObjectNode().put("executorId", device).put("generation", generation).put("digest", digest);
        tx.triggers().save(DESIRED, new TriggerStore.Row(device, generation, codec.json(document)), row == null ? 0 : row.revision());
        return generation;
    }

    public Store.Reply instances(DirectoryService.Actor actor, String assignment, String executorId, String pageToken, Integer pageSize) {
        tenantOf(actor);
        int size = pageSize(pageSize);
        String after = after(pageToken);
        return store.transaction(tenant, false, tx -> {
            var page = JSON.createObjectNode();
            ArrayNode items = page.putArray("items");
            var names = new ArrayList<Stored>();
            if (assignment != null) names.add(existing(tx, assignment));
            else names.addAll(assignments(tx, null));
            names.sort(java.util.Comparator.comparing(Stored::uid));
            String last = null;
            for (var stored : names) {
                try {
                    requireTargets(tx, actor, stored.spec().path("target"), "trigger.read");
                } catch (Failure denied) {
                    if (denied.status() != 403 || assignment != null) throw denied;
                    continue;
                }
                String cursor = after.startsWith(stored.uid() + "/") ? after : after.compareTo(stored.uid() + "/") > 0 ? null : "";
                if (cursor == null) continue;
                while (true) {
                    var rows = tx.triggers().page(INSTANCE, stored.uid() + "/", cursor, 101);
                    for (var row : rows) {
                        cursor = row.id();
                        var view = read(row.document()).path("view");
                        if (executorId != null && !executorId.equals(view.path("executorId").asText())) continue;
                        if (items.size() == size) {
                            page.put("nextPageToken", token(last));
                            return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerInstancePage.class);
                        }
                        last = row.id();
                        var item = (ObjectNode) view.deepCopy();
                        var lease = tx.triggers().get(LEASE, leaseId(view.path("executorId").asText(), view.path("instanceId").asText()));
                        if (lease != null && "ACTIVE".equals(read(lease.document()).path("state").asText())) item.put("leaseEpoch", read(lease.document()).path("leaseEpoch").asLong());
                        item.put("lastSeenAtUnixMs", read(row.document()).path("lastSeenAtUnixMs").asLong(0));
                        if (item.path("lastSeenAtUnixMs").asLong() == 0) item.remove("lastSeenAtUnixMs");
                        stale(item, clock.millis());
                        items.add(item);
                    }
                    if (rows.size() < 101) break;
                }
            }
            return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerInstancePage.class);
        });
    }

    /**
     * The signed desired trigger state of the calling device (plan 8.1). Instances of groups the device joined since
     * the last assignment change are materialized here, and the generation advances whenever the resolved set changes.
     */
    public Store.Reply desired(java.security.cert.X509Certificate peer) {
        available();
        return store.transaction(tenant, true, tx -> {
            long now = now(tx);
            var device = endpoints.authenticate(tx, peer, now);
            var document = desiredDocument(tx, device, now);
            return new Store.Reply(200, codec.json(crypto.desired(document)), document.generation());
        });
    }

    /** The signed desired document of a device this transaction already authenticated (the route above, and check-in). */
    public io.ololabs.toolgate.contracts.v2.SignedTriggerDesiredDocument desired(Store.Session tx, EndpointDeviceRecord device) {
        available();
        long now = now(tx);
        endpoints.active(tx, device);
        return crypto.desired(desiredDocument(tx, device, now));
    }

    TriggerDesiredDocument desiredDocument(Store.Session tx, EndpointDeviceRecord device, long now) {
        var directory = tx.load();
        var all = assignments(tx, null);
        var instances = resolved(directory, all, device.deviceId());
        if (instances.size() > MAX_INSTANCES_PER_CLIENT) throw Failure.conflict();
        for (var stored : all) if (devices(directory, stored).contains(device.deviceId())) writeInstance(tx, stored, device.deviceId(), false, now);
        long generation = refreshDesired(tx, device.deviceId(), instances);
        var desired = new ArrayList<TriggerDesiredInstance>();
        for (var instance : instances) desired.add(codec.model(codec.json(instance), TriggerDesiredInstance.class));
        return new TriggerDesiredDocument(1L, tenant.value(), device.deviceId(), TriggerExecutor.CLIENT, generation, now, now + DESIRED_TTL_MS, desired);
    }

    // ---- leases ----

    /** The leases a device holds after this call, and those revoked since its instances changed (plan 8.3 leases, revoked). */
    public record Leases(List<TriggerLease> leases, List<TriggerRevocation> revoked) {}

    /**
     * Issues or renews the run lease of every Running or Paused instance the device resolves to, and lists the revoked
     * leases of instances it no longer runs. Called inside the check-in's transaction (module 5) with its request id.
     * A new epoch starts on first issue, after a release or revocation, after expiry, and when the config revision
     * changes; renewal keeps the epoch and rotates the bearer token, of which only the digest is stored.
     */
    public Leases leases(Store.Session tx, EndpointDeviceRecord device, String requestId) {
        available();
        long now = now(tx);
        endpoints.active(tx, device);
        var document = desiredDocument(tx, device, now);
        var leases = new ArrayList<TriggerLease>();
        var current = new HashSet<String>();
        for (var instance : document.instances()) {
            current.add(instance.instanceId());
            if (instance.desiredState() == TriggerDesiredState.Stopped) continue;
            leases.add(issue(tx, device, instance, document.expiresAtUnixMs(), now, requestId));
        }
        var revoked = new ArrayList<TriggerRevocation>();
        String cursor = "";
        while (true) {
            var rows = tx.triggers().page(LEASE, device.deviceId() + "/", cursor, 101);
            for (var row : rows) {
                cursor = row.id();
                var lease = read(row.document());
                String instanceId = lease.path("instanceId").asText();
                if (current.contains(instanceId) && "ACTIVE".equals(lease.path("state").asText())) continue;
                if ("REVOKED".equals(lease.path("state").asText()) && !lease.path("reported").asBoolean()) {
                    revoked.add(new TriggerRevocation(instanceId, TriggerRevocationReason.valueOf(lease.path("reason").asText())));
                    var reported = ((ObjectNode) lease.deepCopy()).put("reported", true);
                    tx.triggers().save(LEASE, new TriggerStore.Row(row.id(), row.revision() + 1, codec.json(reported)), row.revision());
                }
            }
            if (rows.size() < 101) break;
        }
        return new Leases(leases, revoked);
    }

    private TriggerLease issue(Store.Session tx, EndpointDeviceRecord device, TriggerDesiredInstance instance, long expiresAt, long now, String requestId) {
        var row = tx.triggers().get(LEASE, leaseId(device.deviceId(), instance.instanceId()));
        var previous = row == null ? null : read(row.document());
        long epoch;
        if (previous == null) epoch = 1;
        else {
            boolean renewable = "ACTIVE".equals(previous.path("state").asText()) && previous.path("executorId").asText().equals(device.deviceId())
                    && previous.path("expiresAtUnixMs").asLong() > now && previous.path("configRevision").asLong() == instance.configRevision();
            epoch = renewable ? previous.path("leaseEpoch").asLong() : next(previous.path("leaseEpoch").asLong());
        }
        String token = randomToken();
        var lease = JSON.createObjectNode();
        lease.put("instanceId", instance.instanceId()).put("executorId", device.deviceId()).put("leaseEpoch", epoch)
                .put("configRevision", instance.configRevision()).put("tokenDigest", DirectoryService.digest(token))
                .put("expiresAtUnixMs", expiresAt).put("issuedAtUnixMs", now).put("state", "ACTIVE");
        // An epoch that ends without a revocation drains its queued records for 7 days (plan 6.6.1): remember when.
        var ended = previous == null || !previous.path("endedEpochs").isArray() ? JSON.createArrayNode() : (ArrayNode) previous.path("endedEpochs").deepCopy();
        if (previous != null && previous.path("leaseEpoch").asLong() != epoch && "ACTIVE".equals(previous.path("state").asText())) ended(ended, previous.path("leaseEpoch").asLong(), now);
        lease.set("endedEpochs", ended);
        tx.triggers().save(LEASE, new TriggerStore.Row(leaseId(device.deviceId(), instance.instanceId()), row == null ? 1 : row.revision() + 1, codec.json(lease)), row == null ? 0 : row.revision());
        if (previous == null || previous.path("leaseEpoch").asLong() != epoch) {
            tx.audit(device.keyFingerprint(), "TRIGGER_LEASE_ISSUE", "trigger-instance:" + instance.instanceId(), epoch, requestId,
                    DirectoryService.digest(instance.instanceId() + "\n" + epoch));
        }
        return new TriggerLease(instance.instanceId(), epoch, token, expiresAt);
    }

    /** Ends a lease; the device learns of it on its next lease exchange. */
    private void revoke(Store.Session tx, String device, String instanceId, TriggerRevocationReason reason) {
        var row = tx.triggers().get(LEASE, leaseId(device, instanceId));
        if (row == null) return;
        var lease = read(row.document());
        if (!"ACTIVE".equals(lease.path("state").asText())) return;
        var revoked = ((ObjectNode) lease.deepCopy()).put("state", "REVOKED").put("reason", reason.name()).put("reported", false);
        ended(revoked.withArray("endedEpochs"), lease.path("leaseEpoch").asLong(), clock.millis());
        tx.triggers().save(LEASE, new TriggerStore.Row(row.id(), row.revision() + 1, codec.json(revoked)), row.revision());
    }

    /** Records when an epoch ended, keeping the 16 most recent. */
    private static void ended(ArrayNode array, long epoch, long now) {
        for (var item : array) if (item.path("leaseEpoch").asLong() == epoch) return;
        array.addObject().put("leaseEpoch", epoch).put("endedAtUnixMs", now);
        while (array.size() > 16) array.remove(0);
    }

    /** A client lease is device-bound (plan 7), so its record sits under the device. */
    static String leaseId(String device, String instanceId) { return device + "/" + instanceId; }

    /**
     * Whether a request carries the current lease of an instance for this executor (plan 7): the lease is active and
     * unexpired, the epoch is the current one and the token matches its digest. Lower epochs are always refused.
     */
    public boolean leaseValid(Store.Session tx, String executorId, String instanceId, long leaseEpoch, String token) {
        available();
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) return false;
        var row = tx.triggers().get(LEASE, leaseId(executorId, instanceId));
        if (row == null) return false;
        var lease = read(row.document());
        return "ACTIVE".equals(lease.path("state").asText()) && lease.path("executorId").asText().equals(executorId)
                && lease.path("leaseEpoch").asLong() == leaseEpoch && lease.path("expiresAtUnixMs").asLong() > clock.millis()
                && MessageDigest.isEqual(lease.path("tokenDigest").asText().getBytes(StandardCharsets.US_ASCII),
                        DirectoryService.digest(token).getBytes(StandardCharsets.US_ASCII));
    }

    // ---- module 5: what ingest and the device need ----

    /** One instance a device resolves to now, with its assignment and the signed definition it runs. */
    record Resolved(Stored stored, String recordId, TriggerDesiredInstance desired, Definition definition) {}

    /** The device's resolved instances by instance id; materializes them as the desired document does. */
    Map<String, Resolved> resolvedOn(Store.Session tx, EndpointDeviceRecord device, long now) {
        var document = desiredDocument(tx, device, now);
        var directory = tx.load();
        var byId = new HashMap<String, Stored>();
        for (var stored : assignments(tx, null)) if (devices(directory, stored).contains(device.deviceId())) byId.put(instanceId(stored.uid(), device.deviceId()), stored);
        var definitions = definitions(tx);
        var out = new LinkedHashMap<String, Resolved>();
        for (var desired : document.instances()) {
            var stored = byId.get(desired.instanceId());
            Definition definition = null;
            for (var d : definitions) if (d.triggerId().equals(desired.triggerId()) && d.packageDigest().equals(desired.packageDigest())) definition = d;
            if (stored != null && definition != null) out.put(desired.instanceId(), new Resolved(stored, stored.uid() + "/" + device.deviceId(), desired, definition));
        }
        return out;
    }

    /** The desired generation of a device (zero before its first desired document). */
    long generation(Store.Session tx, String device) {
        var row = tx.triggers().get(DESIRED, device);
        return row == null ? 0 : row.revision();
    }

    /**
     * The signed descriptor of a trigger the calling device runs, by its digest (plan 5.9). The device checks the digest
     * against its signed desired document and package release; anything it does not resolve to is not found.
     */
    public Store.Reply descriptor(java.security.cert.X509Certificate peer, String digest) {
        available();
        if (digest == null || !digest.matches("^sha256:[a-f0-9]{64}$")) throw notFound();
        return store.transaction(tenant, true, tx -> {
            long now = now(tx);
            var device = endpoints.authenticate(tx, peer, now);
            for (var resolved : resolvedOn(tx, device, now).values()) {
                if (resolved.definition().descriptorDigest().equals(digest)) return new Store.Reply(200, codec.json(resolved.definition().descriptor()), 0);
            }
            throw notFound();
        });
    }

    /** One page of an instance's stored records, in (leaseEpoch, outboxSeq) order. Readers need trigger.read on its targets. */
    public Store.Reply events(DirectoryService.Actor actor, String assignment, String instanceId, String pageToken, Integer pageSize) {
        tenantOf(actor);
        if (instanceId == null || !instanceId.matches("^ti-[a-f0-9]{32}$")) throw notFound();
        int size = pageSize(pageSize);
        String after = after(pageToken);
        return store.transaction(tenant, false, tx -> {
            var stored = existing(tx, assignment);
            requireTargets(tx, actor, stored.spec().path("target"), "trigger.read");
            boolean found = false;
            String cursor = "";
            while (!found) {
                var rows = tx.triggers().page(INSTANCE, stored.uid() + "/", cursor, 101);
                for (var row : rows) {
                    cursor = row.id();
                    if (read(row.document()).path("view").path("instanceId").asText().equals(instanceId)) found = true;
                }
                if (rows.size() < 101) break;
            }
            if (!found) throw notFound();
            var page = JSON.createObjectNode();
            var items = page.putArray("items");
            var rows = tx.triggers().page(TriggerStore.Kind.EVENT, instanceId + "/", after.isEmpty() ? "" : instanceId + "/" + after, size + 1);
            for (var row : rows.subList(0, Math.min(size, rows.size()))) items.add(read(row.document()));
            if (rows.size() > size) page.put("nextPageToken", token(rows.get(size - 1).id().substring(instanceId.length() + 1)));
            return reply(200, page, 0, io.ololabs.toolgate.contracts.v2.TriggerEventPage.class);
        });
    }

    /**
     * Control derives {@code Stale=True} after 3 missed check-ins or 5 minutes, whichever is longer (plan 9.2), so a
     * disconnected executor is never shown as running. It changes only what is shown, never the lease.
     */
    void stale(ObjectNode view, long now) {
        if ("Removed".equals(view.path("phase").asText())) return;
        long window = Math.max(STALE_MIN_MS, 3 * endpoints.checkInPolicy().nextIntervalMs());
        long seen = view.path("lastSeenAtUnixMs").asLong(0);
        boolean stale = seen == 0 ? !"Pending".equals(view.path("phase").asText()) : now - seen > window;
        var conditions = view.withArray("conditions");
        for (int i = conditions.size() - 1; i >= 0; i--) if ("Stale".equals(conditions.get(i).path("type").asText())) conditions.remove(i);
        conditions.addObject().put("type", "Stale").put("status", stale ? "True" : "False").put("reason", stale ? "MissedCheckIns" : "CheckingIn")
                .put("lastTransitionTime", Instant.ofEpochMilli(stale && seen > 0 ? seen + window : Math.max(seen, 0)).toString());
    }

    // ---- results, status and plumbing ----

    private Store.Reply result(Store.Session tx, Stored stored, boolean dryRun, List<ObjectNode> changes, int status) {
        var result = JSON.createObjectNode();
        result.set("assignment", withStatus(tx, stored));
        result.put("dryRun", dryRun);
        result.set("changes", JSON.valueToTree(changes));
        return reply(status, result, stored.row().revision(), io.ololabs.toolgate.contracts.v2.TriggerAssignmentResult.class);
    }

    /** The resource with its status computed from its instance rows (plan 9.1). */
    private ObjectNode withStatus(Store.Session tx, Stored stored) {
        var assignment = stored.assignment().deepCopy();
        var counts = new LinkedHashMap<String, Long>();
        for (String phase : List.of("desired", "pending", "starting", "running", "paused", "stopped", "failed", "unsupported", "stale")) counts.put(phase, 0L);
        String cursor = "";
        while (true) {
            var rows = tx.triggers().page(INSTANCE, stored.uid() + "/", cursor, 101);
            for (var row : rows) {
                cursor = row.id();
                var view = read(row.document()).path("view");
                String phase = view.path("phase").asText().toLowerCase(java.util.Locale.ROOT);
                if (phase.equals("removed")) continue;
                counts.merge("desired", 1L, Long::sum);
                if (counts.containsKey(phase)) counts.merge(phase, 1L, Long::sum);
            }
            if (rows.size() < 101) break;
        }
        var status = JSON.createObjectNode();
        status.put("observedGeneration", assignment.path("metadata").path("generation").asLong());
        status.set("instances", JSON.valueToTree(counts));
        long desired = counts.get("desired"), running = counts.get("running");
        String reason = stored.removed() ? "AssignmentRemoved" : desired == 0 ? "NoTargets" : running == desired ? "AllInstancesRunning" : "InstancesPending";
        status.putArray("conditions").addObject().put("type", "Available").put("status", !stored.removed() && desired > 0 && running == desired ? "True" : "False")
                .put("reason", reason).put("lastTransitionTime", Instant.ofEpochMilli(assignment.path("metadata").path("updatedAtUnixMs").asLong()).toString());
        assignment.set("status", status);
        return assignment;
    }

    private Store.Reply reply(int status, JsonNode body, long revision, Class<?> contract) {
        String text = codec.json(body);
        codec.model(text, contract);
        return new Store.Reply(status, text, revision);
    }

    private static String body(String body) {
        if (body == null || body.isBlank()) throw invalid("A JSON body is required");
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) throw invalid("The body is larger than " + MAX_BODY_BYTES + " bytes");
        return body;
    }

    private JsonNode labels(JsonNode labels) {
        var keys = new HashSet<String>();
        for (var label : labels) if (!keys.add(label.path("key").asText())) throw invalid("Label " + label.path("key").asText() + " is given twice");
        return labels;
    }

    private static Map<String, String> selector(String selector) {
        var out = new TreeMap<String, String>();
        if (selector == null || selector.isBlank()) return out;
        if (selector.length() > 512) throw invalid("labelSelector is too long");
        for (String term : selector.split(",")) {
            int eq = term.indexOf('=');
            if (eq <= 0) throw invalid("labelSelector terms are key=value");
            out.put(term.substring(0, eq).strip(), term.substring(eq + 1).strip());
        }
        return out;
    }

    private static boolean matches(Stored stored, Map<String, String> selector) {
        var labels = new HashMap<String, String>();
        for (var label : stored.assignment().path("metadata").path("labels")) labels.put(label.path("key").asText(), label.path("value").asText());
        for (var term : selector.entrySet()) if (!term.getValue().equals(labels.get(term.getKey()))) return false;
        return true;
    }

    static int pageSize(Integer size) {
        if (size == null) return DEFAULT_PAGE;
        if (size < 1 || size > MAX_PAGE) throw invalid("pageSize is 1.." + MAX_PAGE);
        return size;
    }

    static String token(String after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(after.getBytes(StandardCharsets.UTF_8));
    }

    static String after(String token) {
        if (token == null) return "";
        if (!token.matches("^[A-Za-z0-9_-]{1,256}$")) throw invalid("Invalid pageToken");
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid pageToken");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int bytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node).length;
        } catch (java.io.IOException e) {
            throw Failure.validation();
        }
    }

    static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        for (var v : array) out.add(v.asText());
        return out;
    }

    static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw invalid("Invalid JSON");
        }
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    /** RFC 7386: objects merge member by member, null removes, anything else replaces. */
    static JsonNode merge(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) return patch.deepCopy();
        var result = target != null && target.isObject() ? ((ObjectNode) target).deepCopy() : JSON.createObjectNode();
        for (var field : patch.properties()) {
            if (field.getValue().isNull()) result.remove(field.getKey());
            else result.set(field.getKey(), merge(result.get(field.getKey()), field.getValue()));
        }
        return result;
    }
}
