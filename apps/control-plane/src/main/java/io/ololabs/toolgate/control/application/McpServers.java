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

/** MCP proxy registrations and their scans (ADR 030). Shared Control never contacts an
 *  upstream server: a scan runs on a Tool Host of the registration's pool, which is its execution binding's Device
 *  Group, under a scan permit with a signed deadline. Control keeps the registration, the immutable snapshots it
 *  accepted (recomputing every digest and limit) and the scan jobs, all in the routed tenant. */
public final class McpServers {
    public static final int MAX_SERVERS = 32, MAX_SNAPSHOTS = 16, MAX_OPEN_SCANS = 2;
    /** The signed scan deadline, the admission window of a scan permit, and how long a scan may wait for its host. */
    public static final long SCAN_MS = 30_000, PERMIT_MS = 10_000, QUEUED_MS = 60_000;
    /** How often an approved server is refreshed at most (plan §7, Refresh). */
    public static final long REFRESH_MS = 60_000;
    // Plan §5 limits, enforced again on receipt.
    static final int MAX_TOOLS = 256, MAX_NAME = 128, MAX_DESCRIPTION = 8192, MAX_SCHEMA_BYTES = 65536, MAX_DEPTH = 32, MAX_MEMBERS = 256, MAX_ENUM = 256;
    static final int MAX_SCAN_BYTES = 4 << 20;
    static final Set<String> OPEN = Set.of("QUEUED", "CLAIMED", "ADMITTED");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Store store; private final Codec codec; private final EffectSigner signer; private final Clock clock;
    private final ServerPackages packages; private final EnterpriseOperations operations;
    public McpServers(Store store, Codec codec, EffectSigner signer, Clock clock, ServerPackages packages, EnterpriseOperations operations) {
        this.store = store; this.codec = codec; this.signer = signer; this.clock = clock; this.packages = packages; this.operations = operations;
    }

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
            // A slug registered again continues its approval sequence, so a package version is never reused.
            var history = servers.approvals(request.slug(), 1);
            if (!history.isEmpty()) registration.put("approvalSequence", history.getFirst().sequence());
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

    public Store.Reply approvals(DirectoryService.Actor actor, String slug) {
        actor.requireAdmin();
        return store.transaction(actor.tenant(), false, tx -> {
            readable(tx, actor, slug);
            var page = JSON.objectNode(); var items = page.putArray("items");
            for (var approval : tx.mcpServers().approvals(slug, 64)) items.add(node(approval.document()));
            return reply(200, page);
        });
    }

    /** Approves at most 64 tools of one snapshot as the next approval sequence (plan §5): generates the tenant's
     *  MCP_PROXY package, imports it through the package pipeline and deploys it to the registration's pool, retiring
     *  the tools the approval omits. An approval identical in effect to the current one returns the current one. */
    public Store.Reply approve(DirectoryService.Actor actor, String slug, String body, String key, String requestId) {
        actor.requireAdmin(); Ids.valid(key);
        var request = codec.model(body, McpApprovalRequest.class);
        String digest = DirectoryService.digest("MCP_SERVER_APPROVE\n" + slug + "\n" + codec.json(request));
        return store.transaction(actor.tenant(), true, tx -> {
            var server = readable(tx, actor, slug); var registration = (ObjectNode) node(server.document());
            var binding = binding(tx, registration.get("bindingId").asText());
            authorize(tx, actor, "register", binding);
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            var servers = tx.mcpServers();
            var stored = servers.snapshot(slug, request.snapshotRevision());
            if (stored == null) throw notFound();
            var snapshot = node(stored.document());
            var definitions = new HashMap<String, JsonNode>();
            for (var tool : snapshot.get("tools")) definitions.put(tool.get("tool").get("name").asText(), tool);
            var approved = JSON.arrayNode(); var names = new HashSet<String>(); var upstream = new HashSet<String>();
            for (var tool : request.tools()) {
                var definition = definitions.get(tool.upstreamName());
                // Each upstream tool once, each local name once; names are never silently changed.
                if (definition == null || !upstream.add(tool.upstreamName()) || !names.add(tool.name())) throw Failure.validation();
                String toolId = "mcp." + slug + "/" + tool.name();
                if (("mcp." + slug + "." + tool.name()).length() > 128) throw Failure.validation();
                approved.addObject().put("toolId", toolId).put("upstreamName", tool.upstreamName()).put("definitionDigest", definition.get("definitionDigest").asText());
            }
            long sequence = registration.path("approvalSequence").asLong(0);
            if (registration.get("state").asText().equals("APPROVED") && sequence > 0) {
                var current = node(servers.approval(slug, sequence).document());
                if (current.get("snapshotRevision").asLong() == request.snapshotRevision() && set(current.get("tools")).equals(set(approved))) {
                    var reply = new Store.Reply(200, codec.json(current), 0);
                    tx.remember(actor.id(), key, digest, reply);
                    return reply;
                }
            }
            sequence++;
            var pins = JSON.arrayNode().add(snapshot.get("spkiPin").asText());
            var proxy = JSON.objectNode().put("formatVersion", 1);
            var target = proxy.putObject("server").put("slug", slug).put("url", registration.get("url").asText());
            target.set("auth", registration.get("auth")); target.set("serverInfo", snapshot.get("serverInfo")); target.set("spkiPins", pins);
            proxy.put("approvalSequence", sequence).put("strict", registration.get("strict").asBoolean());
            proxy.set("tools", approved);
            codec.model(proxy.toString(), McpProxyDocument.class);
            byte[] data = McpPackages.generate(slug, sequence, snapshot, definitions, request.tools(), proxy);
            var verified = PackageArchive.validate(data, this::node);
            PackageCompatibility.require(PackageCompatibility.problems(verified.manifest()));
            packages.imported(tx, actor.id(), verified, data, requestId);
            var deployment = JSON.objectNode().put("packageDigest", verified.packageDigest()); deployment.putObject("globals");
            deployment.putArray("deviceGroupIds").add(binding.deviceGroupId());
            packages.deployIn(tx, actor.id(), codec.model(deployment.toString(), PackageDeploymentRequest.class), requestId);
            var approval = JSON.objectNode().put("sequence", sequence).put("snapshotRevision", request.snapshotRevision());
            approval.set("tools", approved); approval.set("spkiPins", pins);
            approval.put("packageId", "mcp." + slug).put("packageVersion", "1.0." + sequence).put("packageDigest", verified.packageDigest()).put("approvedAtUnixMs", clock.millis());
            String document = codec.json(codec.model(approval.toString(), McpApproval.class));
            servers.saveApproval(new McpServerStore.Approval(slug, sequence, document));
            registration.put("approvalSequence", sequence).put("state", "APPROVED");
            registration.remove("quarantine");
            // The snapshot it approves is the first check of this approval sequence.
            registration.putObject("verification").put("status", "CURRENT").put("approvalSequence", sequence).put("checkedAtUnixMs", snapshot.get("scannedAtUnixMs").asLong());
            save(tx, registration);
            tx.audit(actor.id(), "MCP_SERVER_APPROVED", "mcp-server:" + slug, sequence, requestId, DirectoryService.digest(document));
            var reply = new Store.Reply(200, document, 0);
            tx.remember(actor.id(), key, digest, reply);
            return reply;
        });
    }
    private static Set<JsonNode> set(JsonNode array) { var out = new HashSet<JsonNode>(); array.forEach(out::add); return out; }

    /** Unregisters a server: its package is undeployed and its tools disabled, its snapshots and open scans end, and
     *  its approvals stay as history. */
    public Store.Reply unregister(DirectoryService.Actor actor, String slug, String key, String requestId) {
        actor.requireAdmin(); Ids.valid(key);
        String digest = DirectoryService.digest("MCP_SERVER_UNREGISTER\n" + slug);
        return store.transaction(actor.tenant(), true, tx -> {
            var server = readable(tx, actor, slug);
            authorize(tx, actor, "register", binding(tx, node(server.document()).get("bindingId").asText()));
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            var servers = tx.mcpServers(); long now = clock.millis();
            packages.undeployIn(tx, actor.id(), "mcp." + slug, requestId);
            for (var row : servers.scans(slug, OPEN)) {
                var document = node(row.document()); var job = (ObjectNode) document.get("job").deepCopy();
                job.put("state", "EXPIRED"); saveScan(tx, job, (ObjectNode) document.get("lease").deepCopy());
            }
            for (long revision : servers.snapshotRevisions(slug)) servers.deleteSnapshot(slug, revision);
            servers.deleteServer(slug);
            tx.audit(actor.id(), "MCP_SERVER_UNREGISTERED", "mcp-server:" + slug, server.revision(), requestId, DirectoryService.digest(server.document() + now));
            var reply = new Store.Reply(204, "{}", 0);
            tx.remember(actor.id(), key, digest, reply);
            return reply;
        });
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
            var server = tx.mcpServers().server(scan.row().slug());
            var registration = (ObjectNode) node(server.document());
            if (scan.job().get("purpose").asText().equals("REFRESH")) return reply(200, refreshed(tx, scan, registration, result, tools, now, actor));
            long revision = snapshot(tx, registration, scanId, result, tools, now);
            scan.job().put("snapshotRevision", revision);
            if (Set.of("REGISTERED", "SCANNING").contains(registration.get("state").asText())) registration.put("state", "SCANNED");
            save(tx, registration);
            tx.audit(actor, "MCP_SCAN_FINISHED", "mcp-server:" + server.slug(), revision, scanId, DirectoryService.digest(scanId));
            return reply(200, finish(tx, scan, "FINISHED", null, now, null));
        });
    }

    /** Stores an immutable snapshot of a scan result as the registration's latest; returns its revision. */
    private long snapshot(Store.Session tx, ObjectNode registration, String scanId, McpScanResult result, ArrayNode tools, long now) {
        var servers = tx.mcpServers(); String slug = registration.get("slug").asText();
        long revision = registration.path("latestSnapshotRevision").asLong(0) + 1;
        var snapshot = JSON.objectNode().put("revision", revision).put("scanId", scanId);
        snapshot.set("serverInfo", node(codec.json(result.serverInfo())));
        snapshot.put("protocolVersion", result.protocolVersion()).put("spkiPin", result.spkiPin()).put("scannedAtUnixMs", now);
        snapshot.set("tools", tools);
        servers.saveSnapshot(new McpServerStore.Snapshot(slug, revision, codec.json(codec.model(snapshot.toString(), McpSnapshot.class))));
        prune(tx, slug, revision);
        registration.put("latestSnapshotRevision", revision);
        return revision;
    }

    /** A refresh outcome replaces the verification record (plan §7): a match makes the approval CURRENT now; a changed
     *  server name quarantines the server, a missing or changed tool quarantines that tool, with the observed snapshot
     *  kept as evidence for re-approval; a changed server version only asks for review. A refresh of an approval that
     *  is no longer current proves nothing and changes nothing. */
    private ObjectNode refreshed(Store.Session tx, Lease scan, ObjectNode registration, McpScanResult result, ArrayNode tools, long now, String actor) {
        var servers = tx.mcpServers(); String slug = registration.get("slug").asText();
        long sequence = scan.job().path("approvalSequence").asLong(-1);
        if (registration.path("approvalSequence").asLong(0) != sequence || !registration.get("state").asText().equals("APPROVED")) {
            tx.audit(actor, "MCP_REFRESH_STALE", "mcp-server:" + slug, sequence, scan.row().scanId(), DirectoryService.digest(scan.row().scanId()));
            return finish(tx, scan, "FINISHED", null, now, null);
        }
        var approval = node(servers.approval(slug, sequence).document());
        var approvedSnapshot = node(servers.snapshot(slug, approval.get("snapshotRevision").asLong()).document());
        var observed = new HashMap<String, String>();
        for (var tool : tools) observed.put(tool.get("tool").get("name").asText(), tool.get("definitionDigest").asText());
        String reason = null; boolean serverWide = false; var affected = new ArrayList<String>(); var all = new ArrayList<String>();
        for (var tool : approval.get("tools")) {
            all.add(tool.get("toolId").asText());
            String digest = observed.get(tool.get("upstreamName").asText());
            if (digest != null && digest.equals(tool.get("definitionDigest").asText())) continue;
            affected.add(tool.get("toolId").asText());
            if (reason == null || digest == null) reason = digest == null ? "TOOL_MISSING" : "DEFINITION_CHANGED";
        }
        if (!result.serverInfo().name().equals(approvedSnapshot.get("serverInfo").get("name").asText())) { reason = "SERVER_CHANGED"; serverWide = true; affected = all; }
        if (reason != null) {
            scan.job().put("snapshotRevision", snapshot(tx, registration, scan.row().scanId(), result, tools, now));
            save(tx, registration);
            quarantineIn(tx, slug, reason, scan.row().scanId(), affected, serverWide, now, actor);
            return finish(tx, scan, "FINISHED", null, now, null);
        }
        if (!result.serverInfo().version().equals(approvedSnapshot.get("serverInfo").get("version").asText()))
            tx.audit(actor, "MCP_SERVER_REVIEW_REQUESTED", "mcp-server:" + slug, sequence, scan.row().scanId(), DirectoryService.digest(result.serverInfo().version()));
        registration.putObject("verification").put("status", "CURRENT").put("approvalSequence", sequence).put("checkedAtUnixMs", now);
        save(tx, registration);
        return finish(tx, scan, "FINISHED", null, now, null);
    }

    /** Disables the affected tools in one audited directory change and marks the registration QUARANTINED; a refresh
     *  of it can no longer make the approval CURRENT. Recovery is a re-scan and re-approval. */
    private void quarantineIn(Store.Session tx, String slug, String reason, String evidence, List<String> toolIds, boolean serverWide, long now, String actor) {
        var before = tx.load(); var entries = new HashMap<>(before.entries());
        for (var toolId : toolIds) {
            var entry = entries.get(Kind.TOOL.id(toolId));
            if (entry == null || !entry.enabled()) continue;
            var tool = (ObjectNode) node(entry.document());
            entries.put(entry.id(), codec.entry(Kind.TOOL, codec.json(tool.put("enabled", false).put("revision", tool.get("revision").asLong() + 1))));
        }
        if (!entries.equals(before.entries())) tx.save(before, new io.ololabs.toolgate.control.domain.Directory(before.revision() + 1, entries));
        var registration = (ObjectNode) node(tx.mcpServers().server(slug).document());
        long sequence = registration.path("approvalSequence").asLong();
        var quarantine = registration.putObject("quarantine").put("reason", reason).put("evidenceId", evidence);
        var ids = quarantine.putArray("toolIds"); toolIds.forEach(ids::add);
        quarantine.put("serverWide", serverWide).put("atUnixMs", now).put("approvalSequence", sequence);
        registration.put("state", "QUARANTINED");
        save(tx, registration);
        tx.audit(actor, "MCP_SERVER_QUARANTINED", "mcp-server:" + slug, sequence, evidence, DirectoryService.digest(reason + "\n" + String.join(",", toolIds)));
    }

    /** Queues a pinned REFRESH of the current approval of an approved server. */
    private void refresh(Store.Session tx, McpServerStore.Server server, String actor, String requestId) {
        var registration = node(server.document()); long sequence = registration.path("approvalSequence").asLong();
        var pins = new ArrayList<String>();
        node(tx.mcpServers().approval(server.slug(), sequence).document()).get("spkiPins").forEach(pin -> pins.add(pin.asText()));
        queue(tx, server, binding(tx, registration.get("bindingId").asText()), McpScanPurpose.REFRESH, pins, sequence, actor, requestId);
    }

    /** The holder of an MCP_PROXY call saw notifications/tools/list_changed: refresh that server now, unless a scan of
     *  it is open. */
    public Store.Reply refreshRequested(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String body) {
        var request = codec.model(body, McpRefreshRequest.class);
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device);
            var invocation = operations.invocationIn(tx, request.invocationId()); var evaluation = invocation.evaluation();
            if (!d.deviceId().equals(evaluation.context().deviceId()) || invocation.reservedNonce() == null
                || !java.security.MessageDigest.isEqual(invocation.reservedNonce().getBytes(java.nio.charset.StandardCharsets.UTF_8), request.fencingToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw ManagementAccess.denied();
            String toolId = evaluation.toolId();
            if (!toolId.startsWith("mcp.") || toolId.indexOf('/') < 0) throw ManagementAccess.denied();
            var server = tx.mcpServers().server(toolId.substring(4, toolId.indexOf('/')));
            if (server != null && node(server.document()).get("state").asText().equals("APPROVED") && tx.mcpServers().scans(server.slug(), OPEN).isEmpty()) {
                try { refresh(tx, server, DirectoryService.digest(d.deviceId()), request.invocationId()); }
                catch (Failure busy) { if (busy.status() != 409) throw busy; }
            }
            return new Store.Reply(202, "{}", 0);
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
            String actor = DirectoryService.digest(d.deviceId());
            var job = finish(tx, scan, "FAILED", report.failure().name(), now, actor);
            if (report.failure() == McpCheckFailure.PIN_MISMATCH && job.get("purpose").asText().equals("REFRESH")) {
                // The approved TLS key changed: the server is quarantined, before any credential was sent.
                var server = tx.mcpServers().server(scan.row().slug()); var registration = server == null ? null : node(server.document());
                long sequence = job.path("approvalSequence").asLong(-1);
                if (registration != null && registration.get("state").asText().equals("APPROVED") && registration.path("approvalSequence").asLong() == sequence) {
                    var toolIds = new ArrayList<String>();
                    node(tx.mcpServers().approval(scan.row().slug(), sequence).document()).get("tools").forEach(t -> toolIds.add(t.get("toolId").asText()));
                    quarantineIn(tx, scan.row().slug(), "PIN_MISMATCH", scanId, toolIds, true, now, actor);
                }
            }
            return reply(200, job);
        });
    }

    /** A quarantine report, bound to one invocation or one refresh scan the reporting host holds (plan §6). Control
     *  derives the targets itself and applies the report only to the current approval; a stale report is audited and
     *  changes nothing. */
    public Store.Reply quarantine(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String body) {
        var report = codec.model(body, McpQuarantineReport.class);
        if ((report.invocationId() == null) == (report.scanId() == null)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); long now = clock.millis(); String actor = DirectoryService.digest(d.deviceId());
            String slug, evidence; List<String> toolIds; boolean serverWide, current;
            var servers = tx.mcpServers();
            if (report.scanId() != null) {
                var scan = lease(tx, d.deviceId(), report.scanId());
                if (!report.fencingToken().equals(scan.lease().get("fencingToken").asText()) || !scan.job().get("purpose").asText().equals("REFRESH")) throw ManagementAccess.denied();
                slug = scan.row().slug(); evidence = report.scanId(); serverWide = true;
                var server = servers.server(slug);
                long sequence = server == null ? -1 : node(server.document()).path("approvalSequence").asLong(-1);
                current = sequence == scan.job().path("approvalSequence").asLong(-2);
                toolIds = new ArrayList<>();
                if (current) for (var tool : node(servers.approval(slug, sequence).document()).get("tools")) toolIds.add(tool.get("toolId").asText());
            } else {
                var invocation = operations.invocationIn(tx, report.invocationId()); var evaluation = invocation.evaluation();
                // Only the host that holds the invocation's lease reports on it.
                if (!d.deviceId().equals(evaluation.context().deviceId()) || invocation.reservedNonce() == null
                    || !java.security.MessageDigest.isEqual(invocation.reservedNonce().getBytes(java.nio.charset.StandardCharsets.UTF_8), report.fencingToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw ManagementAccess.denied();
                String toolId = evaluation.toolId();
                if (!toolId.startsWith("mcp.") || toolId.indexOf('/') < 0) throw ManagementAccess.denied();
                slug = toolId.substring(4, toolId.indexOf('/')); evidence = report.invocationId(); serverWide = false; toolIds = List.of(toolId);
                var deployment = tx.packages().deployment("mcp." + slug);
                current = deployment != null && node(deployment).get("packageDigest").asText().equals("sha256:" + evaluation.packageDigest());
            }
            var server = servers.server(slug);
            if (server == null) throw notFound();
            var registration = (ObjectNode) node(server.document());
            if (registration.path("quarantine").path("evidenceId").asText().equals(evidence)) return new Store.Reply(200, server.document(), 0);
            if (!current) {
                tx.audit(actor, "MCP_QUARANTINE_STALE", "mcp-server:" + slug, server.revision(), evidence, DirectoryService.digest(body));
                return new Store.Reply(200, server.document(), 0);
            }
            quarantineIn(tx, slug, report.reason().name(), evidence, toolIds, serverWide, now, actor);
            return new Store.Reply(200, tx.mcpServers().server(slug).document(), 0);
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
            // Refreshes: each approved server at most every 60 s, its last check (success or failure) the clock.
            for (var server : tx.mcpServers().servers(MAX_SERVERS)) {
                var registration = node(server.document());
                if (!registration.get("state").asText().equals("APPROVED")) continue;
                if (now - registration.path("verification").path("checkedAtUnixMs").asLong(0) < REFRESH_MS) continue;
                if (!tx.mcpServers().scans(server.slug(), OPEN).isEmpty()) continue;
                try { refresh(tx, server, "mcp-refresh-scheduler", "refresh-" + now); }
                catch (Failure busy) { if (busy.status() != 409) throw busy; }
            }
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
