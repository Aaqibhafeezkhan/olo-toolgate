// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.FleetPackageDocument;
import io.ololabs.toolgate.contracts.FleetPackageRelease;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Converts a verified Java SDK package into a client release (ADR 026, ADR 014 §7).
 *
 *  Control copies the package's single artifact into one deterministic layer on the approved JRE base, pushes the image
 *  by digest to the configured registry, and describes every export a device can run as a JAVA_SDK registration in a
 *  FleetPackageDocument pinned to that image. With the package-release key it signs the document, mirrors its bytes and
 *  records the fleet release; without it, it returns the exact bytes for the independent release authority. Exports a
 *  device cannot run are reported, never weakened. Directory records are written disabled: activation, grants,
 *  rollouts and permits stay separate reviewed authorities. */
public final class ClientReleases {
    public static final String JAVA_SDK_MAIN = "io.ololabs.toolgate.runtime.Main";
    public static final String RUNTIME_ID = "jvm";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int MAX_DOCUMENT = 32768;
    private static final Map<String, String> ARCHITECTURES = Map.of("amd64", "x86_64", "arm64", "aarch64");

    /** repository: where client-tool images are pushed; base: the approved JRE, mirrored on the same registry, by digest. */
    public record Settings(String repository, String baseRepository, String baseDigest, List<String> platforms, String minimumClientVersion,
                           String pythonBaseRepository, String pythonBaseDigest) {
        public Settings(String repository, String baseRepository, String baseDigest, List<String> platforms, String minimumClientVersion) {
            this(repository, baseRepository, baseDigest, platforms, minimumClientVersion, null, null);
        }
        public Settings {
            if (repository == null || !repository.matches("[a-z0-9]+([._/-][a-z0-9]+)*") || baseRepository == null || !baseRepository.matches("[a-z0-9]+([._/-][a-z0-9]+)*")
                || baseDigest == null || !baseDigest.matches("sha256:[a-f0-9]{64}") || platforms == null || platforms.isEmpty()
                || !Set.of("LINUX", "WINDOWS", "MACOS").containsAll(platforms) || new HashSet<>(platforms).size() != platforms.size()) throw new IllegalArgumentException("Invalid client image settings");
            if ((pythonBaseRepository == null) != (pythonBaseDigest == null) || pythonBaseRepository != null && (
                !pythonBaseRepository.matches("[a-z0-9]+([._/-][a-z0-9]+)*") || !pythonBaseDigest.matches("sha256:[a-f0-9]{64}")))
                throw new IllegalArgumentException("Invalid Python client base");
        }
        public Settings withPythonBase(String base) {
            if (base == null) return this;
            int at = base.indexOf('@');
            if (at < 1) throw new IllegalArgumentException("The Python base must be pinned by digest");
            return new Settings(repository, baseRepository, baseDigest, platforms, minimumClientVersion, base.substring(0, at), base.substring(at + 1));
        }
        public static Settings parse(String repository, String base, String platforms, String minimumClientVersion) {
            int at = base == null ? -1 : base.indexOf('@');
            if (at < 1) throw new IllegalArgumentException("The approved base must be <repository>@sha256:<digest>");
            return new Settings(repository, base.substring(0, at), base.substring(at + 1), List.of(platforms.split(",")), minimumClientVersion);
        }
    }

    private final Store store; private final Codec codec; private final OciRegistry registry; private final Settings settings;
    private final PackageReleaseSigner signer; private final FleetService fleet; private final ArtifactStore artifacts; private final Clock clock;
    public ClientReleases(Store store, Codec codec, OciRegistry registry, Settings settings, PackageReleaseSigner signer, FleetService fleet, ArtifactStore artifacts, Clock clock) {
        this.store = store; this.codec = codec; this.registry = registry; this.settings = settings; this.signer = signer; this.fleet = fleet; this.artifacts = artifacts; this.clock = clock;
    }

    private JsonNode node(String document) { return (JsonNode) codec.value(document); }
    private JsonNode node(byte[] bytes) { return node(new String(bytes, StandardCharsets.UTF_8)); }
    private static Failure incompatible(String reason) { return new Failure(ErrorCode.VALIDATION, 422, reason); }

    /** Whether client images are configured, and whether Control signs releases itself (both needed by deployment profiles). */
    public boolean configured() { return registry != null; }
    public boolean signs() { return signer != null; }

    public Store.Reply release(DirectoryService.Actor actor, String body, String requestId) {
        String digest = codec.model(body, io.ololabs.toolgate.contracts.v2.ClientReleaseRequest.class).packageDigest();
        if (registry == null) throw Failure.unavailable();
        var content = store.transaction(actor.tenant(), false, tx -> {
            actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.publish", clock.millis());
            if (tx.packages().version(digest) == null) throw new Failure(ErrorCode.NOT_FOUND, 404, "Package not found");
            var deployment = tx.packages().deployment(PackageArchive.validate(tx.packages().content(digest), this::node).manifest().path("id").asText());
            var server = new HashSet<String>(); if (deployment != null) node(deployment).path("toolIds").forEach(t -> server.add(t.asText()));
            return Map.entry(tx.packages().content(digest), server);
        });
        var verified = PackageArchive.validate(content.getKey(), this::node); var manifest = verified.manifest();
        String packageId = manifest.path("id").asText(), version = manifest.path("version").asText();
        String language = manifest.path("sdk").path("language").asText();
        if (!Set.of("JAVA", "PYTHON").contains(language)) throw incompatible("Only Java and Python SDK packages have a client runtime");
        boolean python = "PYTHON".equals(language);
        if (python && settings.pythonBaseRepository() == null) throw incompatible("Configure an approved Python base image before releasing Python tools");
        String runtimeId = python ? "python" : RUNTIME_ID;
        String filename = python ? "tool.pyz" : "tool.jar";
        var jars = new ArrayList<JsonNode>(); for (var a : manifest.path("artifacts")) if (runtimeId.equals(a.path("runtimeId").asText())) jars.add(a);
        if (jars.size() != 1 || !jars.get(0).path("path").asText().endsWith(python ? ".pyz" : ".jar")) throw incompatible("A client release needs exactly one SDK artifact");
        byte[] jar = verified.files().get(jars.get(0).path("path").asText());
        var examples = examples(jar, python ? "_toolgate_index/package.json" : "META-INF/toolgate/package.json");

        // Which exports a device can run, and why the others cannot.
        var report = new ArrayList<ObjectNode>(); var supported = new ArrayList<JsonNode>();
        for (var tool : manifest.path("tools")) {
            var d = tool.path("descriptor"); String toolId = d.path("toolId").asText();
            String reason = unsupported(tool, d, content.getValue(), examples.get(toolId.substring(toolId.indexOf('/') + 1)), runtimeId);
            var entry = JSON.objectNode().put("toolId", toolId).put("supported", reason == null); if (reason != null) entry.put("reason", reason);
            report.add(entry); if (reason == null) supported.add(tool);
        }
        if (supported.isEmpty()) throw incompatible("No export of this package can run on a client device");

        var image = push(packageId, version, jar, python);
        var runtime = JSON.objectNode().put("id", runtimeId).put("kind", python ? "PYTHON_SDK" : "JAVA_SDK").put("image", image.reference()).put("version", image.javaVersion());
        var document = JSON.objectNode().put("formatVersion", 1).put("packageId", packageId).put("version", version);
        var platforms = document.putArray("platforms"); settings.platforms().forEach(platforms::add);
        var architectures = document.putArray("architectures"); image.architectures().forEach(architectures::add);
        document.put("minimumClientVersion", settings.minimumClientVersion());
        document.putArray("runtimes").add(runtime);
        var tools = document.putArray("tools"); var tests = document.putArray("selfTests"); var records = new ArrayList<ObjectNode[]>();
        for (var tool : supported) {
            var d = tool.path("descriptor"); String toolId = d.path("toolId").asText();
            var registration = JSON.objectNode().put("toolId", toolId).put("action", ServerPackages.ACTION).put("runtimeId", runtimeId)
                .put("entryPoint", "/opt/tool/" + packageId + "/" + filename);
            registration.set("inputSchema", d.get("inputSchema")); registration.set("outputSchema", d.get("outputSchema"));
            // The device brokers its files area only as the signed registration grants (ADR 027).
            String access = "NONE";
            for (var permission : d.path("permissions")) {
                if (permission.asText().equals("DEVICE_FILES_WRITE")) access = "WRITE";
                else if (permission.asText().equals("DEVICE_FILES_READ") && access.equals("NONE")) access = "READ";
            }
            registration.put("fileAccess", access);
            registration.putObject("limits").put("timeoutMs", Math.min(10000, Math.max(1000, d.path("execution").path("maxDurationMs").asLong(10000))))
                .put("memoryMiB", 512).put("maxInputBytes", 65536).put("maxOutputBytes", 65536);
            String managed = InstalledProfiles.packageDigest(registration, runtime);
            String extractorId = "extract-" + DirectoryService.digest(toolId).substring(0, 32);
            var fixed = JSON.arrayNode(); fixed.addObject().put("kind", "CUSTOM").put("locator", "tool://" + toolId);
            var extractor = JSON.objectNode().put("id", extractorId).put("name", ("Resources for " + toolId).substring(0, Math.min(128, 14 + toolId.length())))
                .put("enabled", true).put("revision", 1).put("extractorKind", "FIELDS").put("version", version);
            extractor.putArray("fields"); extractor.set("fixedResources", fixed); extractor.put("maxResources", 32);
            var definition = JSON.objectNode().put("id", toolId).put("name", toolId).put("description", d.path("description").asText(toolId));
            definition.putArray("actions").addObject().put("name", ServerPackages.ACTION).putArray("resourceKinds").add("CUSTOM");
            definition.set("inputSchema", d.get("inputSchema")); definition.set("outputSchema", d.get("outputSchema"));
            var controlTool = JSON.objectNode().put("id", toolId).put("name", toolId).put("enabled", true).put("revision", 1);
            controlTool.set("definition", definition); controlTool.put("extractorId", extractorId).put("version", version).put("packageDigest", managed);
            tools.add(registration);
            var example = examples.get(toolId.substring(toolId.indexOf('/') + 1));
            tests.addObject().put("toolId", toolId).<ObjectNode>set("arguments", example[0]).set("expectedOutput", example[1]);
            records.add(new ObjectNode[]{controlTool, extractor});
        }
        // The profiles carry each extractor exactly as the directory stores it, so devices report matching tool digests.
        var stored = directory(actor, packageId, records, requestId);
        for (int i = 0; i < records.size(); i++) {
            var profile = ((ObjectNode) tools.get(i)).putObject("authorizationProfile");
            profile.set("tool", records.get(i)[0]); profile.set("extractor", stored.get(records.get(i)[0].get("id").asText()));
        }
        var model = codec.model(codec.json(document), FleetPackageDocument.class);
        FleetService.validatePackage(model);
        byte[] bytes = codec.json(model).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT) throw incompatible("The client release document exceeds 32 KiB; release fewer exports");
        String manifestDigest = FleetService.digest(bytes);

        var result = JSON.objectNode().put("packageId", packageId).put("version", version).put("packageDigest", digest).put("image", image.reference())
            .put("runtimeVersion", image.javaVersion()).put("manifestDigest", manifestDigest).put("signed", signer != null);
        result.set("document", node(bytes)); result.put("documentBase64", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        var toolReport = result.putArray("tools"); report.forEach(toolReport::add);
        if (signer != null) {
            var release = new FleetPackageRelease(packageId, version, manifestDigest, (long) bytes.length, signer.sign(bytes));
            artifacts.mirror(manifestDigest, bytes);
            fleet.publish(actor, codec.json(release), "client-release-" + manifestDigest.substring(0, 32), requestId);
            result.set("release", node(codec.json(release)));
        }
        codec.model(codec.json(result), io.ololabs.toolgate.contracts.v2.ClientRelease.class);
        return new Store.Reply(200, codec.json(result), 0);
    }

    /** Why a device cannot run this export, or null. Devices have no broker, routes, secrets, globals or claims. */
    private static String unsupported(JsonNode tool, JsonNode d, Set<String> server, JsonNode[] example, String runtimeId) {
        if (!runtimeId.equals(tool.path("runtimeId").asText()) || !("python".equals(runtimeId) ? "toolgate.runtime" : JAVA_SDK_MAIN).equals(tool.path("entryPoint").asText())) return "The entry point is not the SDK runtime";
        if (!io.ololabs.toolgate.sdk.ExecutionLocation.CLIENT.allows(d.path("kind").asText())) return "The declaration does not allow client devices";
        for (var field : List.of("routes", "destinations", "httpAuth", "tcp")) if (!d.path(field).isEmpty()) return "Needs Tool Host network capabilities devices do not provide";
        for (var permission : d.path("permissions")) if (!permission.asText().startsWith("DEVICE_FILES_")) return "Needs Tool Host network capabilities devices do not provide";
        for (var field : List.of("globals", "secrets", "claims")) if (!d.path("variables").path(field).isEmpty()) return "Needs globals, secrets or claims devices do not provide";
        if (d.path("execution").path("async").asBoolean()) return "Asynchronous tools are not supported on devices";
        if (server.contains(d.path("toolId").asText())) return "Deployed to Tool Hosts; one directory record per export";
        if (!closed(d.path("inputSchema")) || !closed(d.path("outputSchema"))) return "The input or output schema is not closed for device validation";
        if (example == null) return "No example with an expected output to self-test on devices";
        return null;
    }

    /** The client's schema rules: a closed object, without references or patterns, at most 8 KiB. */
    static boolean closed(JsonNode schema) {
        if (!schema.isObject() || !"object".equals(schema.path("type").asText()) || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean()) return false;
        if (schema.toString().getBytes(StandardCharsets.UTF_8).length > 8192) return false;
        return safe(schema, 0);
    }
    private static boolean safe(JsonNode value, int depth) {
        if (depth > 16) return false;
        if (value.isObject()) {
            for (var key : List.of("$ref", "$dynamicRef", "$id", "pattern", "patternProperties")) if (value.has(key)) return false;
            for (var child : value) if (!safe(child, depth + 1)) return false;
        }
        if (value.isArray()) { if (value.size() > 64) return false; for (var child : value) if (!safe(child, depth + 1)) return false; }
        return true;
    }

    /** The SDK's examples index inside the verified artifact: tool name -> {arguments, expectedOutput} of the first plain example. */
    private Map<String, JsonNode[]> examples(byte[] jar, String indexPath) {
        var result = new HashMap<String, JsonNode[]>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals(indexPath)) continue;
                byte[] raw = zip.readNBytes(1048577); if (raw.length > 1048576) throw incompatible("The SDK package index is too large");
                for (var tool : node(raw).path("tools")) for (var e : tool.path("examples")) {
                    if (e.has("expectedError") || e.has("exchanges") || !e.path("expectedOutput").isTextual()) continue;
                    var arguments = node(e.path("arguments").asText()); var expected = node(e.path("expectedOutput").asText());
                    if (arguments.isObject() && expected.isObject() && arguments.size() <= 32 && expected.size() <= 32) { result.putIfAbsent(tool.path("name").asText(), new JsonNode[]{arguments, expected}); }
                }
            }
        } catch (java.io.IOException | RuntimeException invalid) { if (invalid instanceof Failure f) throw f; throw incompatible("The SDK package index cannot be read"); }
        return result;
    }

    record Image(String reference, String javaVersion, List<String> architectures) {}

    /** Builds one manifest per supported base platform and an index, pushing every blob and manifest by digest. */
    private Image push(String packageId, String version, byte[] jar, boolean python) {
        String baseRepository = python ? settings.pythonBaseRepository() : settings.baseRepository();
        String baseDigest = python ? settings.pythonBaseDigest() : settings.baseDigest();
        var layer = OciImages.layer(packageId, python ? "tool.pyz" : "tool.jar", jar);
        var base = registry.manifest(baseRepository, baseDigest);
        var platforms = new ArrayList<JsonNode[]>(); // {descriptor or null, manifest}
        if (OciImages.OCI_INDEX.equals(base.mediaType()) || OciImages.DOCKER_LIST.equals(base.mediaType())) {
            for (var entry : node(base.body()).path("manifests")) {
                var p = entry.path("platform");
                if (!"linux".equals(p.path("os").asText()) || !ARCHITECTURES.containsKey(p.path("architecture").asText())) continue;
                var m = registry.manifest(baseRepository, entry.path("digest").asText());
                platforms.add(new JsonNode[]{entry, JSON.objectNode().put("mediaType", m.mediaType()).set("body", node(m.body()))});
            }
        } else platforms.add(new JsonNode[]{null, JSON.objectNode().put("mediaType", base.mediaType()).set("body", node(base.body()))});
        if (platforms.isEmpty()) throw incompatible("The approved base has no linux/amd64 or linux/arm64 image");
        registry.putBlob(settings.repository(), layer.digest(), layer.gzip());
        var entries = new ArrayList<OciImages.Platform>(); var architectures = new TreeSet<String>(); String java = null;
        for (var platform : platforms) {
            var manifest = platform[1].get("body"); String mediaType = platform[1].get("mediaType").asText();
            String configDigest = manifest.path("config").path("digest").asText();
            var baseConfig = node(registry.blob(baseRepository, configDigest, 1048576));
            String architecture = baseConfig.path("architecture").asText(), os = baseConfig.path("os").asText();
            if (!"linux".equals(os) || !ARCHITECTURES.containsKey(architecture)) continue;
            String javaVersion = python ? OciImages.pythonVersion(baseConfig) : OciImages.javaVersion(baseConfig);
            if (java != null && !java.equals(javaVersion)) throw incompatible("The approved base platforms declare different Java versions");
            java = javaVersion;
            for (var l : manifest.path("layers")) { String d = l.path("digest").asText(); if (!registry.hasBlob(settings.repository(), d)) registry.mount(settings.repository(), d, baseRepository); }
            byte[] config = OciImages.config(baseConfig, layer, "toolgate client release " + packageId + "@" + version);
            registry.putBlob(settings.repository(), OciImages.sha256(config), config);
            byte[] built = OciImages.manifest(manifest, mediaType, config, layer);
            String digest = OciImages.sha256(built);
            registry.putManifest(settings.repository(), digest, mediaType, built);
            var p = platform[0] == null ? null : platform[0].path("platform");
            entries.add(new OciImages.Platform(os, architecture, p == null || !p.has("variant") ? null : p.get("variant").asText(), digest, built.length, mediaType));
            architectures.add(ARCHITECTURES.get(architecture));
        }
        if (entries.isEmpty()) throw incompatible("The approved base has no linux/amd64 or linux/arm64 image");
        byte[] index = OciImages.index(entries); String indexDigest = OciImages.sha256(index);
        registry.putManifest(settings.repository(), indexDigest, OciImages.OCI_INDEX, index);
        return new Image(registry.host() + "/" + settings.repository() + "@" + indexDigest, java, List.copyOf(architectures));
    }

    /** Disabled tool records and their extractors, matching the profiles devices will report; an existing record keeps
     *  its enabled state and group, and moves to the new revision only when it changed. */
    private Map<String, JsonNode> directory(DirectoryService.Actor actor, String packageId, List<ObjectNode[]> records, String requestId) {
        return store.transaction(actor.tenant(), true, tx -> {
            new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.publish", clock.millis());
            var before = tx.load(); var entries = new HashMap<>(before.entries()); boolean changed = false; var stored = new HashMap<String, JsonNode>();
            for (var pair : records) {
                var tool = pair[0].deepCopy(); var extractor = pair[1].deepCopy(); String toolId = tool.get("id").asText();
                changed |= put(entries, Kind.EXTRACTOR, extractor.get("id").asText(), extractor, true);
                stored.put(toolId, node(entries.get(Kind.EXTRACTOR.id(extractor.get("id").asText())).document()));
                boolean added = !entries.containsKey(Kind.TOOL.id(toolId));
                changed |= put(entries, Kind.TOOL, toolId, tool, false);
                if (added) GroupGraph.addDefault(entries, codec, Kind.TOOL, toolId);
            }
            if (!changed) return stored;
            var after = new Directory(before.revision() + 1, entries);
            try { after.validate(512, 1048576); codec.validatePolicies(after); } catch (IllegalArgumentException invalid) { throw Failure.conflict(); }
            tx.save(before, after);
            tx.audit(actor.id(), "PACKAGE_CLIENT_RELEASE", "package:" + packageId, after.revision(), requestId, DirectoryService.digest(codec.json(stored)));
            return stored;
        });
    }
    private boolean put(Map<Ids.RecordId, Directory.Entry> entries, Kind kind, String id, ObjectNode document, boolean enabled) {
        var previous = entries.get(kind.id(id));
        if (previous != null) {
            var old = (ObjectNode) node(previous.document());
            document.put("enabled", kind == Kind.TOOL ? previous.enabled() : enabled);
            document.put("revision", old.get("revision").asLong());
            if (codec.json(old).equals(codec.json(document))) return false;
            document.put("revision", old.get("revision").asLong() + 1);
        } else document.put("enabled", enabled);
        entries.put(kind.id(id), codec.entry(kind, codec.json(document)));
        return true;
    }
}
