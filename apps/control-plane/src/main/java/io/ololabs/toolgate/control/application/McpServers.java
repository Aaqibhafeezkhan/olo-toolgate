// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.ControlExecutionBinding;
import io.ololabs.toolgate.contracts.EndpointDeviceRecord;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** MCP proxy registrations and their scans (docs/planning/mcp-proxy/plan.md §4–§7). Shared Control never contacts an
 *  upstream server: a scan runs on a Tool Host of the registration's pool, which is its execution binding's Device
 *  Group, under a scan permit with a signed deadline. Control keeps the registration, the immutable snapshots it
 *  accepted (recomputing every digest and limit) and the scan jobs, all in the routed tenant. */
public final class McpServers {
    public static final int MAX_SERVERS = 32, MAX_SNAPSHOTS = 16, MAX_OPEN_SCANS = 2;
    /** The signed scan deadline, the admission window of a scan permit, and how long a scan may wait for its host. */
    public static final long SCAN_MS = 30_000, PERMIT_MS = 10_000, QUEUED_MS = 60_000;
    // Plan §5 limits, enforced again on receipt.
    static final int MAX_TOOLS = 256, MAX_NAME = 128, MAX_DESCRIPTION = 8192, MAX_SCHEMA_BYTES = 65536, MAX_DEPTH = 32, MAX_MEMBERS = 256, MAX_ENUM = 256;
    static final int MAX_SCAN_BYTES = 4 << 20;
    static final Set<String> OPEN = Set.of("QUEUED", "CLAIMED", "ADMITTED");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Store store; private final Codec codec; private final EffectSigner signer; private final Clock clock;
    public McpServers(Store store, Codec codec, EffectSigner signer, Clock clock) { this.store = store; this.codec = codec; this.signer = signer; this.clock = clock; }

    private JsonNode node(String document) { return (JsonNode) codec.value(document); }
    private Store.Reply reply(int status, Object value) { return new Store.Reply(status, codec.json(value), 0); }
    private static Failure notFound() { return new Failure(ErrorCode.NOT_FOUND, 404, "MCP server not found"); }
    private static Failure busy(String message) { return new Failure(ErrorCode.CONFLICT, 409, message); }

    // ---- Authority: mcp_server.<action> over the binding's Tool Group and Device Group, read live.

    record Binding(String id, String toolGroupId, String deviceGroupId) {}
    private Binding binding(Store.Session tx, String bindingId) {
        var entry = bindingId == null ? null : tx.load().entries().get(Kind.BINDING.id(bindingId));
        if (entry == null || !entry.enabled()) throw ManagementAccess.denied();
        var binding = codec.model(entry.document(), ControlExecutionBinding.class);
        return new Binding(bindingId, binding.toolGroupId(), binding.deviceGroupId());
    }
    private void authorize(Store.Session tx, DirectoryService.Actor actor, String action, Binding binding) {
        var directory = tx.load(); var access = new ManagementAccess(codec); long now = clock.millis();
        access.require(directory, actor.userId(), "mcp_server." + action, Kind.TOOL_GROUP, binding.toolGroupId(), now);
        access.require(directory, actor.userId(), "mcp_server." + action, Kind.DEVICE_GROUP, binding.deviceGroupId(), now);
    }
    private boolean visible(Store.Session tx, DirectoryService.Actor actor, McpServerStore.Server server) {
        try { authorize(tx, actor, "read", binding(tx, node(server.document()).get("bindingId").asText())); return true; }
        catch (Failure denied) { return false; }
    }
    private McpServerStore.Server readable(Store.Session tx, DirectoryService.Actor actor, String slug) {
        var server = tx.mcpServers().server(slug);
        if (server == null || !visible(tx, actor, server)) throw notFound();
        return server;
    }

    // ---- Administrators

    public Store.Reply register(DirectoryService.Actor actor, String body, String key, String requestId) {
        actor.requireAdmin(); Ids.valid(key);
        var request = codec.model(body, McpServerRegistrationRequest.class);
        if (request.auth().scheme() == McpAuthScheme.HEADER && request.auth().header() == null) throw Failure.validation();
        String digest = DirectoryService.digest("MCP_SERVER_REGISTER\n" + codec.json(request));
        return store.transaction(actor.tenant(), true, tx -> {
            var binding = binding(tx, request.bindingId());
            authorize(tx, actor, "register", binding);
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            // The upstream credential belongs to exactly the binding's groups, the rule VaultService.deliver applies to calls.
            var secret = tx.enterprise().secret(request.auth().secretName());
            if (secret == null || !secret.toolGroupId().equals(binding.toolGroupId()) || !secret.deviceGroupId().equals(binding.deviceGroupId())) throw ManagementAccess.denied();
            var servers = tx.mcpServers();
            if (servers.server(request.slug()) != null) throw busy("An MCP server with this slug is registered");
            if (servers.count() >= MAX_SERVERS) throw busy("The tenant has the most MCP servers it may register");
            var registration = (ObjectNode) node(codec.json(request));
            registration.put("state", "REGISTERED").put("revision", 1).put("createdAtUnixMs", clock.millis());
            registration.putObject("verification").put("status", "UNVERIFIED");
            String document = codec.json(codec.model(registration.toString(), McpServerRegistration.class));
            servers.saveServer(new McpServerStore.Server(request.slug(), 1, document));
            tx.audit(actor.id(), "MCP_SERVER_REGISTERED", "mcp-server:" + request.slug(), 1, requestId, DirectoryService.digest(document));
            var reply = new Store.Reply(200, document, 1);
            tx.remember(actor.id(), key, digest, reply);
            return reply;
        });
    }

    public Store.Reply servers(DirectoryService.Actor actor) {
        actor.requireAdmin();
        return store.transaction(actor.tenant(), false, tx -> {
            var page = JSON.objectNode(); var items = page.putArray("items");
            for (var server : tx.mcpServers().servers(MAX_SERVERS)) if (visible(tx, actor, server)) items.add(node(server.document()));
            return reply(200, page);
        });
    }

    public Store.Reply server(DirectoryService.Actor actor, String slug) {
        actor.requireAdmin();
        return store.transaction(actor.tenant(), false, tx -> new Store.Reply(200, readable(tx, actor, slug).document(), 0));
    }

    public Store.Reply snapshot(DirectoryService.Actor actor, String slug, long revision) {
        actor.requireAdmin();
        return store.transaction(actor.tenant(), false, tx -> {
            readable(tx, actor, slug);
            var snapshot = tx.mcpServers().snapshot(slug, revision);
            if (snapshot == null) throw notFound();
            return new Store.Reply(200, snapshot.document(), 0);
        });
    }

    /** An administrator's DISCOVER scan: the only scan that connects without a pin, recording the observed one. */
    public Store.Reply scan(DirectoryService.Actor actor, String slug, String key, String requestId) {
        actor.requireAdmin(); Ids.valid(key);
        String digest = DirectoryService.digest("MCP_SERVER_SCAN\n" + slug);
        return store.transaction(actor.tenant(), true, tx -> {
            var server = readable(tx, actor, slug);
            var binding = binding(tx, node(server.document()).get("bindingId").asText());
            authorize(tx, actor, "register", binding);
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            var job = queue(tx, server, binding, McpScanPurpose.DISCOVER, List.of(), null, actor.id(), requestId);
            var reply = new Store.Reply(200, job, 0);
            tx.remember(actor.id(), key, digest, reply);
            return reply;
        });
    }

    /** Queues one scan on a live Tool Host of the registration's pool. One open scan per server, two per tenant. */
    String queue(Store.Session tx, McpServerStore.Server server, Binding binding, McpScanPurpose purpose, List<String> pins, Long approvalSequence, String actor, String requestId) {
        var servers = tx.mcpServers(); long now = clock.millis();
        if (!servers.scans(server.slug(), OPEN).isEmpty()) throw busy("A scan of this MCP server is open");
        var open = servers.open(64);
        if (open.size() >= MAX_OPEN_SCANS) throw busy("The tenant has the most open scans it may run");
        String host = pick(tx, binding, open);
        String scanId = "scan-" + UUID.randomUUID();
        var job = JSON.objectNode().put("scanId", scanId).put("slug", server.slug()).put("purpose", purpose.name()).put("state", "QUEUED")
            .put("hostId", host).put("createdAtUnixMs", now).put("deadlineUnixMs", now + QUEUED_MS);
        if (approvalSequence != null) job.put("approvalSequence", approvalSequence);
        var registration = (ObjectNode) node(server.document());
        var lease = JSON.objectNode().put("fencingToken", UUID.randomUUID().toString()).put("priorState", registration.get("state").asText());
        if (purpose == McpScanPurpose.DISCOVER && !registration.get("state").asText().equals("APPROVED") && !registration.get("state").asText().equals("QUARANTINED")) {
            registration.put("state", "SCANNING"); save(tx, registration);
        }
        // The scan is issued and its secret delivered only while the registration stays at this revision.
        lease.put("registrationRevision", registration.get("revision").asLong());
        var pinned = lease.putArray("spkiPins"); pins.forEach(pinned::add);
        saveScan(tx, job, lease);
        tx.audit(actor, "MCP_SCAN_QUEUED", "mcp-server:" + server.slug(), server.revision(), requestId, DirectoryService.digest(scanId));
        return codec.json(codec.model(job.toString(), McpScanJob.class));
    }

    /** A live member of the pool that is a registered Tool Host, the least busy first. */
    private String pick(Store.Session tx, Binding binding, List<McpServerStore.Scan> open) {
        var busy = new HashMap<String, Integer>(); open.forEach(s -> busy.merge(s.hostId(), 1, Integer::sum));
        return DeviceGroups.members(tx.load(), codec, binding.deviceGroupId()).stream().sorted()
            .filter(id -> tx.packages().toolHost(id) != null)
            .min(Comparator.comparingInt(id -> busy.getOrDefault(id, 0)))
            .orElseThrow(() -> busy("No registered Tool Host is a member of the binding's Device Group"));
    }

    // ---- Tool Hosts: the device certificate authenticates every call; only the scan's lease holder is answered.

    private EndpointDeviceRecord host(Store.Session tx, Function<Store.Session, EndpointDeviceRecord> device) {
        var d = device.apply(tx); if (tx.packages().toolHost(d.deviceId()) == null) throw ManagementAccess.denied(); return d;
    }
    record Lease(McpServerStore.Scan row, ObjectNode job, ObjectNode lease) {}
    private Lease lease(Store.Session tx, String hostId, String scanId) {
        var row = tx.mcpServers().scan(scanId);
        if (row == null) throw notFound();
        if (!row.hostId().equals(hostId)) throw ManagementAccess.denied();
        var document = node(row.document());
        return new Lease(row, (ObjectNode) document.get("job").deepCopy(), (ObjectNode) document.get("lease").deepCopy());
    }
    private void saveScan(Store.Session tx, ObjectNode job, ObjectNode lease) {
        codec.model(job.toString(), McpScanJob.class);
        var document = JSON.objectNode(); document.set("job", job); document.set("lease", lease);
        tx.mcpServers().saveScan(new McpServerStore.Scan(job.get("scanId").asText(), job.get("slug").asText(), job.get("hostId").asText(), job.get("state").asText(),
            job.get("deadlineUnixMs").asLong(), job.get("createdAtUnixMs").asLong(), document.toString()));
    }
    /** Saves the next revision of a registration. */
    private void save(Store.Session tx, ObjectNode registration) {
        long revision = registration.get("revision").asLong() + 1;
        registration.put("revision", revision);
        String document = codec.json(codec.model(registration.toString(), McpServerRegistration.class));
        tx.mcpServers().saveServer(new McpServerStore.Server(registration.get("slug").asText(), revision, document));
    }
    private boolean member(Store.Session tx, String hostId, ObjectNode registration) {
        var binding = binding(tx, registration.get("bindingId").asText());
        return DeviceGroups.members(tx.load(), codec, binding.deviceGroupId()).contains(hostId);
    }

    /** The host's oldest queued scan and its scan permit. Membership of the pool is checked again at issuance. */
    public Store.Reply next(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device) {
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); long now = clock.millis();
            var row = tx.mcpServers().nextScan(d.deviceId(), now);
            if (row == null) return new Store.Reply(204, null, 0);
            var scan = lease(tx, d.deviceId(), row.scanId());
            var server = tx.mcpServers().server(row.slug());
            var registration = server == null ? null : (ObjectNode) node(server.document());
            boolean current = registration != null && registration.get("revision").asLong() == scan.lease().get("registrationRevision").asLong();
            if (!current || !member(tx, d.deviceId(), registration)) {
                // The registration changed or the host left the pool: the scan is never issued.
                finish(tx, scan, "EXPIRED", null, now, DirectoryService.digest(d.deviceId()));
                return new Store.Reply(204, null, 0);
            }
            var claims = JSON.objectNode().put("jti", UUID.randomUUID().toString()).put("iss", "control").put("aud", d.deviceId())
                .put("iat", now).put("exp", now + PERMIT_MS).put("scanId", row.scanId()).put("slug", row.slug())
                .put("registrationRevision", server.revision()).put("bindingId", registration.get("bindingId").asText())
                .put("purpose", scan.job().get("purpose").asText()).put("url", registration.get("url").asText());
            claims.set("auth", registration.get("auth"));
            claims.set("spkiPins", scan.lease().get("spkiPins"));
            if (scan.job().has("approvalSequence")) claims.put("approvalSequence", scan.job().get("approvalSequence").asLong());
            claims.put("fencingToken", scan.lease().get("fencingToken").asText()).put("deadlineUnixMs", now + SCAN_MS);
            String permit = signer.signScan(codec.model(claims.toString(), McpScanClaims.class));
            scan.job().put("state", "CLAIMED").put("deadlineUnixMs", now + SCAN_MS);
            scan.lease().put("jti", claims.get("jti").asText()).put("permitExpiresAt", now + PERMIT_MS);
            saveScan(tx, scan.job(), scan.lease());
            tx.audit(DirectoryService.digest(d.deviceId()), "MCP_SCAN_CLAIMED", "mcp-server:" + row.slug(), server.revision(), row.scanId(), DirectoryService.digest(permit));
            return reply(200, JSON.objectNode().put("scanId", row.scanId()).put("permit", permit));
        });
    }

    /** The lease holder consumes the permit it verified, within its admission window and before the deadline. */
    public Store.Reply consume(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String scanId, String body) {
        var claims = codec.model(body, McpScanClaims.class);
        if (!claims.scanId().equals(scanId)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); long now = clock.millis();
            var scan = lease(tx, d.deviceId(), scanId);
            if (!scan.row().state().equals("CLAIMED")) throw Failure.conflict();
            if (!claims.jti().equals(scan.lease().path("jti").asText()) || !claims.fencingToken().equals(scan.lease().get("fencingToken").asText())
                || !claims.aud().equals(d.deviceId())) throw ManagementAccess.denied();
            if (now > scan.lease().get("permitExpiresAt").asLong() || now > scan.row().deadline()) throw Failure.conflict();
            scan.job().put("state", "ADMITTED");
            saveScan(tx, scan.job(), scan.lease());
            return reply(200, scan.job());
        });
    }

    /** What a scan may redeem: the registration's secret and its binding's groups, for the admitted lease holder only,
     *  before the deadline, while the registration and the host's pool membership are current. */
    public record ScanSecret(String secretName, String toolGroupId, String deviceGroupId, long revision) {}
    public ScanSecret secretAuthority(Store.Session tx, String deviceId, String scanId, String fencingToken) {
        var scan = lease(tx, deviceId, scanId); long now = clock.millis();
        if (!scan.row().state().equals("ADMITTED") || now > scan.row().deadline()) throw ManagementAccess.denied();
        if (fencingToken == null || !fencingToken.equals(scan.lease().get("fencingToken").asText())) throw ManagementAccess.denied();
        var server = tx.mcpServers().server(scan.row().slug());
        if (server == null || server.revision() != scan.lease().get("registrationRevision").asLong()) throw ManagementAccess.denied();
        var registration = (ObjectNode) node(server.document());
        if (!member(tx, deviceId, registration)) throw ManagementAccess.denied();
        var binding = binding(tx, registration.get("bindingId").asText());
        return new ScanSecret(registration.get("auth").get("secretName").asText(), binding.toolGroupId(), binding.deviceGroupId(), server.revision());
    }

    /** The lease holder's result, once and before the deadline. Control recomputes digests and enforces the limits again. */
    public Store.Reply result(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String scanId, String body) {
        var result = codec.model(body, McpScanResult.class);
        if (!result.scanId().equals(scanId)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); long now = clock.millis();
            var scan = lease(tx, d.deviceId(), scanId);
            admitted(scan, result.fencingToken(), now);
            String actor = DirectoryService.digest(d.deviceId());
            ArrayNode tools;
            try { tools = definitions(result); }
            catch (Limit exceeded) { return reply(200, finish(tx, scan, "FAILED", exceeded.failure, now, actor)); }
            var servers = tx.mcpServers(); var server = servers.server(scan.row().slug());
            var registration = (ObjectNode) node(server.document());
            long revision = registration.path("latestSnapshotRevision").asLong(0) + 1;
            var snapshot = JSON.objectNode().put("revision", revision).put("scanId", scanId);
            snapshot.set("serverInfo", node(codec.json(result.serverInfo())));
            snapshot.put("protocolVersion", result.protocolVersion()).put("spkiPin", result.spkiPin()).put("scannedAtUnixMs", now);
            snapshot.set("tools", tools);
            String document = codec.json(codec.model(snapshot.toString(), McpSnapshot.class));
            servers.saveSnapshot(new McpServerStore.Snapshot(server.slug(), revision, document));
            prune(tx, server.slug(), revision);
            scan.job().put("snapshotRevision", revision);
            registration.put("latestSnapshotRevision", revision);
            if (Set.of("REGISTERED", "SCANNING").contains(registration.get("state").asText())) registration.put("state", "SCANNED");
            save(tx, registration);
            tx.audit(actor, "MCP_SCAN_FINISHED", "mcp-server:" + server.slug(), revision, scanId, DirectoryService.digest(document));
            return reply(200, finish(tx, scan, "FINISHED", null, now, null));
        });
    }

    /** The lease holder reports that its scan failed, once and before the deadline. */
    public Store.Reply failure(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String scanId, String body) {
        var report = codec.model(body, McpScanFailureReport.class);
        if (!report.scanId().equals(scanId)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); long now = clock.millis();
            var scan = lease(tx, d.deviceId(), scanId);
            if (!Set.of("CLAIMED", "ADMITTED").contains(scan.row().state())) throw Failure.conflict();
            if (!report.fencingToken().equals(scan.lease().get("fencingToken").asText())) throw ManagementAccess.denied();
            if (now > scan.row().deadline()) throw Failure.conflict();
            return reply(200, finish(tx, scan, "FAILED", report.failure().name(), now, DirectoryService.digest(d.deviceId())));
        });
    }

    private static void admitted(Lease scan, String fencingToken, long now) {
        if (!scan.row().state().equals("ADMITTED")) throw Failure.conflict();
        if (!fencingToken.equals(scan.lease().get("fencingToken").asText())) throw ManagementAccess.denied();
        if (now > scan.row().deadline()) throw Failure.conflict();
    }

    /** Ends a scan. A DISCOVER scan that did not finish returns the registration to the state it had; a REFRESH that
     *  did not finish leaves its approval UNVERIFIED (a failed check). */
    private ObjectNode finish(Store.Session tx, Lease scan, String state, String failure, long now, String actor) {
        scan.job().put("state", state);
        if (failure != null) scan.job().put("failure", failure);
        saveScan(tx, scan.job(), scan.lease());
        if (!state.equals("FINISHED")) {
            var server = tx.mcpServers().server(scan.row().slug());
            if (server != null) {
                var registration = (ObjectNode) node(server.document());
                if (scan.job().get("purpose").asText().equals("DISCOVER")) {
                    if (registration.get("state").asText().equals("SCANNING")) registration.put("state", scan.lease().get("priorState").asText());
                } else {
                    var verification = registration.putObject("verification").put("status", "UNVERIFIED");
                    if (scan.job().has("approvalSequence")) verification.put("approvalSequence", scan.job().get("approvalSequence").asLong());
                    verification.put("checkedAtUnixMs", now).put("failure", failure == null ? "DEADLINE_EXCEEDED" : failure);
                }
                save(tx, registration);
            }
            if (actor != null) tx.audit(actor, "MCP_SCAN_" + state, "mcp-server:" + scan.row().slug(), 0, scan.row().scanId(), DirectoryService.digest(scan.job().toString()));
        }
        return scan.job();
    }

    /** Keeps the newest snapshots, and every snapshot an approval references. */
    private void prune(Store.Session tx, String slug, long newest) {
        var servers = tx.mcpServers(); var referenced = new HashSet<Long>();
        for (var approval : servers.approvals(slug, 64)) referenced.add(node(approval.document()).get("snapshotRevision").asLong());
        var revisions = servers.snapshotRevisions(slug);
        int kept = 0;
        for (long revision : revisions) {
            if (revision == newest || referenced.contains(revision) || ++kept < MAX_SNAPSHOTS) continue;
            servers.deleteSnapshot(slug, revision);
        }
    }

    /** Expires scans past their deadline; an expired refresh is a failed check. Returns how many expired. */
    public int sweep(Ids.TenantId tenant) {
        return store.transaction(tenant, true, tx -> {
            long now = clock.millis(); int expired = 0;
            for (var row : tx.mcpServers().open(256)) {
                if (row.deadline() >= now) continue;
                var document = node(row.document());
                finish(tx, new Lease(row, (ObjectNode) document.get("job").deepCopy(), (ObjectNode) document.get("lease").deepCopy()), "EXPIRED", null, now, "mcp-scan-watchdog");
                expired++;
            }
            tx.mcpServers().prune(now - 86_400_000L);
            return expired;
        });
    }

    // ---- Limits and digests

    static final class Limit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String failure;
        Limit(String failure) { super(failure, null, false, false); this.failure = failure; }
    }

    /** The snapshot's tools with recomputed definition digests, in upstream order, or a Limit. */
    private ArrayNode definitions(McpScanResult result) {
        var tools = result.tools();
        if (tools.size() > MAX_TOOLS) throw new Limit("SCAN_LIMIT_EXCEEDED");
        var names = new HashSet<String>(); var out = JSON.arrayNode(); long total = 0;
        for (var tool : tools) {
            var definition = node(codec.json(tool));
            String name = definition.get("name").asText();
            if (!names.add(name)) throw new Limit("PROTOCOL_ERROR");
            if (name.length() > MAX_NAME || definition.path("title").asText("").length() > MAX_NAME
                || definition.path("description").asText("").length() > MAX_DESCRIPTION) throw new Limit("SCAN_LIMIT_EXCEEDED");
            long schema = 0;
            for (var member : List.of("inputSchema", "outputSchema", "annotations")) if (definition.has(member)) {
                bounded(definition.get(member), 1);
                schema += definition.get(member).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if (schema > MAX_SCHEMA_BYTES) throw new Limit("SCAN_LIMIT_EXCEEDED");
            total += definition.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (total > MAX_SCAN_BYTES) throw new Limit("SCAN_LIMIT_EXCEEDED");
            var entry = out.addObject(); entry.set("tool", definition); entry.put("definitionDigest", McpDefinitions.digest(definition));
        }
        return out;
    }
    private static void bounded(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) throw new Limit("SCAN_LIMIT_EXCEEDED");
        if (node.isObject()) {
            if (node.size() > MAX_MEMBERS || node.path("enum").size() > MAX_ENUM) throw new Limit("SCAN_LIMIT_EXCEEDED");
            for (var child : node) bounded(child, depth + 1);
        } else if (node.isArray()) for (var child : node) bounded(child, depth + 1);
    }
}
