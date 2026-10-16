// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

final class ClientReleasesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "mirror/eclipse-temurin", REPOSITORY = "toolgate/client-tools";
    @TempDir Path directory;
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    /** A minimal OCI Distribution registry that challenges with a bearer token, as hosted registries do. */
    static final class Registry {
        final Map<String, byte[]> blobs = new ConcurrentHashMap<>(); // repo + "@" + digest
        final Map<String, String[]> manifests = new ConcurrentHashMap<>(); // repo + "@" + digest -> {mediaType, body}
        final Map<String, String> uploads = new ConcurrentHashMap<>();
        int mounts, copies;
        void blob(String repo, byte[] bytes) { blobs.put(repo + "@" + OciImages.sha256(bytes), bytes); }
        String manifest(String repo, String mediaType, byte[] bytes) { String d = OciImages.sha256(bytes); manifests.put(repo + "@" + d, new String[]{mediaType, new String(bytes, StandardCharsets.UTF_8)}); return d; }
    }
    private Registry registry; private String origin;

    private void start() throws Exception {
        registry = new Registry();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", exchange -> {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Basic " + Base64.getEncoder().encodeToString("robot:secret".getBytes(StandardCharsets.UTF_8))).equals(auth)) { send(exchange, 401, new byte[0], null); return; }
            send(exchange, 200, "{\"token\":\"registry-token\"}".getBytes(StandardCharsets.UTF_8), "application/json");
        });
        server.createContext("/v2/", this::v2);
        server.start();
    }
    private void v2(HttpExchange exchange) throws java.io.IOException {
        if (!"Bearer registry-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + origin + "/token\",service=\"registry\"");
            send(exchange, 401, new byte[0], null); return;
        }
        String path = exchange.getRequestURI().getRawPath(), query = Optional.ofNullable(exchange.getRequestURI().getRawQuery()).orElse("");
        String method = exchange.getRequestMethod(); byte[] body = exchange.getRequestBody().readAllBytes();
        if (path.contains("/blobs/uploads/") && method.equals("POST")) {
            String repo = path.substring(4, path.indexOf("/blobs/uploads/"));
            var params = params(query);
            if (params.containsKey("mount")) {
                var source = registry.blobs.get(params.get("from") + "@" + params.get("mount"));
                if (source != null) { registry.blobs.put(repo + "@" + params.get("mount"), source); registry.mounts++; send(exchange, 201, new byte[0], null); return; }
            }
            String id = UUID.randomUUID().toString(); registry.uploads.put(id, repo);
            exchange.getResponseHeaders().add("Location", "/v2/" + repo + "/blobs/uploads/" + id + "?state=x");
            send(exchange, 202, new byte[0], null); return;
        }
        if (path.contains("/blobs/uploads/") && method.equals("PUT")) {
            String id = path.substring(path.lastIndexOf('/') + 1); String repo = registry.uploads.remove(id); String digest = params(query).get("digest");
            if (repo == null || !OciImages.sha256(body).equals(digest)) { send(exchange, 400, new byte[0], null); return; }
            registry.blobs.put(repo + "@" + digest, body); registry.copies++; send(exchange, 201, new byte[0], null); return;
        }
        int blobs = path.lastIndexOf("/blobs/"), manifests = path.lastIndexOf("/manifests/");
        if (blobs > 0) {
            var bytes = registry.blobs.get(path.substring(4, blobs) + "@" + path.substring(blobs + 7));
            if (bytes == null) { send(exchange, 404, new byte[0], null); return; }
            send(exchange, 200, method.equals("HEAD") ? null : bytes, "application/octet-stream"); return;
        }
        if (manifests > 0) {
            String key = path.substring(4, manifests) + "@" + path.substring(manifests + 11);
            if (method.equals("PUT")) {
                if (!OciImages.sha256(body).equals(path.substring(manifests + 11))) { send(exchange, 400, new byte[0], null); return; }
                registry.manifests.put(key, new String[]{exchange.getRequestHeaders().getFirst("Content-Type"), new String(body, StandardCharsets.UTF_8)}); send(exchange, 201, new byte[0], null); return;
            }
            var m = registry.manifests.get(key);
            if (m == null) { send(exchange, 404, new byte[0], null); return; }
            send(exchange, 200, m[1].getBytes(StandardCharsets.UTF_8), m[0]); return;
        }
        send(exchange, 404, new byte[0], null);
    }
    private static Map<String, String> params(String query) {
        var out = new HashMap<String, String>();
        for (var pair : query.split("&")) { int eq = pair.indexOf('='); if (eq > 0) out.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)); }
        return out;
    }
    private static void send(HttpExchange exchange, int status, byte[] body, String type) throws java.io.IOException {
        if (type != null) exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
        if (body != null && body.length > 0) exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** The approved base as a two-platform index on the mirror repository. */
    private String seedBase() throws Exception {
        var entries = new ArrayList<OciImages.Platform>();
        for (var arch : List.of("amd64", "arm64")) {
            byte[] layer = ("base layer " + arch).getBytes(StandardCharsets.UTF_8); registry.blob(BASE, layer);
            var config = (ObjectNode) JSON.readTree("{\"architecture\":\"" + arch + "\",\"os\":\"linux\",\"config\":{\"Env\":[\"PATH=/opt/java/openjdk/bin:/usr/bin\",\"JAVA_VERSION=jdk-21.0.12.1+1\"],\"Entrypoint\":[\"/__cacert_entrypoint.sh\"],\"Cmd\":[\"jshell\"]},"
                + "\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[\"" + OciImages.sha256(("diff " + arch).getBytes(StandardCharsets.UTF_8)) + "\"]},\"history\":[{\"created_by\":\"base\"}]}");
            byte[] configBytes = OciImages.bytes(config); registry.blob(BASE, configBytes);
            var manifest = JSON.createObjectNode().put("schemaVersion", 2).put("mediaType", OciImages.OCI_MANIFEST);
            manifest.putObject("config").put("mediaType", OciImages.OCI_CONFIG).put("digest", OciImages.sha256(configBytes)).put("size", configBytes.length);
            manifest.putArray("layers").addObject().put("mediaType", OciImages.OCI_LAYER).put("digest", OciImages.sha256(layer)).put("size", layer.length);
            byte[] bytes = OciImages.bytes(manifest); String digest = registry.manifest(BASE, OciImages.OCI_MANIFEST, bytes);
            entries.add(new OciImages.Platform("linux", arch, null, digest, bytes.length, OciImages.OCI_MANIFEST));
        }
        return registry.manifest(BASE, OciImages.OCI_INDEX, OciImages.index(entries));
    }

    /** A Java SDK package: a ClientTool and a route-free ServerTool devices can run, and a ServerTool needing routes. */
    static byte[] sdkPackage() throws Exception {
        var jar = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(jar)) {
            zip.putNextEntry(new ZipEntry("META-INF/toolgate/package.json"));
            zip.write(("{\"tools\":[{\"name\":\"client_greet\",\"examples\":[{\"arguments\":\"{}\",\"expectedOutput\":\"{\\\"message\\\":\\\"Hello World\\\"}\"}]},"
                + "{\"name\":\"server_greet\",\"examples\":[{\"arguments\":\"{\\\"text\\\":\\\"Ada\\\"}\",\"expectedOutput\":\"{\\\"message\\\":\\\"Hello Ada\\\"}\"}]},"
                + "{\"name\":\"fetch\",\"examples\":[{\"arguments\":\"{}\",\"expectedOutput\":\"{}\"}]}]}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("io/ololabs/toolgate/runtime/Main.class")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        byte[] jarBytes = jar.toByteArray();
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"text\":{\"type\":\"string\",\"default\":\"World\",\"maxLength\":128}},\"required\":[]}";
        String output = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}";
        var files = new TreeMap<String, byte[]>(); var manifest = (ObjectNode) JSON.readTree("""
            {"schemaVersion":2,"kind":"SDK","id":"acme.greet","version":"1.0.0","publisher":"Acme","sdk":{"language":"JAVA","version":"0.1.0","protocol":{"min":2,"max":2},"descriptorGeneration":1},
             "compatibility":{"contractsVersion":"1.0.0"},"runtimes":[{"runtimeId":"jvm","language":"java","version":"17"}],"credentialReferences":[],"connections":[],"composition":[],
             "limits":{"maxTools":64,"maxDescriptorBytes":1048576}}""");
        var tools = manifest.putArray("tools"); var set = JSON.createArrayNode();
        for (var spec : List.of(new String[]{"client_greet", "CLIENT"}, new String[]{"fetch", "SERVER"}, new String[]{"server_greet", "SERVER"})) {
            var d = (ObjectNode) JSON.readTree("{\"descriptorGeneration\":1,\"toolId\":\"acme.greet/" + spec[0] + "\",\"mcpName\":\"greet." + spec[0] + "\",\"kind\":\"" + spec[1] + "\",\"title\":\"Greet\",\"description\":\"Greets.\","
                + "\"inputSchema\":" + schema + ",\"outputSchema\":" + output + ",\"errors\":[],\"effect\":{\"safety\":\"READ_ONLY\",\"destructive\":false,\"openWorld\":false},\"businessKey\":{\"required\":false},"
                + "\"execution\":{\"async\":false,\"maxDurationMs\":30000},\"routes\":[],\"destinations\":[],\"httpAuth\":[],\"tcp\":[],\"variables\":{\"globals\":[],\"secrets\":[],\"claims\":[]},\"permissions\":[],\"toolsets\":[]}");
            if (spec[0].equals("fetch")) {
                d.set("routes", JSON.readTree("[{\"id\":\"items.get\",\"destination\":\"api\",\"method\":\"GET\",\"path\":\"/items\",\"query\":[],\"headers\":[],\"requestContentTypes\":[],\"maxRequestBytes\":0,\"maxResponseBytes\":65536,\"streaming\":\"NONE\",\"timeoutMs\":5000}]"));
                d.set("destinations", JSON.readTree("[{\"id\":\"api\",\"hostSetting\":\"ApiHost\",\"port\":443,\"spkiPins\":[]}]"));
                d.putArray("permissions").add("HTTP_ROUTE");
            }
            byte[] canonical = Canonical.bytes(d); String digest = Canonical.sha256(canonical);
            var tool = tools.addObject(); tool.set("descriptor", d); tool.put("toolDescriptorDigest", digest).put("runtimeId", "jvm").put("entryPoint", ClientReleases.JAVA_SDK_MAIN); tool.putArray("artifactIds").add("app");
            set.addObject().put("toolId", "acme.greet/" + spec[0]).put("toolDescriptorDigest", digest);
            files.put("tools/" + spec[0] + ".json", canonical);
        }
        manifest.putArray("artifacts").addObject().put("artifactId", "app").put("path", "artifacts/app.jar").put("mediaType", "application/java-archive").put("runtimeId", "jvm").put("artifactDigest", Canonical.sha256(jarBytes)).put("sizeBytes", jarBytes.length);
        manifest.put("descriptorSetDigest", Canonical.digest(set));
        files.put("artifacts/app.jar", jarBytes); files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return ServerPackagesTest.zip(files);
    }

    private record Rig(PostgresStore store, ContractCodec codec, Ids.TenantId tenant, DirectoryService.Actor admin, String digest, ClientReleases releases, RsaFleetCrypto crypto, LocalArtifactStore artifacts) {}

    private Rig rig(boolean signed, String credentials) throws Exception {
        start(); String baseDigest = seedBase();
        var codec = new ContractCodec(); var store = new PostgresStore(SqliteState.open(directory.resolve("releases.sqlite")), codec);
        var tenant = new Ids.TenantId("fleet"); var admin = TestSupport.seed(store, codec, tenant, "root");
        String digest = JSON.readTree(new ServerPackages(store, codec, null, java.time.Clock.systemUTC()).upload(admin, sdkPackage(), "upload").body()).get("packageDigest").asText();
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var organization = generator.generateKeyPair(); var release = generator.generateKeyPair();
        var crypto = new RsaFleetCrypto(codec, (RSAPrivateCrtKey) organization.getPrivate(), "org", List.of(trust("release", release.getPublic())), List.of(trust("org", organization.getPublic())), List.of());
        var endpoint = new EndpointService(store, codec, null, java.time.Clock.systemUTC(), true, "fleet", "server", "Organization", "https://control.example.test", "https://gateway.example.test");
        var fleet = new FleetService(store, codec, crypto, endpoint, java.time.Clock.systemUTC(), true, "fleet", "server");
        java.nio.file.Files.createDirectories(directory.resolve("artifacts"));
        var artifacts = new LocalArtifactStore(directory.resolve("artifacts"));
        var settings = ClientReleases.Settings.parse(REPOSITORY, BASE + "@" + baseDigest, "LINUX", "0.10.0-dev");
        var signer = signed ? new RsaPackageReleaseSigner(codec, (RSAPrivateCrtKey) release.getPrivate(), "release") : null;
        var releases = new ClientReleases(store, codec, new HttpsOciRegistry(origin, credentials, null), settings, signer, fleet, artifacts, java.time.Clock.systemUTC());
        return new Rig(store, codec, tenant, admin, digest, releases, crypto, artifacts);
    }
    private static FleetTrustKey trust(String kid, java.security.PublicKey key) {
        byte[] n = ((RSAPublicKey) key).getModulus().toByteArray(); if (n[0] == 0) n = Arrays.copyOfRange(n, 1, n.length);
        return new FleetTrustKey(kid, Base64.getUrlEncoder().withoutPadding().encodeToString(n), "AQAB");
    }

    @Test void aSignedClientReleasePushesADeterministicImageAndPinsItInTheFleetDocument() throws Exception {
        var rig = rig(true, "robot:secret");
        var result = JSON.readTree(rig.releases.release(rig.admin, "{\"packageDigest\":\"" + rig.digest + "\"}", "release-1").body());
        String image = result.get("image").asText();
        assertTrue(image.startsWith("127.0.0.1:" + server.getAddress().getPort() + "/" + REPOSITORY + "@sha256:"), image);
        assertEquals("21.0.12+1", result.get("runtimeVersion").asText());
        // Exports devices can run are released; one needing Tool Host routes is reported, not weakened.
        var tools = new HashMap<String, JsonNode>(); result.get("tools").forEach(t -> tools.put(t.get("toolId").asText(), t));
        assertTrue(tools.get("acme.greet/client_greet").get("supported").asBoolean());
        assertTrue(tools.get("acme.greet/server_greet").get("supported").asBoolean());
        assertFalse(tools.get("acme.greet/fetch").get("supported").asBoolean());
        assertTrue(tools.get("acme.greet/fetch").get("reason").asText().contains("network"));

        // The index pins one manifest per base platform; each ends with the artifact layer on the mirrored base layer.
        String indexDigest = image.substring(image.indexOf('@') + 1);
        var index = JSON.readTree(registry.manifests.get(REPOSITORY + "@" + indexDigest)[1]);
        assertEquals(2, index.get("manifests").size());
        for (var entry : index.get("manifests")) {
            var manifest = JSON.readTree(registry.manifests.get(REPOSITORY + "@" + entry.get("digest").asText())[1]);
            assertEquals(2, manifest.get("layers").size());
            for (var layer : manifest.get("layers")) assertNotNull(registry.blobs.get(REPOSITORY + "@" + layer.get("digest").asText()));
            var config = JSON.readTree(registry.blobs.get(REPOSITORY + "@" + manifest.get("config").get("digest").asText()));
            assertFalse(config.get("config").has("Entrypoint")); assertFalse(config.get("config").has("Cmd"));
            assertEquals("65532:65532", config.get("config").get("User").asText());
            assertEquals(2, config.get("rootfs").get("diff_ids").size());
            assertEquals(OciImages.EPOCH, config.get("created").asText());
        }
        assertEquals(2, registry.mounts);

        // The document: one JAVA_SDK runtime on that image, a registration and a self-test per released export.
        var document = rig.codec.model(rig.codec.json(result.get("document")), FleetPackageDocument.class);
        FleetService.validatePackage(document);
        assertEquals(LocalRuntimeKind.JAVA_SDK, document.runtimes().getFirst().kind());
        assertEquals(image, document.runtimes().getFirst().image());
        assertEquals(List.of("acme.greet/client_greet", "acme.greet/server_greet"), document.tools().stream().map(LocalToolRegistration::toolId).toList());
        assertEquals("/opt/tool/acme.greet/tool.jar", document.tools().getFirst().entryPoint());
        for (var tool : document.tools()) InstalledProfiles.validate(tool, document.runtimes().getFirst());
        assertEquals("Hello Ada", document.selfTests().stream().filter(t -> t.toolId().equals("acme.greet/server_greet")).findFirst().orElseThrow().expectedOutput().get("message").asText());

        // Signed by the release key, mirrored, and recorded as a fleet release.
        var release = rig.codec.model(rig.codec.json(result.get("release")), FleetPackageRelease.class);
        var verified = rig.crypto.release(release.release());
        assertArrayEquals(Base64.getUrlDecoder().decode(result.get("documentBase64").asText()), verified.bytes());
        assertArrayEquals(verified.bytes(), rig.artifacts.download(release.manifestDigest(), release.sizeBytes()));

        // Directory records are disabled and match the profiles devices will report, extractor byte for byte.
        var entry = rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.greet/client_greet")));
        assertFalse(entry.enabled());
        var profile = document.tools().getFirst().authorizationProfile();
        String extractor = rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.EXTRACTOR.id(profile.tool().extractorId())).document());
        assertEquals(rig.codec.json(profile.extractor()), extractor);
        assertEquals(profile.tool().packageDigest(), rig.codec.model(entry.document(), ControlTool.class).packageDigest());

        // The same package releases to the same image and document.
        var again = JSON.readTree(rig.releases.release(rig.admin, "{\"packageDigest\":\"" + rig.digest + "\"}", "release-2").body());
        assertEquals(image, again.get("image").asText());
        assertEquals(result.get("manifestDigest"), again.get("manifestDigest"));
    }

    @Test void withoutTheReleaseKeyTheExactDocumentIsReturnedForTheReleaseAuthority() throws Exception {
        var rig = rig(false, "robot:secret");
        var result = JSON.readTree(rig.releases.release(rig.admin, "{\"packageDigest\":\"" + rig.digest + "\"}", "release-1").body());
        assertFalse(result.get("signed").asBoolean()); assertFalse(result.has("release"));
        byte[] bytes = Base64.getUrlDecoder().decode(result.get("documentBase64").asText());
        assertEquals(result.get("manifestDigest").asText(), FleetService.digest(bytes));
        assertThrows(Failure.class, () -> rig.artifacts.download(result.get("manifestDigest").asText(), bytes.length));
    }

    @Test void refusalsAreExplicit() throws Exception {
        var rig = rig(true, "robot:wrong");
        // Wrong registry credentials: the token is refused and nothing is released.
        assertEquals(503, assertThrows(Failure.class, () -> rig.releases.release(rig.admin, "{\"packageDigest\":\"" + rig.digest + "\"}", "release-1")).status());
        assertEquals(404, assertThrows(Failure.class, () -> rig.releases.release(rig.admin, "{\"packageDigest\":\"sha256:" + "0".repeat(64) + "\"}", "release-2")).status());
        var reader = new DirectoryService.Actor(rig.tenant, "b".repeat(64), true, false, "mallory");
        assertThrows(Failure.class, () -> rig.releases.release(reader, "{\"packageDigest\":\"" + rig.digest + "\"}", "release-3"));
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.greet/client_greet"))));
    }

    @Test void layersAreDeterministicAndConfined() {
        var a = OciImages.layer("acme.greet", new byte[]{1, 2, 3}); var b = OciImages.layer("acme.greet", new byte[]{1, 2, 3});
        assertArrayEquals(a.gzip(), b.gzip()); assertEquals(a.diffId(), b.diffId());
        assertNotEquals(a.digest(), OciImages.layer("acme.greet", new byte[]{1, 2, 4}).digest());
        assertThrows(Failure.class, () -> OciImages.layer("../escape", new byte[]{1}));
        assertEquals("21.0.12+1", OciImages.javaVersion(JSON.createObjectNode().set("config", JSON.createObjectNode().set("Env", JSON.createArrayNode().add("JAVA_VERSION=jdk-21.0.12.1+1")))));
        assertEquals("17.0.9", OciImages.javaVersion(JSON.createObjectNode().set("config", JSON.createObjectNode().set("Env", JSON.createArrayNode().add("JAVA_VERSION=jdk-17.0.9+9")))));
        assertThrows(Failure.class, () -> OciImages.javaVersion(JSON.createObjectNode()));
    }
}
