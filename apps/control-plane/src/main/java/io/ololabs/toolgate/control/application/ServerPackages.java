// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.PackageDeploymentRequest;
import io.ololabs.toolgate.contracts.v2.PermitReservationRequest;
import io.ololabs.toolgate.contracts.v2.ToolHostRegistration;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Server packages and the Tool Hosts that run them (platform-requirements.md §7, §8, §11).
 *
 *  An administrator uploads a `.tgpkg`; Control applies the packager's checks and seals it by digest. Deploying it builds
 *  the service profile (each destination's host is the global its hostSetting names) and the globals document, and
 *  writes each tool to the directory as a disabled tool with its own resource extractor. Enabling a tool and granting it
 *  stay reviewed directory changes, as for every other tool.
 *
 *  A Tool Host is an enrolled device that registers with its device certificate. It fetches its assignments (the
 *  deployed tools, which it reports in its check-ins as local tools, so placement sends their calls to it) and the
 *  packages and documents they pin. The Gateway reserves a v2 permit addressed to the host for a call placed there. */
public final class ServerPackages {
    public static final String ACTION = "invoke";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final Store store; private final Codec codec; private final EnterpriseOperations operations; private final Clock clock;
    public ServerPackages(Store store, Codec codec, EnterpriseOperations operations, Clock clock) { this.store = store; this.codec = codec; this.operations = operations; this.clock = clock; }

    private static void validateDestinationTls(String id,JsonNode tls) {
        // Platform mTLS must retain normal CA/hostname validation; SPKI-only validation is insufficient.
        if (Set.of("olo-memory","olo-rag").contains(id) && !tls.path("spkiPins").isEmpty()) throw Failure.validation();
        if (tls.has("caBundle")) {
            try {
                byte[] pem=tls.path("caBundle").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                var certificates=java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new java.io.ByteArrayInputStream(pem));
                if(certificates.isEmpty()||certificates.size()>32)throw Failure.validation();
            } catch(java.security.cert.CertificateException invalid) {throw Failure.validation();}
        }
    }

    private JsonNode node(String document) { return (JsonNode) codec.value(document); }
    private Store.Reply reply(int status, Object value) { return new Store.Reply(status, codec.json(value), 0); }
    private static Failure notFound() { return new Failure(ErrorCode.NOT_FOUND, 404, "Package not found"); }
    /** A declaration whose execution location does not allow this target (docs/contracts/platform-requirements.md). */
    static Failure locationRefused() { return new Failure(ErrorCode.FORBIDDEN, 403, "Tool execution location does not allow this target"); }
    private void writer(Store.Session tx, DirectoryService.Actor actor, String action) { actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package." + action, clock.millis()); }
    private void reader(Store.Session tx, DirectoryService.Actor actor) { actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.read", clock.millis()); }
    private static String hex(String digest) { if (digest == null || !digest.matches("sha256:[a-f0-9]{64}")) throw Failure.validation(); return digest.substring(7); }

    /** Where an imported package came from, recorded in the audit log (marketplace design §8.3). */
    public record Origin(String marketplaceId, String packageId, String version, String publisherSlug, String keyId) {
        String target() { return "marketplace:" + marketplaceId + "/" + packageId + "@" + version + " publisher:" + publisherSlug + " key:" + keyId; }
    }

    public Store.Reply upload(DirectoryService.Actor actor, byte[] data, String requestId) { return upload(actor, data, requestId, null); }

    /** Package verification never confers authority; import permission is resolved against current roles. */
    public Store.Reply upload(DirectoryService.Actor actor, byte[] data, String requestId, Origin origin) {
        var verified = PackageArchive.validate(data, this::node); var manifest = verified.manifest();
        PackageCompatibility.require(PackageCompatibility.problems(manifest));
        // Only Control generates MCP_PROXY packages, from an approved MCP server snapshot.
        if (PackageCompatibility.proxy(manifest)) throw PackageCompatibility.incompatible();
        return store.transaction(actor.tenant(), true, tx -> {
            writer(tx, actor, "import");
            if (origin != null) tx.audit(actor.id(), "PACKAGE_MARKETPLACE_IMPORT", origin.target(), 1, requestId, hex(verified.packageDigest()));
            return imported(tx, actor.id(), verified, data, requestId);
        });
    }

    /** Stores a verified, compatible package. The caller has authorized the import. */
    Store.Reply imported(Store.Session tx, String actor, PackageArchive.Verified verified, byte[] data, String requestId) {
        var manifest = verified.manifest();
        String digest = verified.packageDigest(), id = manifest.get("id").asText(), version = manifest.get("version").asText();
        {
            var existing = tx.packages().version(digest);
            if (existing != null) return new Store.Reply(200, existing.document(), 0);
            long now = clock.millis();
            var document = JSON.objectNode().put("packageId", id).put("version", version).put("packageDigest", digest).put("descriptorSetDigest", verified.descriptorSetDigest())
                .put("state", "VERIFIED").put("revision", 1).put("uploadedAtUnixMs", now);
            document.set("manifest", manifest);
            tx.packages().saveVersion(new PackageStore.Version(digest, id, version, now, codec.json(document)), data);
            tx.audit(actor, "PACKAGE_UPLOAD", "package:" + id, 1, requestId, hex(digest));
            return reply(201, document);
        }
    }

    public Store.Reply versions(DirectoryService.Actor actor) {
        return store.transaction(actor.tenant(), false, tx -> {
            reader(tx, actor); var page = JSON.objectNode(); var items = page.putArray("items");
            for (var v : tx.packages().versions(100)) items.add(node(v.document()));
            return reply(200, page);
        });
    }

    public Store.Reply deployments(DirectoryService.Actor actor) {
        return store.transaction(actor.tenant(), false, tx -> {
            reader(tx, actor); var page = JSON.objectNode(); var items = page.putArray("items");
            for (var d : tx.packages().deployments()) items.add(node(d));
            return reply(200, page);
        });
    }

    public Store.Reply deploy(DirectoryService.Actor actor, String body, String requestId) {
        var request = codec.model(body, PackageDeploymentRequest.class);
        return store.transaction(actor.tenant(), true, tx -> {
            writer(tx, actor, "deploy");
            var stored = tx.packages().version(request.packageDigest());
            // A generated MCP_PROXY package is deployed by its approval only, onto its registration's pool.
            if (stored != null && PackageCompatibility.proxy(node(stored.document()).get("manifest"))) throw Failure.conflict();
            return reply(200, deployIn(tx, actor.id(), request, requestId));
        });
    }

    /** Deploys a stored package. The caller has authorized the deployment. Tools of the previous deployment that this
     *  manifest omits are retired in the same directory change: disabled with their extractors, so catalogs stop listing
     *  them and permits for them are refused. */
    ObjectNode deployIn(Store.Session tx, String actor, PackageDeploymentRequest request, String requestId) {
        {
            var version = tx.packages().version(request.packageDigest());
            if (version == null) throw notFound();
            var manifest = node(version.document()).get("manifest");
            String packageId = version.packageId();
            var declared = new TreeMap<String, JsonNode>(); var hosts = new TreeSet<String>();
            var destinations = new TreeMap<String, JsonNode>(); var routes = new TreeMap<String, JsonNode>(); var secrets = new TreeMap<String, Set<String>>();
            for (var tool : manifest.path("tools")) {
                if (!io.ololabs.toolgate.sdk.ExecutionLocation.SERVER.allows(tool.path("descriptor").path("kind").asText())) continue;
                var descriptor = tool.get("descriptor"); String toolId = descriptor.get("toolId").asText();
                for (var global : descriptor.path("variables").path("globals")) same(declared, global.get("name").asText(), global);
                var names = new TreeSet<String>();
                for (var secret : descriptor.path("variables").path("secrets")) names.add(secret.get("name").asText());
                secrets.put(toolId, names);
                for (var destination : descriptor.path("destinations")) { same(destinations, destination.get("id").asText(), destination); hosts.add(destination.get("hostSetting").asText()); }
                for (var route : descriptor.path("routes")) same(routes, route.get("id").asText(), route);
            }
            for (var name : request.globals().keySet()) if (!declared.containsKey(name) && !hosts.contains(name)) throw Failure.validation();
            if (PackageCompatibility.proxy(manifest)) {
                // The upstream credential is named by the proxy document, not by descriptor variables.
                String secret = proxyDocument(tx, version).get("server").get("auth").get("secretName").asText();
                secrets.replaceAll((toolId, names) -> new TreeSet<>(Set.of(secret)));
                if (request.deviceGroupIds() == null || request.deviceGroupIds().size() != 1) throw Failure.validation();
            }
            var targets = new TreeSet<String>(request.hostIds() == null ? List.of() : request.hostIds());
            var groups = new TreeSet<String>(request.deviceGroupIds() == null ? List.of() : request.deviceGroupIds());
            // A deployment names Tool Hosts or Device Groups (pools), never both.
            if (!targets.isEmpty() && !groups.isEmpty()) throw Failure.validation();
            var problems = new ArrayList<>(PackageCompatibility.runtimeProblems(manifest));
            for (var target : targets) {
                var registration = tx.packages().toolHost(target);
                if (registration == null) throw Failure.validation();
                problems.addAll(PackageCompatibility.hostProblems(manifest, target, node(registration)));
            }
            var directory = tx.load();
            for (var group : groups) {
                if (directory.entries().get(Ids.Kind.DEVICE_GROUP.id(group)) == null) throw Failure.validation();
                for (var member : DeviceGroups.members(directory, codec, group)) {
                    var registration = tx.packages().toolHost(member);
                    if (registration != null) problems.addAll(PackageCompatibility.hostProblems(manifest, member, node(registration)));
                }
            }
            PackageCompatibility.require(problems);
            var globals = JSON.objectNode();
            declared.forEach((name, declaration) -> {
                var value = request.globals().containsKey(name) ? request.globals().get(name) : declaration.get("default");
                if (value == null || value.isNull()) { if (declaration.path("required").asBoolean()) throw Failure.validation(); return; }
                if (!typed(declaration, value)) throw Failure.validation();
                globals.set(name, value);
            });
            var approvedTls = request.destinationTls() == null ? Map.<String,JsonNode>of() : request.destinationTls();
            if (!destinations.keySet().containsAll(approvedTls.keySet())) throw Failure.validation();
            var profileDestinations = JSON.arrayNode();
            destinations.forEach((id, declaration) -> {
                var host = request.globals().get(declaration.get("hostSetting").asText());
                if (host == null || !host.isTextual() || !host.asText().matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) throw Failure.validation();
                var tls = JSON.objectNode(); tls.set("spkiPins", declaration.path("spkiPins").deepCopy());
                if (declaration.has("caBundle")) tls.set("caBundle", declaration.get("caBundle"));
                if (approvedTls.containsKey(id)) tls = (ObjectNode) approvedTls.get(id).deepCopy();
                validateDestinationTls(id,tls);
                var destination = profileDestinations.addObject().put("id", id).put("scheme", "https").put("host", host.asText()).put("port", declaration.get("port").asInt());
                destination.set("tls", tls); destination.put("mode", "BROKERED");
            });
            var previous = tx.packages().deployment(packageId);
            long revision = previous == null ? 1 : node(previous).get("revision").asLong() + 1;
            var profile = JSON.objectNode().put("profileId", "sp-" + packageId).put("revision", revision).put("packageId", packageId);
            profile.set("destinations", profileDestinations);
            var profileRoutes = profile.putArray("routes"); routes.values().forEach(profileRoutes::add);
            var ranges = profile.putArray("privateRanges");
            for (var range : request.privateRanges() == null ? List.<io.ololabs.toolgate.contracts.v2.PrivateRangeApproval>of() : request.privateRanges()) {
                var ports = java.util.stream.StreamSupport.stream(profileDestinations.spliterator(),false)
                    .filter(d -> d.path("host").asText().equals(range.host())).map(d -> d.path("port").asLong()).collect(java.util.stream.Collectors.toSet());
                if (range.ports().isEmpty() || !ports.containsAll(range.ports())) throw Failure.validation();
                try {
                    var network=NetworkRange.parse(range.cidr());
                    String address=java.net.InetAddress.getByAddress(network.address()).getHostAddress();
                    boolean privateOnly=List.of("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","100.64.0.0/10","fc00::/7").stream()
                        .map(NetworkRange::parse).anyMatch(base -> network.address().length==base.address().length && network.prefix()>=base.prefix() && base.contains(address));
                    if(!privateOnly)throw Failure.validation();
                } catch (IllegalArgumentException | java.net.UnknownHostException invalid) { throw Failure.validation(); }
                ranges.add(node(codec.json(range)));
            }
            String profileDigest = Canonical.digest(profile), globalsDigest = Canonical.digest(globals);
            tx.packages().saveDocument(profileDigest, codec.json(profile));
            tx.packages().saveDocument(globalsDigest, codec.json(globals));

            var before = tx.load(); var entries = new HashMap<>(before.entries()); var toolIds = new ArrayList<String>();
            for (var tool : manifest.path("tools")) {
                if (!io.ololabs.toolgate.sdk.ExecutionLocation.SERVER.allows(tool.path("descriptor").path("kind").asText())) continue;
                var descriptor = tool.get("descriptor"); String toolId = descriptor.get("toolId").asText(); toolIds.add(toolId);
                String extractorId = "extract-" + DirectoryService.digest(toolId).substring(0, 32);
                var authorization = PackageAuthorization.of(descriptor);
                var fixed = JSON.arrayNode(); authorization.fixedResources(fixed, toolId);
                for (var secret : secrets.get(toolId)) fixed.addObject().put("kind", "CUSTOM").put("locator", "secret://" + secret);
                var extractor = JSON.objectNode().put("id", extractorId).put("name", ("Resources for " + toolId).substring(0, Math.min(128, 14 + toolId.length())))
                    .put("enabled", true).put("revision", 1).put("extractorKind", "FIELDS").put("version", version.version());
                extractor.putArray("fields"); extractor.set("fixedResources", fixed); extractor.put("maxResources", 32);
                authorization.locatorTemplates(extractor);
                put(entries, Kind.EXTRACTOR, extractorId, extractor, true);
                var definition = JSON.objectNode().put("id", toolId).put("name", toolId).put("description", descriptor.path("description").asText(toolId));
                definition.putArray("actions").addObject().put("name", authorization.action()).putArray("resourceKinds").add("CUSTOM");
                String reconciliation = PackageReconciliation.of(descriptor);
                if (reconciliation != null) definition.put("reconciliation", reconciliation);
                definition.set("inputSchema", descriptor.get("inputSchema"));
                definition.set("outputSchema", descriptor.path("outputSchema").isObject() ? descriptor.get("outputSchema") : JSON.objectNode().put("type", "object"));
                var controlTool = JSON.objectNode().put("id", toolId).put("name", toolId).put("enabled", false).put("revision", 1);
                controlTool.set("definition", definition);
                controlTool.put("extractorId", extractorId).put("version", version.version()).put("packageDigest", hex(version.digest()));
                boolean added = !entries.containsKey(Kind.TOOL.id(toolId));
                put(entries, Kind.TOOL, toolId, controlTool, false);
                // Every tool belongs to exactly one Tool Group; a new one starts in the default group, as when created by hand.
                if (added) GroupGraph.addDefault(entries, codec, Kind.TOOL, toolId);
            }
            if (toolIds.isEmpty()) throw PackageCompatibility.incompatible();
            if (previous != null) for (var old : node(previous).path("toolIds")) if (!toolIds.contains(old.asText())) retire(entries, old.asText());
            var after = new Directory(before.revision() + 1, entries);
            try { after.validate(512, 1048576); codec.validatePolicies(after); } catch (IllegalArgumentException failure) { throw Failure.conflict(); }
            tx.save(before, after);

            var deployment = JSON.objectNode().put("packageId", packageId).put("version", version.version()).put("packageDigest", version.digest()).put("revision", revision)
                .put("serviceProfileDigest", profileDigest).put("serviceProfileRevision", revision).put("globalsDigest", globalsDigest);
            var ids = deployment.putArray("toolIds"); toolIds.forEach(ids::add);
            deployment.put("deployedAtUnixMs", clock.millis());
            if (!targets.isEmpty()) { var hostIds = deployment.putArray("hostIds"); targets.forEach(hostIds::add); }
            if (!groups.isEmpty()) { var groupIds = deployment.putArray("deviceGroupIds"); groups.forEach(groupIds::add); }
            tx.packages().saveDeployment(packageId, codec.json(deployment));
            tx.audit(actor, "PACKAGE_DEPLOY", "package:" + packageId, revision, requestId, hex(version.digest()));
            return deployment;
        }
    }

    /** Retires every tool of a deployment and removes it (an unregistered MCP server's package). */
    void undeployIn(Store.Session tx, String actor, String packageId, String requestId) {
        var raw = tx.packages().deployment(packageId);
        if (raw == null) return;
        var before = tx.load(); var entries = new HashMap<>(before.entries());
        for (var toolId : node(raw).path("toolIds")) retire(entries, toolId.asText());
        tx.save(before, new Directory(before.revision() + 1, entries));
        tx.packages().deleteDeployment(packageId);
        tx.audit(actor, "PACKAGE_UNDEPLOY", "package:" + packageId, node(raw).get("revision").asLong(), requestId, DirectoryService.digest(raw));
    }

    /** Disables a deployed tool and its extractor at their next revisions. */
    private void retire(Map<Ids.RecordId, Directory.Entry> entries, String toolId) {
        var tool = entries.get(Kind.TOOL.id(toolId));
        if (tool == null) return;
        var document = (ObjectNode) node(tool.document());
        var extractor = entries.get(Kind.EXTRACTOR.id(document.path("extractorId").asText()));
        entries.put(tool.id(), codec.entry(Kind.TOOL, codec.json(document.put("enabled", false).put("revision", document.get("revision").asLong() + 1))));
        if (extractor != null) {
            var e = (ObjectNode) node(extractor.document());
            entries.put(extractor.id(), codec.entry(Kind.EXTRACTOR, codec.json(e.put("enabled", false).put("revision", e.get("revision").asLong() + 1))));
        }
    }

    public static final String MCP_PROXY_MEDIA_TYPE = "application/vnd.ololabs.toolgate.mcp-proxy+json";
    /** The mcp-proxy.json of a stored MCP_PROXY package. */
    JsonNode proxyDocument(Store.Session tx, PackageStore.Version version) {
        var manifest = node(version.document()).get("manifest"); String path = null;
        for (var artifact : manifest.path("artifacts")) if (MCP_PROXY_MEDIA_TYPE.equals(artifact.path("mediaType").asText())) path = artifact.get("path").asText();
        var bytes = tx.packages().content(version.digest());
        if (path == null || bytes == null) throw Failure.conflict();
        var file = PackageArchive.validate(bytes, this::node).files().get(path);
        if (file == null) throw Failure.conflict();
        String json = new String(file, java.nio.charset.StandardCharsets.UTF_8);
        codec.model(json, io.ololabs.toolgate.contracts.v2.McpProxyDocument.class);
        return node(json);
    }

    /** A deployment runs on the Tool Hosts it names, on the current members of the Device Groups (pools) it names, or on
     *  every Tool Host when it names neither. Group membership is read live, so a host added to a pool gets its
     *  deployments and one removed from it stops getting them, and their permits. Moving a tool to another device is a
     *  redeploy that names another host or pool. */
    public static boolean targets(Directory directory, Codec codec, JsonNode deployment, String hostId) {
        var groupIds = deployment.path("deviceGroupIds");
        if (groupIds.isArray() && !groupIds.isEmpty()) {
            for (var group : groupIds) if (DeviceGroups.members(directory, codec, group.asText()).contains(hostId)) return true;
            return false;
        }
        var hostIds = deployment.path("hostIds");
        if (!hostIds.isArray() || hostIds.isEmpty()) return true;
        for (var id : hostIds) if (id.asText().equals(hostId)) return true;
        return false;
    }
    private List<JsonNode> deploymentsFor(Store.Session tx, String hostId) {
        var directory = tx.load();
        return tx.packages().deployments().stream().map(this::node).filter(d -> targets(directory, codec, d, hostId)).toList();
    }

    /** One declaration per name across a package's tools; two tools may share it only verbatim. */
    private static void same(Map<String, JsonNode> seen, String key, JsonNode value) { var previous = seen.putIfAbsent(key, value); if (previous != null && !previous.equals(value)) throw Failure.validation(); }

    /** A directory entry for a deployed tool; a tool keeps its enabled state and moves to the next revision when changed. */
    private void put(Map<Ids.RecordId, Directory.Entry> entries, Kind kind, String id, ObjectNode document, boolean enabled) {
        var previous = entries.get(kind.id(id));
        if (previous != null) {
            var old = (ObjectNode) node(previous.document());
            if (kind == Kind.TOOL) document.put("enabled", previous.enabled()); else document.put("enabled", enabled);
            document.put("revision", old.get("revision").asLong());
            if (old.equals(document)) return;
            document.put("revision", old.get("revision").asLong() + 1);
        }
        entries.put(kind.id(id), codec.entry(kind, codec.json(document)));
    }

    private static boolean typed(JsonNode declaration, JsonNode value) {
        return switch (declaration.get("type").asText()) {
            case "INT", "LONG" -> value.isIntegralNumber();
            case "NUMBER" -> value.isNumber();
            case "BOOLEAN" -> value.isBoolean();
            case "OBJECT" -> value.isObject();
            case "ARRAY" -> value.isArray();
            case "ENUM" -> value.isTextual() && (!declaration.has("enum") || java.util.stream.StreamSupport.stream(declaration.get("enum").spliterator(), false).anyMatch(e -> e.asText().equals(value.asText())));
            default -> value.isTextual();
        };
    }

    // Tool Host side: the device's certificate authenticates every call.

    private EndpointDeviceRecord host(Store.Session tx, Function<Store.Session, EndpointDeviceRecord> device) {
        var d = device.apply(tx); if (tx.packages().toolHost(d.deviceId()) == null) throw ManagementAccess.denied(); return d;
    }

    /** A Tool Host's host id is its device id, so a permit addressed to the host is addressed to the device placement picked. */
    /** The one tool protocol every executor speaks. */
    public static final long PROTOCOL = 2;

    public Store.Reply register(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String body, String requestId) {
        var registration = codec.model(body, ToolHostRegistration.class);
        // Preflight: tool protocol v2 is the only protocol (ADR 027); a host that cannot speak it is refused, never adapted to.
        if (registration.protocol().min() > PROTOCOL || registration.protocol().max() < PROTOCOL)
            throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.VALIDATION, 409, "This Tool Host does not speak tool protocol " + PROTOCOL + "; deploy a matching version");
        return store.transaction(tenant, true, tx -> {
            var d = device.apply(tx);
            if (!registration.hostId().equals(d.deviceId())) throw ManagementAccess.denied();
            // The pool a host claims is never trusted: it must be a Device Group the host is an administered member of,
            // on its first registration as on every later one.
            if (!DeviceGroups.members(tx.load(), codec, registration.poolId()).contains(d.deviceId())) throw ManagementAccess.denied();
            String document = codec.json(registration);
            if (!document.equals(tx.packages().toolHost(d.deviceId()))) {
                tx.packages().saveToolHost(d.deviceId(), document);
                tx.audit(DirectoryService.digest(d.deviceId()), "TOOL_HOST_REGISTER", "endpoint:" + d.deviceId(), 1, requestId, DirectoryService.digest(document));
            }
            return new Store.Reply(200, document, 0);
        });
    }

    public Store.Reply assignments(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device) {
        return store.transaction(tenant, false, tx -> {
            var d = host(tx, device); var directory = tx.load(); var evaluator = new EnterpriseEvaluator(codec);
            var result = JSON.objectNode().put("hostId", d.deviceId());
            ArrayNode tools = result.putArray("tools"), deployments = result.putArray("deployments");
            for (var deployment : deploymentsFor(tx, d.deviceId())) {
                String digest = deployment.get("packageDigest").asText();
                deployments.add(deployment);
                for (var toolId : deployment.path("toolIds")) {
                    var entry = directory.entries().get(Kind.TOOL.id(toolId.asText()));
                    if (entry == null) continue;
                    var tool = codec.model(entry.document(), ControlTool.class);
                    if (!("sha256:" + tool.packageDigest()).equals(digest)) continue;
                    var assignment = tools.addObject().put("toolId", tool.id()).put("action", tool.definition().actions().getFirst().name()).put("description", tool.definition().description()).put("enabled", entry.enabled());
                    assignment.set("inputSchema", node(codec.json(tool.definition().inputSchema())));
                    assignment.put("toolDigest", evaluator.toolDigest(directory, tool)).put("packageDigest", digest);
                }
            }
            return reply(200, result);
        });
    }

    /** The exact bytes of a deployed package. */
    public byte[] content(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String digest) {
        hex(digest);
        return store.transaction(tenant, false, tx -> {
            var d = host(tx, device);
            if (deploymentsFor(tx, d.deviceId()).stream().noneMatch(deployment -> deployment.get("packageDigest").asText().equals(digest))) throw notFound();
            var bytes = tx.packages().content(digest); if (bytes == null) throw notFound(); return bytes;
        });
    }

    /** A service profile or globals document a deployment pins. */
    public Store.Reply document(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String digest) {
        hex(digest);
        return store.transaction(tenant, false, tx -> {
            var h = host(tx, device);
            if (deploymentsFor(tx, h.deviceId()).stream().noneMatch(d -> d.get("serviceProfileDigest").asText().equals(digest) || d.get("globalsDigest").asText().equals(digest))) throw notFound();
            var document = tx.packages().document(digest); if (document == null) throw notFound();
            return new Store.Reply(200, document, 0);
        });
    }

    /** The Gateway's v2 reservation: one permit for one attempt of a call Control placed on a registered Tool Host, pinning
     *  the deployment the host runs it from. A call placed on any other device is 409, and the Gateway relays it instead. */
    public Store.Reply permit(DirectoryService.Actor actor, String body, String requestId) {
        if (actor.admin() || actor.userId() != null) throw ManagementAccess.denied();
        var request = codec.model(body, PermitReservationRequest.class);
        if (request.arguments() == null) throw Failure.validation();
        return store.transaction(actor.tenant(), true, tx -> reply(200, permitIn(tx, actor, request)));
    }

    /** The permit for one attempt on the Tool Host the invocation was placed on. Only a declaration whose execution
     *  location allows SERVER can be permitted here, whatever the directory, placement or a forged request says. */
    private io.ololabs.toolgate.contracts.v2.SignedInvocationPermit permitIn(Store.Session tx, DirectoryService.Actor actor, PermitReservationRequest request) {
        {
            var i = operations.invocationIn(tx, request.invocationId()); var e = i.evaluation(); String device = e.context().deviceId();
            if (!request.audience().equals(device) || tx.packages().toolHost(device) == null) throw Failure.conflict();
            var arguments = node(codec.json(request.arguments()));
            if (!DirectoryService.digest(codec.json(arguments)).equals(e.argumentsDigest())) throw ManagementAccess.denied();
            int slash = e.toolId().indexOf('/'); var raw = slash < 0 ? null : tx.packages().deployment(e.toolId().substring(0, slash));
            if (raw == null) throw Failure.conflict();
            var deployment = node(raw);
            if (!deployment.get("packageDigest").asText().equals("sha256:" + e.packageDigest()) || !targets(tx.load(), codec, deployment, device)) throw Failure.conflict();
            var version = tx.packages().version(deployment.get("packageDigest").asText()); var manifest = node(version.document()).get("manifest");
            JsonNode tool = null;
            for (var t : manifest.path("tools")) if (t.path("descriptor").path("toolId").asText().equals(e.toolId())) tool = t;
            if (tool == null) throw Failure.conflict();
            if (!io.ololabs.toolgate.sdk.ExecutionLocation.SERVER.allows(tool.path("descriptor").path("kind").asText())) throw locationRefused();
            JsonNode runtime = null, artifact = null;
            for (var r : manifest.path("runtimes")) if (r.path("runtimeId").asText().equals(tool.path("runtimeId").asText())) runtime = r;
            for (var a : manifest.path("artifacts")) if (a.path("artifactId").asText().equals(tool.path("artifactIds").path(0).asText())) artifact = a;
            if (runtime == null || artifact == null) throw Failure.conflict();
            var descriptor = tool.get("descriptor"); String safety = descriptor.path("effect").path("safety").asText("UNKNOWN");

            var lease = operations.lease(actor.tenant(), tx, i.id(), i.revision(), actor.id());
            var context = e.context();
            var identity = JSON.objectNode().put("tenantId", context.tenantId())
                .put("mode", context.userId() != null ? (context.agentId() != null ? "DELEGATED" : "HUMAN") : "SERVICE");
            if (context.agentId() != null) identity.put("agentId", context.agentId());
            if (context.userId() != null) identity.put("userId", context.userId());
            identity.putArray("chain");
            if (context.credentialEpoch() != null) identity.put("credentialEpoch", context.credentialEpoch());
            identity.put("authorizationEpoch", i.authorizationEpoch()); identity.set("claims", passedClaims(codec, tx.load(), context, e.toolId(), descriptor.path("mcpName").asText(null)));
            var target = JSON.objectNode().put("toolId", e.toolId()).put("toolDescriptorDigest", tool.get("toolDescriptorDigest").asText())
                .put("packageDigest", deployment.get("packageDigest").asText()).put("artifactDigest", artifact.get("artifactDigest").asText())
                .put("runtimeDigest", Canonical.digest(runtime)).put("globalsDigest", deployment.get("globalsDigest").asText())
                .put("serviceProfileDigest", deployment.get("serviceProfileDigest").asText()).put("serviceProfileRevision", deployment.get("serviceProfileRevision").asLong())
                .put("deploymentBindingDigest", Canonical.digest(deployment)).put("deploymentBindingRevision", deployment.get("revision").asLong()).put("configRevision", deployment.get("revision").asLong());
            var targetRoutes = target.putArray("routes"); for (var route : descriptor.path("routes")) targetRoutes.add(route.get("id").asText());
            var claims = JSON.objectNode().put("jti", UUID.randomUUID().toString()).put("iss", operations.issuer()).put("aud", device)
                .put("iat", lease.issuedAt()).put("nbf", lease.issuedAt()).put("exp", lease.expiresAt()).put("nonce", lease.nonce()).put("invocationId", i.id()).put("attempt", request.attempt());
            claims.set("identity", identity); claims.set("target", target);
            claims.put("catalogRevision", Canonical.sha256(Long.toString(tx.load().revision()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .put("argumentsDigest", Canonical.arguments(arguments)).put("action", e.action());
            claims.set("resources", node(codec.json(e.resources())));
            claims.put("effectSafety", safety).put("requiresExecutionLedger", !safety.equals("READ_ONLY") && !safety.equals("IDEMPOTENT"));
            var approvals = claims.putArray("approvalIds"); lease.approvalIds().forEach(approvals::add);
            claims.put("deadlineUnixMs", i.expiresAtUnixMs());
            if (PackageCompatibility.proxy(manifest)) claims.set("mcpVerification", mcpVerification(tx, version, clock.millis()));
            return operations.signer().signInvocation(codec.model(codec.json(claims), io.ololabs.toolgate.contracts.v2.InvocationPermitClaims.class));
        }
    }

    public static final long MCP_FRESHNESS_MS = 120_000;
    /** Freshness evidence for an MCP_PROXY call (ADR 030): CURRENT only when the most recent
     *  check of the deployed approval sequence succeeded at most 120 s ago and the server is approved, not quarantined. */
    public ObjectNode mcpVerification(Store.Session tx, PackageStore.Version version, long now) {
        long deployed = Long.parseLong(version.version().substring(version.version().lastIndexOf('.') + 1));
        var result = JSON.objectNode(); boolean current = false;
        var server = tx.mcpServers().server(version.packageId().substring("mcp.".length()));
        if (server != null) {
            var registration = node(server.document()); var verification = registration.path("verification");
            long checked = verification.path("checkedAtUnixMs").asLong(-1);
            current = registration.path("state").asText().equals("APPROVED") && verification.path("status").asText().equals("CURRENT")
                && verification.path("approvalSequence").asLong(-1) == deployed && checked >= 0 && checked <= now && now - checked <= MCP_FRESHNESS_MS;
        }
        result.put("status", current ? "CURRENT" : "UNVERIFIED").put("approvalSequence", deployed);
        if (current) result.put("checkedAtUnixMs", node(server.document()).path("verification").path("checkedAtUnixMs").asLong());
        return result;
    }

    /** Delegated user claims the workload binding passes to this tool (glob on tool id or MCP name, * suffix only). Nothing else reaches the permit. */
    public static com.fasterxml.jackson.databind.node.ObjectNode passedClaims(Codec codec, io.ololabs.toolgate.control.domain.Directory directory, io.ololabs.toolgate.contracts.EnterpriseContext context, String toolId, String mcpName) {
        var claims = JSON.objectNode(); if (context.userId() == null || context.workloadBindingId() == null) return claims;
        var binding = directory.entries().get(io.ololabs.toolgate.control.domain.Ids.Kind.WORKLOAD_BINDING.id(context.workloadBindingId())); var user = directory.entries().get(io.ololabs.toolgate.control.domain.Ids.Kind.USER.id(context.userId()));
        if (binding == null || user == null) return claims;
        var passed = ((JsonNode) codec.value(binding.document())).path("passClaims"); var values = ((JsonNode) codec.value(user.document())).path("claims");
        if (!passed.isObject() || !context.userId().equals(((JsonNode) codec.value(binding.document())).path("delegatedUserId").asText())) return claims;
        passed.properties().forEach(e -> { var value = values.path(e.getKey()); if (!value.isTextual()) return;
            for (var glob : e.getValue()) if (matches(glob.asText(), toolId) || mcpName != null && matches(glob.asText(), mcpName)) { claims.put(e.getKey(), value.asText()); return; } });
        return claims;
    }
    static boolean matches(String glob, String name) { return glob.endsWith("*") ? name.startsWith(glob.substring(0, glob.length() - 1)) : glob.equals(name); }

    // Pull mode: a Tool Host that the Gateway cannot reach (a laptop behind NAT, another network) fetches its calls from
    // Control over its own connection. The Gateway hands Control the permitted dispatch and polls for the host's answer;
    // the host claims it, answers the admission and sends the result. Nothing else about the call changes.

    private static final Set<String> OPEN = Set.of("QUEUED", "CLAIMED", "ADMITTED");

    /** The permit's claims, read without verifying it: Control signed it, and the host verifies it before running. */
    private JsonNode claims(JsonNode dispatch) {
        var parts = dispatch.path("permit").path("jws").asText().split("\\.");
        if (parts.length != 3) throw Failure.validation();
        try { return node(new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException invalid) { throw Failure.validation(); }
    }
    private ObjectNode status(PackageStore.Relayed relayed) { return (ObjectNode) node(relayed.document()).get("status"); }
    private PackageStore.Relayed updated(PackageStore.Relayed relayed, String state, java.util.function.Consumer<ObjectNode> change) {
        var document = (ObjectNode) node(relayed.document()); var status = (ObjectNode) document.get("status");
        status.put("state", state); change.accept(status);
        return new PackageStore.Relayed(relayed.invocationId(), relayed.hostId(), state, relayed.expiresAt(), codec.json(document));
    }
    private PackageStore.Relayed current(PackageStore.Relayed relayed) {
        return relayed != null && OPEN.contains(relayed.state()) && relayed.expiresAt() <= clock.millis() ? updated(relayed, "EXPIRED", s -> {}) : relayed;
    }

    /** The Gateway hands Control a dispatch for a call placed on a Tool Host; the host fetches it. */
    public Store.Reply relay(DirectoryService.Actor actor, String body) {
        if (actor.admin() || actor.userId() != null) throw ManagementAccess.denied();
        var dispatch = codec.model(body, io.ololabs.toolgate.contracts.v2.Dispatch.class);
        return store.transaction(actor.tenant(), true, tx -> relayIn(tx, dispatch));
    }

    /** A console Test of a tool placed on a Tool Host: the same permit and relayed dispatch the Gateway would create for
     *  the person's own admitted invocation. A retry finds the dispatch already queued and never issues a second permit. */
    public Store.Reply humanDispatch(DirectoryService.Actor actor, String invocationId, Map<String, JsonNode> arguments) {
        if (actor.userId() == null || arguments == null) throw ManagementAccess.denied();
        return store.transaction(actor.tenant(), true, tx -> {
            var i = operations.invocationIn(tx, invocationId); var context = i.evaluation().context();
            if (!actor.userId().equals(context.userId()) || context.mode() != io.ololabs.toolgate.contracts.v2.RequestMode.HUMAN) throw ManagementAccess.denied();
            var existing = tx.packages().relayed(invocationId);
            if (existing != null) return reply(200, status(current(existing)));
            if (i.state() != EnterpriseInvocationState.QUEUED) throw Failure.conflict();
            var permit = permitIn(tx, actor, new PermitReservationRequest(invocationId, context.deviceId(), 1L, arguments));
            return relayIn(tx, new io.ololabs.toolgate.contracts.v2.Dispatch(permit, arguments));
        });
    }

    public boolean toolHost(Ids.TenantId tenant, String deviceId) { return store.transaction(tenant, false, tx -> tx.packages().toolHost(deviceId) != null); }

    /** The caller's own retained Tool Host result, with the same live authority checks as execution. */
    public Store.Reply humanResult(DirectoryService.Actor actor, long sessionEpoch, String invocationId) {
        return store.transaction(actor.tenant(), true, tx -> {
            var invocation = operations.invocationIn(tx, invocationId); var evaluation = invocation.evaluation();
            var context = evaluation.context();
            if (actor.userId() == null || !actor.userId().equals(context.userId())
                    || context.mode() != io.ololabs.toolgate.contracts.v2.RequestMode.HUMAN
                    || !Objects.equals(context.sessionEpoch(), sessionEpoch)) throw ManagementAccess.denied();
            operations.requireFresh(actor.tenant(), tx, invocation);
            var relayed = current(tx.packages().relayed(invocationId));
            if (relayed == null || !relayed.hostId().equals(context.deviceId())) throw notFound();
            var status = status(relayed); RemoteToolResult result = null; ErrorCode error = null;
            RemoteToolState state = switch (relayed.state()) {
                case "QUEUED" -> RemoteToolState.WAITING_FOR_POLL;
                case "EXPIRED" -> RemoteToolState.EXPIRED;
                case "REFUSED" -> RemoteToolState.FAILED;
                default -> RemoteToolState.SUBMITTED;
            };
            if (relayed.state().equals("FINISHED")) {
                var message = codec.model(codec.json(status.get("result")), io.ololabs.toolgate.contracts.v2.ResultMessage.class);
                var frame = message.result();
                boolean completed = message.outcome() == io.ololabs.toolgate.contracts.v2.InvocationOutcome.COMPLETED
                        && frame != null && frame.resultType() == io.ololabs.toolgate.contracts.v2.ResultType.complete
                        && !Boolean.TRUE.equals(frame.isError()) && frame.structuredContent() != null;
                error = completed ? null : ErrorCode.INTERNAL;
                state = completed ? RemoteToolState.DONE : RemoteToolState.FAILED;
                result = new RemoteToolResult(invocationId, invocation.reservedNonce(), completed ? frame.structuredContent() : null, error);
            } else if (state == RemoteToolState.EXPIRED) error = ErrorCode.TIMEOUT;
            else if (state == RemoteToolState.FAILED) error = ErrorCode.DEPENDENCY_UNAVAILABLE;
            var record = new RemoteToolRecord(invocationId, context.deviceId(), null, evaluation.toolId(), state,
                    evaluation.nowUnixMs(), invocation.expiresAtUnixMs(), null, null, null, error);
            return reply(200, new RemoteToolResponse(record, result));
        });
    }

    private Store.Reply relayIn(Store.Session tx, io.ololabs.toolgate.contracts.v2.Dispatch dispatch) {
        var claims = claims(node(codec.json(dispatch)));
        String invocationId = claims.path("invocationId").asText(), audience = claims.path("aud").asText();
        {
            var i = operations.invocationIn(tx, invocationId); String device = i.evaluation().context().deviceId();
            if (!audience.equals(device) || tx.packages().toolHost(device) == null) throw Failure.conflict();
            String document = codec.json(dispatch); var existing = tx.packages().relayed(invocationId);
            if (existing != null) {
                if (!node(existing.document()).get("dispatch").equals(node(document))) throw Failure.conflict();
                return reply(200, status(current(existing)));
            }
            var record = JSON.objectNode(); record.set("dispatch", node(document));
            record.putObject("status").put("invocationId", invocationId).put("hostId", device).put("state", "QUEUED");
            // The host must claim the call while its permit is valid.
            long expires = Math.min(i.expiresAtUnixMs(), claims.path("exp").asLong(i.expiresAtUnixMs()));
            var relayed = new PackageStore.Relayed(invocationId, device, "QUEUED", expires, codec.json(record));
            tx.packages().saveRelayed(relayed);
            return reply(200, status(relayed));
        }
    }

    /** Where a relayed dispatch stands, for the Gateway that handed it over. */
    public Store.Reply relayed(DirectoryService.Actor actor, String invocationId) {
        if (actor.admin() || actor.userId() != null) throw ManagementAccess.denied();
        return store.transaction(actor.tenant(), false, tx -> {
            var relayed = current(tx.packages().relayed(invocationId)); if (relayed == null) throw notFound();
            return reply(200, status(relayed));
        });
    }

    /** The oldest call waiting for this Tool Host, which it now owns; 204 when there is none. */
    public Store.Reply next(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device) {
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); var relayed = tx.packages().nextRelayed(d.deviceId(), clock.millis());
            if (relayed == null) return new Store.Reply(204, "", 0);
            tx.packages().saveRelayed(updated(relayed, "CLAIMED", s -> {}));
            return reply(200, node(relayed.document()).get("dispatch"));
        });
    }

    /** The host's admission for a call it claimed: ADMITTED, or REFUSED with the reason, as over a direct dispatch. */
    public Store.Reply admission(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String invocationId, String body) {
        var admission = codec.model(body, io.ololabs.toolgate.contracts.v2.AdmissionResponse.class);
        if (!admission.invocationId().equals(invocationId)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); var relayed = current(tx.packages().relayed(invocationId));
            if (relayed == null || !relayed.hostId().equals(d.deviceId())) throw notFound();
            if (!relayed.state().equals("CLAIMED")) throw Failure.conflict();
            String state = admission.kind() == io.ololabs.toolgate.contracts.v2.AdmissionKind.ADMITTED ? "ADMITTED" : "REFUSED";
            var next = updated(relayed, state, s -> s.set("admission", node(codec.json(admission))));
            tx.packages().saveRelayed(next);
            return reply(200, status(next));
        });
    }

    /** The host's result for a call it admitted. */
    public Store.Reply result(Ids.TenantId tenant, Function<Store.Session, EndpointDeviceRecord> device, String invocationId, String body) {
        var result = codec.model(body, io.ololabs.toolgate.contracts.v2.ResultMessage.class);
        if (!result.invocationId().equals(invocationId)) throw Failure.validation();
        return store.transaction(tenant, true, tx -> {
            var d = host(tx, device); var relayed = tx.packages().relayed(invocationId);
            if (relayed == null || !relayed.hostId().equals(d.deviceId())) throw notFound();
            if (!relayed.state().equals("ADMITTED")) throw Failure.conflict();
            var next = updated(relayed, "FINISHED", s -> s.set("result", node(codec.json(result))));
            tx.packages().saveRelayed(next);
            return reply(200, status(next));
        });
    }
}
