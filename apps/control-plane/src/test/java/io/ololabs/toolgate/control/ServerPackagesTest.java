// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

final class ServerPackagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    /** A package as `toolgate pack` lays it out: one Java tool with a global and a brokered destination. */
    static Map<String, byte[]> files() throws Exception {
        var descriptor = (ObjectNode) JSON.readTree("""
            {"descriptorGeneration":1,"toolId":"acme.orders/items.get","mcpName":"orders.items.get","kind":"TOOL","title":"Get item",
             "description":"Gets one item.","inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]},
             "errors":[],"effect":{"safety":"READ_ONLY","destructive":false,"openWorld":true},"businessKey":{"required":false},
             "execution":{"mode":"SYNC"},"routes":[{"id":"items.get","destination":"items-api","method":"GET","path":"/items/{id}",
             "query":[],"headers":[],"requestContentTypes":[],"maxRequestBytes":0,"maxResponseBytes":65536,"streaming":"NONE","timeoutMs":5000}],
             "destinations":[{"id":"items-api","hostSetting":"ItemsApiHost","port":443,"spkiPins":[]}],"httpAuth":[],"tcp":[],
             "variables":{"globals":[{"name":"Greeting","type":"STRING","required":true,"sensitive":false,"default":"Hello"}],"secrets":[{"name":"ApiKey","type":"STRING","scope":"TENANT"}],"claims":[]},
             "permissions":["HTTP_ROUTE"],"toolsets":[]}""");
        byte[] canonical = Canonical.bytes(descriptor), jar = "jar bytes".getBytes(StandardCharsets.UTF_8);
        String digest = Canonical.sha256(canonical);
        var manifest = (ObjectNode) JSON.readTree("""
            {"schemaVersion":2,"kind":"SDK","id":"acme.orders","version":"0.1.0","publisher":"Acme","sdk":{"language":"JAVA","version":"0.1.0","descriptorGeneration":1},
             "compatibility":{},"runtimes":[{"runtimeId":"jvm","language":"java","version":"17"}],"credentialReferences":[],"connections":[],"composition":[],
             "limits":{"maxTools":64,"maxDescriptorBytes":1048576}}""");
        var tool = manifest.putArray("tools").addObject(); tool.set("descriptor", descriptor); tool.put("toolDescriptorDigest", digest).put("runtimeId", "jvm"); tool.putArray("artifactIds").add("app");
        manifest.putArray("artifacts").addObject().put("artifactId", "app").put("path", "artifacts/app.jar").put("runtimeId", "jvm").put("artifactDigest", Canonical.sha256(jar)).put("sizeBytes", jar.length);
        var set = JSON.createArrayNode(); set.addObject().put("toolId", "acme.orders/items.get").put("toolDescriptorDigest", digest);
        manifest.put("descriptorSetDigest", Canonical.digest(set));
        var files = new TreeMap<String, byte[]>();
        files.put("artifacts/app.jar", jar); files.put("tools/items.get.json", canonical); files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }
    static byte[] zip(Map<String, byte[]> files) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                var entry = new ZipEntry(e.getKey()); entry.setMethod(ZipEntry.STORED); entry.setSize(e.getValue().length);
                var crc = new CRC32(); crc.update(e.getValue()); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private JsonNode node(String document) throws Exception { return JSON.readTree(document); }

    @Test void archiveRulesMatchThePackager() throws Exception {
        var codec = new ContractCodec(); var files = files();
        var verified = PackageArchive.validate(zip(files), document -> (JsonNode) codec.value(document));
        assertEquals(Canonical.sha256(files.get("CHECKSUMS.sha256")), verified.packageDigest());
        var tampered = new TreeMap<>(files); tampered.put("artifacts/app.jar", "other".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, assertThrows(Failure.class, () -> PackageArchive.validate(zip(tampered), document -> (JsonNode) codec.value(document))).status());
        var extra = new TreeMap<>(files); extra.put("notes.txt", new byte[1]);
        assertThrows(Failure.class, () -> PackageArchive.validate(zip(extra), document -> (JsonNode) codec.value(document)));
        var traversal = new TreeMap<>(files); traversal.put("../evil", new byte[1]);
        assertThrows(Failure.class, () -> PackageArchive.validate(zip(traversal), document -> (JsonNode) codec.value(document)));
        // RFC 8785: ECMAScript numbers, UTF-16 key order; arguments are not NFC-normalised.
        assertEquals("sha256:" + DirectoryService.digest("{\"a\":1e+21,\"b\":[0.5,\"é\"]}"), Canonical.arguments(JSON.readTree("{\"b\":[0.5,\"é\"],\"a\":1e21}")));
    }

    @Test void deploymentWritesDisabledToolsAndTheHostFetchesThem() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(SqliteState.open(directory.resolve("packages.sqlite")), codec);
        var tenant = new Ids.TenantId("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        var files = files(); byte[] data = zip(files);
        var uploaded = packages.upload(actor, data, "request-1");
        assertEquals(201, uploaded.status());
        String digest = node(uploaded.body()).get("packageDigest").asText();
        assertEquals(200, packages.upload(actor, data, "request-2").status());

        assertEquals(400, assertThrows(Failure.class, () -> packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{}}", "request-3")).status());
        assertEquals(400, assertThrows(Failure.class, () -> packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\",\"Other\":1}}", "request-3")).status());
        var deployed = node(packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"}}", "request-4").body());
        assertEquals(1, deployed.get("revision").asLong());
        var tool = store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get")));
        assertFalse(tool.enabled());
        assertTrue(store.transaction(tenant, false, tx -> codec.model(tx.load().entries().get(Ids.Kind.TOOL_GROUP.id("default-tools")).document(), ControlToolGroup.class)).toolIds().contains("acme.orders/items.get"));
        var extractor = codec.model(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.EXTRACTOR.id(codec.model(tool.document(), ControlTool.class).extractorId()))).document(), ControlResourceExtractor.class);
        assertEquals(List.of("tool://acme.orders/items.get", "secret://ApiKey"), extractor.fixedResources().stream().map(ResourceDescriptor::locator).toList());

        var device = new EndpointDeviceRecord("host-1", "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
        assertEquals(403, assertThrows(Failure.class, () -> packages.assignments(tenant, tx -> device)).status());
        assertEquals(403, assertThrows(Failure.class, () -> packages.register(tenant, tx -> device, "{\"hostId\":\"other\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "request-5")).status());
        packages.register(tenant, tx -> device, "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "request-6");
        var assignments = node(packages.assignments(tenant, tx -> device).body());
        assertEquals("acme.orders/items.get", assignments.get("tools").get(0).get("toolId").asText());
        assertFalse(assignments.get("tools").get(0).get("enabled").asBoolean());
        assertEquals(deployed.get("serviceProfileDigest"), assignments.get("deployments").get(0).get("serviceProfileDigest"));
        assertArrayEquals(data, packages.content(tenant, tx -> device, digest));
        var profile = node(packages.document(tenant, tx -> device, deployed.get("serviceProfileDigest").asText()).body());
        assertEquals("items.example.com", profile.get("destinations").get(0).get("host").asText());
        assertEquals(deployed.get("serviceProfileDigest").asText(), Canonical.digest(profile));
        var globals = node(packages.document(tenant, tx -> device, deployed.get("globalsDigest").asText()).body());
        assertEquals("Hello", globals.get("Greeting").asText());
        assertEquals(404, assertThrows(Failure.class, () -> packages.content(tenant, tx -> device, "sha256:" + "0".repeat(64))).status());

        // An administrator enables a deployed tool through the ordinary directory update.
        var directory = new DirectoryService(store, codec, 512, 1048576);
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) node(directory.get(actor, Ids.Kind.TOOL, "acme.orders/items.get").body());
        directory.mutate(actor, Ids.Kind.TOOL, "acme.orders/items.get", "UPDATE", entry.put("enabled", true).toString(), entry.get("revision").asLong(), "enable-1", "request-8");
        assertTrue(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get"))).enabled());

        // A configuration export with deployed tools imports again.
        var snapshot = directory.export(actor, false);
        var input = "{\"snapshot\":" + snapshot.body() + ",\"mode\":\"REPLACE\",\"dryRun\":true}";
        assertTrue(directory.importConfig(actor, input, false, snapshot.revision(), "dry-key", "request-9").body().contains("\"applied\":false"));
        // Shadow evaluation reads the snapshot through the typed model; deployed tools survive that too.
        var typed = codec.json(codec.model(snapshot.body(), ControlSnapshot.class));
        codec.input("{\"snapshot\":" + typed + ",\"mode\":\"REPLACE\",\"dryRun\":true}", false, tenant);

        // Redeploying keeps the tool's state and moves the deployment to its next revision.
        var again = node(packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items2.example.com\"}}", "request-7").body());
        assertEquals(2, again.get("revision").asLong());
    }
}
