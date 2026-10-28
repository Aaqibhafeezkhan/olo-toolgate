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
    static Map<String, byte[]> files() throws Exception { return files(false); }
    /** split: the tool also needs a zip of libraries, which a Java tool may not. */
    static Map<String, byte[]> files(boolean split) throws Exception { return files(split, "SERVER"); }
    static Map<String, byte[]> files(boolean split, String kind) throws Exception { return files(split, kind, null); }
    /** authorization: the descriptor's OLO M3.7 declaration, or null. */
    static Map<String, byte[]> files(boolean split, String kind, String authorization) throws Exception {
        var descriptor = (ObjectNode) JSON.readTree("""
            {"descriptorGeneration":1,"toolId":"acme.orders/items.get","mcpName":"orders.items.get","kind":"SERVER","title":"Get item",
             "description":"Gets one item.","inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]},
             "errors":[],"effect":{"safety":"READ_ONLY","destructive":false,"openWorld":true},"businessKey":{"required":false},
             "execution":{"mode":"SYNC"},"routes":[{"id":"items.get","destination":"items-api","method":"GET","path":"/items/{id}",
             "query":[],"headers":[],"requestContentTypes":[],"maxRequestBytes":0,"maxResponseBytes":65536,"streaming":"NONE","timeoutMs":5000}],
             "destinations":[{"id":"items-api","hostSetting":"ItemsApiHost","port":443,"spkiPins":[]}],"httpAuth":[],"tcp":[],
             "variables":{"globals":[{"name":"Greeting","type":"STRING","required":true,"sensitive":false,"default":"Hello"}],"secrets":[{"name":"ApiKey","type":"STRING","scope":"TENANT"}],"claims":[]},
             "permissions":["HTTP_ROUTE"],"toolsets":[]}""");
        descriptor.put("kind", kind);
        if (authorization != null) descriptor.set("authorization", JSON.readTree(authorization));
        if (kind.equals("CLIENT")) {
            for (var field : List.of("routes", "destinations", "httpAuth", "tcp", "permissions")) descriptor.putArray(field);
            var variables = descriptor.putObject("variables");
            variables.putArray("globals"); variables.putArray("secrets"); variables.putArray("claims");
        }
        byte[] canonical = Canonical.bytes(descriptor), jar = "jar bytes".getBytes(StandardCharsets.UTF_8);
        String digest = Canonical.sha256(canonical);
        var manifest = (ObjectNode) JSON.readTree("""
            {"schemaVersion":2,"kind":"SDK","id":"acme.orders","version":"0.1.0","publisher":"Acme","sdk":{"language":"JAVA","version":"0.1.0","protocol":{"min":2,"max":2},"descriptorGeneration":1},
             "compatibility":{"contractsVersion":"@CONTRACTS@"},"runtimes":[{"runtimeId":"jvm","language":"java","version":"17"}],"credentialReferences":[],"connections":[],"composition":[],
             "limits":{"maxTools":64,"maxDescriptorBytes":1048576}}""".replace("@CONTRACTS@", io.ololabs.toolgate.contracts.ContractSet.VERSION));
        var tool = manifest.putArray("tools").addObject(); tool.set("descriptor", descriptor); tool.put("toolDescriptorDigest", digest).put("runtimeId", "jvm"); tool.putArray("artifactIds").add("app");
        manifest.putArray("artifacts").addObject().put("artifactId", "app").put("path", "artifacts/app.jar").put("mediaType", "application/java-archive").put("runtimeId", "jvm").put("artifactDigest", Canonical.sha256(jar)).put("sizeBytes", jar.length);
        if (split) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) tool.get("artifactIds")).add("libs");
            ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("artifacts")).addObject().put("artifactId", "libs").put("path", "artifacts/libs.zip").put("mediaType", "application/zip").put("runtimeId", "jvm").put("artifactDigest", Canonical.sha256(jar)).put("sizeBytes", jar.length);
        }
        var set = JSON.createArrayNode(); set.addObject().put("toolId", "acme.orders/items.get").put("toolDescriptorDigest", digest);
        manifest.put("descriptorSetDigest", Canonical.digest(set));
        var files = new TreeMap<String, byte[]>();
        files.put("artifacts/app.jar", jar); files.put("tools/items.get.json", canonical); if (split) files.put("artifacts/libs.zip", jar); files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }
    /** One package with the server tool and a ClientTool beside it, as the Hello World package ships both. */
    static Map<String, byte[]> mixed() throws Exception {
        var files = new TreeMap<>(files()); files.remove("CHECKSUMS.sha256");
        var manifest = (ObjectNode) JSON.readTree(files.get("toolgate-package.json"));
        var client = (ObjectNode) manifest.get("tools").get(0).get("descriptor").deepCopy();
        client.put("toolId", "acme.orders/items.local").put("mcpName", "orders.items.local").put("kind", "CLIENT");
        for (var field : List.of("routes", "destinations", "httpAuth", "tcp", "permissions")) client.putArray(field);
        var variables = client.putObject("variables"); variables.putArray("globals"); variables.putArray("secrets"); variables.putArray("claims");
        byte[] canonical = Canonical.bytes(client);
        var tool = ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("tools")).addObject();
        tool.set("descriptor", client); tool.put("toolDescriptorDigest", Canonical.sha256(canonical)).put("runtimeId", "jvm"); tool.putArray("artifactIds").add("app");
        var set = JSON.createArrayNode();
        for (var t : manifest.get("tools")) set.addObject().put("toolId", t.get("descriptor").get("toolId").asText()).put("toolDescriptorDigest", t.get("toolDescriptorDigest").asText());
        manifest.put("descriptorSetDigest", Canonical.digest(set));
        files.put("tools/items.local.json", canonical); files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest));
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

    @Test void deploymentPrivateRangesAreExplicitHostAndPortScopedAndCannotApprovePublicOrMetadataNetworks() throws Exception {
        var codec = new ContractCodec();
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("private-egress.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant");
        var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor,zip(files()),"upload").body()).path("packageDigest").asText();
        var request=JSON.createObjectNode().put("packageDigest",digest);
        request.putObject("globals").put("ItemsApiHost","items.internal");
        var range=request.putArray("privateRanges").addObject().put("host","items.internal").put("cidr","172.18.0.0/16");
        range.putArray("ports").add(443);
        var deployed=node(packages.deploy(actor,request.toString(),"deploy").body());
        var profile=store.transaction(tenant,false,tx -> nodeUnchecked(tx.packages().document(deployed.path("serviceProfileDigest").asText())));
        assertEquals("172.18.0.0/16",profile.path("privateRanges").get(0).path("cidr").asText());
        for(String cidr:List.of("0.0.0.0/0","169.254.169.254/32","127.0.0.0/8","172.16.0.0/8","not-a-cidr")) {
            range.put("cidr",cidr);
            assertThrows(Failure.class,()->packages.deploy(actor,request.toString(),"bad-network"));
        }
        range.put("cidr","172.18.0.0/16");range.put("host","other.internal");
        assertThrows(Failure.class,()->packages.deploy(actor,request.toString(),"bad-host"));
        range.put("host","items.internal");range.putArray("ports").add(8443);
        assertThrows(Failure.class,()->packages.deploy(actor,request.toString(),"bad-port"));
        request.remove("privateRanges");
        request.putObject("destinationTls").putObject("undeclared").putArray("spkiPins");
        assertThrows(Failure.class,()->packages.deploy(actor,request.toString(),"bad-destination"));
        request.putObject("destinationTls").putObject("items-api").put("caBundle","not a certificate").putArray("spkiPins");
        assertThrows(Failure.class,()->packages.deploy(actor,request.toString(),"bad-trust"));
        // A real PEM CA is much larger than the old 256-character contract bound.
        var trust=java.security.KeyStore.getInstance(java.nio.file.Path.of(System.getProperty("java.home"),"lib","security","cacerts").toFile(),"changeit".toCharArray());
        var certificate=trust.getCertificate(trust.aliases().nextElement());
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(certificate.getEncoded())+"\n-----END CERTIFICATE-----\n";
        assertTrue(pem.length()>256);
        request.putObject("destinationTls").putObject("items-api").put("caBundle",pem).putArray("spkiPins");
        var trusted=node(packages.deploy(actor,request.toString(),"valid-trust").body());
        var trustedProfile=store.transaction(tenant,false,tx -> nodeUnchecked(tx.packages().document(trusted.path("serviceProfileDigest").asText())));
        assertEquals(pem,trustedProfile.path("destinations").get(0).path("tls").path("caBundle").asText());
    }
    private static JsonNode nodeUnchecked(String value) {
        try{return JSON.readTree(value);}catch(Exception failure){throw new IllegalStateException(failure);}
    }

    @Test void archiveRulesMatchThePackager() throws Exception {
        var codec = new ContractCodec(); var files = files();
        var verified = PackageArchive.validate(zip(files), document -> (JsonNode) codec.value(document));
        assertEquals(Canonical.sha256(files.get("CHECKSUMS.sha256")), verified.packageDigest());
        var tampered = new TreeMap<>(files); tampered.put("artifacts/app.jar", "other".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, assertThrows(Failure.class, () -> PackageArchive.validate(zip(tampered), document -> (JsonNode) codec.value(document))).status());
        var extra = new TreeMap<>(files); extra.put("notes.txt", new byte[1]);
        assertThrows(Failure.class, () -> PackageArchive.validate(zip(extra), document -> (JsonNode) codec.value(document)));
        // A Java tool is one self-contained jar: a device needs only a JRE.
        assertThrows(Failure.class, () -> PackageArchive.validate(zip(files(true)), document -> (JsonNode) codec.value(document)));
        var traversal = new TreeMap<>(files); traversal.put("../evil", new byte[1]);
        assertThrows(Failure.class, () -> PackageArchive.validate(zip(traversal), document -> (JsonNode) codec.value(document)));
        // RFC 8785: ECMAScript numbers, UTF-16 key order; arguments are not NFC-normalised.
        assertEquals("sha256:" + DirectoryService.digest("{\"a\":1e+21,\"b\":[0.5,\"é\"]}"), Canonical.arguments(JSON.readTree("{\"b\":[0.5,\"é\"],\"a\":1e21}")));
    }

    @Test void aToolHostPermitIsRefusedForAClientToolEvenWhenTheRequestIsForged() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("forged.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var operations = new EnterpriseOperations(store, codec, null, java.time.Clock.systemUTC(), "control", "toolgate-client", 60000, 256);
        var packages = new ServerPackages(store, codec, operations, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor, zip(mixed()), "upload-mixed").body()).get("packageDigest").asText();
        var host = new EndpointDeviceRecord("host-1", "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
        TestSupport.pool(store, codec, tenant, "pool", "host-1"); // host-1 is an administered member of its pool
        packages.register(tenant, tx -> host, "{\"hostId\":\"host-1\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "register");
        var deployed = node(packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"},\"hostIds\":[\"host-1\"]}", "deploy").body());
        // The deployment exposes only the ServerTool.
        assertEquals("[\"acme.orders/items.get\"]", deployed.get("toolIds").toString());
        // An invocation naming the ClientTool on the Tool Host, as a forged or misplaced call would.
        var arguments = node("{\"id\":\"1\"}");
        String invocation = """
            {"id":"inv-forged","requestDigest":"%s","evaluation":{"context":{"requestId":"inv-forged","tenantId":"tenant","mode":"SERVICE","agentId":"agent-1","chain":[],"bindingId":"bind-1","deviceId":"host-1"},
             "toolId":"acme.orders/items.local","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"QUEUED","revision":1,"authorizationEpoch":0,"expiresAtUnixMs":%d,"completedResources":[],"diagnosticId":"diag"}"""
            .formatted("a".repeat(64), DirectoryService.digest(codec.json(arguments)), "c".repeat(64), digest.substring(7), System.currentTimeMillis() + 60000);
        store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(codec.model(invocation, EnterpriseInvocation.class), System.currentTimeMillis(), 0); return null; });
        var gateway = new DirectoryService.Actor(tenant, "e".repeat(64), false);
        var refused = assertThrows(Failure.class, () -> packages.permit(gateway,
            "{\"invocationId\":\"inv-forged\",\"audience\":\"host-1\",\"attempt\":1,\"arguments\":{\"id\":\"1\"}}", "permit"));
        assertEquals(403, refused.status()); assertEquals("Tool execution location does not allow this target", refused.getMessage());
        assertNull(store.transaction(tenant, false, tx -> tx.packages().relayed("inv-forged")));
        // Control: the same request for the ServerTool passes the location check and is refused only later
        // (this test has no execution binding or signer), so the refusal above is the execution-location rule.
        store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(codec.model(invocation.replace("inv-forged", "inv-server").replace("acme.orders/items.local", "acme.orders/items.get"), EnterpriseInvocation.class), System.currentTimeMillis(), 0); return null; });
        var server = assertThrows(RuntimeException.class, () -> packages.permit(gateway,
            "{\"invocationId\":\"inv-server\",\"audience\":\"host-1\",\"attempt\":1,\"arguments\":{\"id\":\"1\"}}", "permit-server"));
        assertNotEquals("Tool execution location does not allow this target", server.getMessage());
    }

    @Test void clientOnlyPackagesCannotBeDeployedToServerHosts() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("client-package.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor, zip(files(false, "CLIENT")), "upload-client").body()).get("packageDigest").asText();
        assertEquals(422, assertThrows(Failure.class, () -> packages.deploy(actor,
            "{\"packageDigest\":\"" + digest + "\",\"globals\":{}}", "deploy-client")).status());
        assertNull(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get"))));
    }

    /** OLO M3.7: a declared permission and locator template replace invoke on tool://. */
    @Test void declaredAuthorizationBecomesTheToolsActionAndResources() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("authorized.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor, zip(files(false, "SERVER", "{\"action\":\"items.read\",\"resources\":[\"olo://{tenant}/item/{id}\"]}")), "request-1").body()).get("packageDigest").asText();
        packages.deploy(actor, "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"}}", "request-2");
        var tool = codec.model(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/items.get"))).document(), ControlTool.class);
        assertEquals(List.of("items.read"), tool.definition().actions().stream().map(a -> a.name()).toList());
        var extractor = codec.model(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.EXTRACTOR.id(tool.extractorId()))).document(), ControlResourceExtractor.class);
        assertEquals(List.of("secret://ApiKey"), extractor.fixedResources().stream().map(ResourceDescriptor::locator).toList());
        assertEquals(List.of("olo://{tenant}/item/{id}"), extractor.locatorTemplates());
        String bad = "{\"action\":\"items.read\",\"resources\":[\"olo://{tenant}/item/{missing}\"]}";
        assertEquals(422, assertThrows(Failure.class, () -> packages.deploy(actor, "{\"packageDigest\":\"" + node(packages.upload(actor, zip(files(false, "SERVER", bad)), "request-3").body()).get("packageDigest").asText()
            + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"}}", "request-4")).status());
    }

    @Test void deploymentWritesDisabledToolsAndTheHostFetchesThem() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("packages.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
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
        TestSupport.pool(store, codec, tenant, "pool", "host-1"); // host-1 is an administered member of its pool
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
        // Nothing else about a tool can change here, and no tool or extractor can be created outside a package.
        var enabled = (com.fasterxml.jackson.databind.node.ObjectNode) node(directory.get(actor, Ids.Kind.TOOL, "acme.orders/items.get").body());
        var swapped = enabled.deepCopy().put("packageDigest", "b".repeat(64));
        assertEquals(422, assertThrows(Failure.class, () -> directory.mutate(actor, Ids.Kind.TOOL, "acme.orders/items.get", "UPDATE", swapped.toString(), enabled.get("revision").asLong(), "swap-1", "request-8a")).status());
        var injected = enabled.deepCopy().put("id", "acme.orders/injected").put("name", "acme.orders/injected").put("revision", 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) injected.get("definition")).put("id", "acme.orders/injected");
        assertEquals(422, assertThrows(Failure.class, () -> directory.mutate(actor, Ids.Kind.TOOL, "acme.orders/injected", "CREATE", injected.toString(), 0, "inject-1", "request-8b")).status());
        assertNull(store.transaction(tenant, false, tx -> tx.load().entries().get(Ids.Kind.TOOL.id("acme.orders/injected"))));

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

    @Test void aDeploymentRunsOnTheHostsItNamesAndHostsFetchRelayedCalls() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("targets.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor, zip(files()), "request-1").body()).get("packageDigest").asText();
        var hosts = new HashMap<String, EndpointDeviceRecord>();
        TestSupport.pool(store, codec, tenant, "pool", "host-1", "host-2");
        for (var id : List.of("host-1", "host-2")) {
            var device = new EndpointDeviceRecord(id, "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null);
            packages.register(tenant, tx -> device, "{\"hostId\":\"" + id + "\",\"poolId\":\"pool\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "register-" + id);
            hosts.put(id, device);
        }
        String body = "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"},\"hostIds\":[%s]}";
        // Only registered Tool Hosts can be named.
        assertEquals(400, assertThrows(Failure.class, () -> packages.deploy(actor, body.formatted("\"nowhere\""), "request-2")).status());

        // Deployed to host-1: only host-1 is assigned the tool and may fetch its package.
        var deployed = node(packages.deploy(actor, body.formatted("\"host-1\""), "request-3").body());
        assertEquals("host-1", deployed.get("hostIds").get(0).asText());
        assertEquals(1, node(packages.assignments(tenant, tx -> hosts.get("host-1")).body()).get("tools").size());
        assertEquals(0, node(packages.assignments(tenant, tx -> hosts.get("host-2")).body()).get("tools").size());
        assertEquals(404, assertThrows(Failure.class, () -> packages.content(tenant, tx -> hosts.get("host-2"), digest)).status());

        // Switching the device is a redeploy that names the other host.
        packages.deploy(actor, body.formatted("\"host-2\""), "request-4");
        assertEquals(0, node(packages.assignments(tenant, tx -> hosts.get("host-1")).body()).get("tools").size());
        assertEquals(1, node(packages.assignments(tenant, tx -> hosts.get("host-2")).body()).get("tools").size());
        assertEquals(404, assertThrows(Failure.class, () -> packages.content(tenant, tx -> hosts.get("host-1"), digest)).status());
        assertTrue(ServerPackages.targets(null, codec, node("{}"), "any-host"));

        // A relayed call waits for its host, which claims it, admits it and sends its result.
        var dispatch = "{\"permit\":{\"jws\":\"a.b.c\"},\"arguments\":{\"id\":\"1\"}}";
        var record = "{\"dispatch\":" + dispatch + ",\"status\":{\"invocationId\":\"inv-1\",\"hostId\":\"host-2\",\"state\":\"QUEUED\"}}";
        store.transaction(tenant, true, tx -> { tx.packages().saveRelayed(new PackageStore.Relayed("inv-1", "host-2", "QUEUED", System.currentTimeMillis() + 60000, record)); return null; });
        assertEquals(204, packages.next(tenant, tx -> hosts.get("host-1")).status());
        var next = packages.next(tenant, tx -> hosts.get("host-2"));
        assertEquals(node(dispatch), node(next.body()));
        assertEquals(204, packages.next(tenant, tx -> hosts.get("host-2")).status());
        assertEquals(404, assertThrows(Failure.class, () -> packages.admission(tenant, tx -> hosts.get("host-1"), "inv-1", "{\"kind\":\"ADMITTED\",\"invocationId\":\"inv-1\",\"fencingToken\":\"f-1\"}")).status());
        assertEquals(409, assertThrows(Failure.class, () -> packages.result(tenant, tx -> hosts.get("host-2"), "inv-1", "{\"invocationId\":\"inv-1\",\"outcome\":\"COMPLETED\"}")).status());
        assertEquals("ADMITTED", node(packages.admission(tenant, tx -> hosts.get("host-2"), "inv-1", "{\"kind\":\"ADMITTED\",\"invocationId\":\"inv-1\",\"fencingToken\":\"f-1\"}").body()).get("state").asText());
        var frame = "{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"isError\":false,\"provenance\":\"UNTRUSTED_UPSTREAM\"}";
        var finished = node(packages.result(tenant, tx -> hosts.get("host-2"), "inv-1", "{\"invocationId\":\"inv-1\",\"fencingToken\":\"f-1\",\"outcome\":\"COMPLETED\",\"result\":" + frame + "}").body());
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals("COMPLETED", finished.get("result").get("outcome").asText());
        // The durable result that replays return keeps the result's provenance.
        assertEquals("UNTRUSTED_UPSTREAM", finished.get("result").get("result").get("provenance").asText());
        var stored = node(store.<String>transaction(tenant, false, tx -> tx.packages().relayed("inv-1").document()));
        assertEquals("UNTRUSTED_UPSTREAM", stored.get("status").get("result").get("result").get("provenance").asText());
        assertEquals(400, assertThrows(Failure.class, () -> packages.result(tenant, tx -> hosts.get("host-2"), "inv-2", "{\"invocationId\":\"inv-1\",\"outcome\":\"COMPLETED\"}")).status());
    }

    @Test void poolDeploymentsFollowLiveDeviceGroupMembership() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("pools.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String digest = node(packages.upload(actor, zip(files()), "request-1").body()).get("packageDigest").asText();
        var hosts = new HashMap<String, EndpointDeviceRecord>();
        for (var id : List.of("host-1", "host-2", "host-3")) hosts.put(id, new EndpointDeviceRecord(id, "tenant", "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null));
        String registration = "{\"hostId\":\"%s\",\"poolId\":\"%s\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}";
        TestSupport.pool(store, codec, tenant, "pool", "host-1");
        TestSupport.pool(store, codec, tenant, "other", "host-2");

        // A host's first registration claiming a pool it is not an administered member of is refused: it is not a
        // registered Tool Host, so it gets no assignment, package or permit.
        assertEquals(403, assertThrows(Failure.class, () -> packages.register(tenant, tx -> hosts.get("host-3"), registration.formatted("host-3", "pool"), "register-3")).status());
        assertEquals(403, assertThrows(Failure.class, () -> packages.assignments(tenant, tx -> hosts.get("host-3"))).status());
        packages.register(tenant, tx -> hosts.get("host-1"), registration.formatted("host-1", "pool"), "register-1");
        packages.register(tenant, tx -> hosts.get("host-2"), registration.formatted("host-2", "other"), "register-2");

        // Hosts or pools, never both; and only Device Groups that exist.
        String body = "{\"packageDigest\":\"" + digest + "\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"},%s}";
        assertEquals(400, assertThrows(Failure.class, () -> packages.deploy(actor, body.formatted("\"hostIds\":[\"host-1\"],\"deviceGroupIds\":[\"pool\"]"), "deploy-1")).status());
        assertEquals(400, assertThrows(Failure.class, () -> packages.deploy(actor, body.formatted("\"deviceGroupIds\":[\"nowhere\"]"), "deploy-2")).status());
        var deployed = node(packages.deploy(actor, body.formatted("\"deviceGroupIds\":[\"pool\"]"), "deploy-3").body());
        assertEquals("pool", deployed.get("deviceGroupIds").get(0).asText());
        assertFalse(deployed.has("hostIds"));

        // Only members of the pool are assigned the tool; a host of another group of the same tenant is not.
        assertEquals(1, node(packages.assignments(tenant, tx -> hosts.get("host-1")).body()).get("tools").size());
        assertEquals(0, node(packages.assignments(tenant, tx -> hosts.get("host-2")).body()).get("tools").size());
        assertEquals(404, assertThrows(Failure.class, () -> packages.content(tenant, tx -> hosts.get("host-2"), digest)).status());

        // Membership is read live: a replacement host added to the pool gets the deployment, one removed loses it.
        TestSupport.pool(store, codec, tenant, "pool", "host-2");
        assertEquals(1, node(packages.assignments(tenant, tx -> hosts.get("host-2")).body()).get("tools").size());
        assertEquals(0, node(packages.assignments(tenant, tx -> hosts.get("host-1")).body()).get("tools").size());
        assertEquals(404, assertThrows(Failure.class, () -> packages.content(tenant, tx -> hosts.get("host-1"), digest)).status());
        // Permit issuance uses the same live check, so a placement forged onto the removed host is refused there.
        var deployment = node(store.<String>transaction(tenant, false, tx -> tx.packages().deployment("acme.orders")));
        assertFalse(store.<Boolean>transaction(tenant, false, tx -> ServerPackages.targets(tx.load(), codec, deployment, "host-1")));
        assertTrue(store.<Boolean>transaction(tenant, false, tx -> ServerPackages.targets(tx.load(), codec, deployment, "host-2")));
        // A host whose pool membership was removed cannot register again under that pool.
        assertEquals(403, assertThrows(Failure.class, () -> packages.register(tenant, tx -> hosts.get("host-1"), registration.formatted("host-1", "pool"), "register-1b")).status());
    }

    /** The package of files() with a second SERVER tool, items.list, beside items.get. */
    static Map<String, byte[]> twoServers() throws Exception {
        var files = new TreeMap<>(files()); files.remove("CHECKSUMS.sha256");
        var manifest = (ObjectNode) JSON.readTree(files.get("toolgate-package.json"));
        var second = (ObjectNode) manifest.get("tools").get(0).get("descriptor").deepCopy();
        second.put("toolId", "acme.orders/items.list").put("mcpName", "orders.items.list");
        byte[] canonical = Canonical.bytes(second);
        var tool = ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("tools")).addObject();
        tool.set("descriptor", second); tool.put("toolDescriptorDigest", Canonical.sha256(canonical)).put("runtimeId", "jvm"); tool.putArray("artifactIds").add("app");
        var set = JSON.createArrayNode();
        for (var id : List.of("acme.orders/items.get", "acme.orders/items.list")) for (var t : manifest.get("tools")) if (t.get("descriptor").get("toolId").asText().equals(id))
            set.addObject().put("toolId", id).put("toolDescriptorDigest", t.get("toolDescriptorDigest").asText());
        manifest.put("descriptorSetDigest", Canonical.digest(set)).put("version", "0.2.0");
        files.put("tools/items.list.json", canonical); files.put("toolgate-package.json", JSON.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var e : files.entrySet()) checksums.append(Canonical.sha256(e.getValue()).substring(7)).append("  ").append(e.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test void aRedeployWithoutAToolRetiresIt() throws Exception {
        var codec = new ContractCodec(); var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("retire.sqlite"))), codec);
        var tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("tenant"); var actor = TestSupport.seed(store, codec, tenant, "root");
        var packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        String both = node(packages.upload(actor, zip(twoServers()), "upload-1").body()).get("packageDigest").asText();
        String one = node(packages.upload(actor, zip(files()), "upload-2").body()).get("packageDigest").asText();
        String body = "{\"packageDigest\":\"%s\",\"globals\":{\"ItemsApiHost\":\"items.example.com\"}}";
        packages.deploy(actor, body.formatted(both), "deploy-1");
        // Both enabled by a reviewed change.
        store.transaction(tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            for (var id : List.of("acme.orders/items.get", "acme.orders/items.list")) {
                var tool = entries.get(Ids.Kind.TOOL.id(id)); var document = (ObjectNode) codec.value(tool.document());
                entries.put(tool.id(), codec.entry(Ids.Kind.TOOL, codec.json(document.put("enabled", true).put("revision", document.get("revision").asLong() + 1))));
            }
            tx.save(before, new io.ololabs.toolgate.control.domain.Directory(before.revision() + 1, entries)); return null;
        });
        long epoch = store.transaction(tenant, false, tx -> tx.enterprise().authorizationEpoch());
        var deployed = node(packages.deploy(actor, body.formatted(one), "deploy-2").body());
        assertEquals("[\"acme.orders/items.get\"]", deployed.get("toolIds").toString());
        var entries = store.transaction(tenant, false, tx -> tx.load().entries());
        assertTrue(entries.get(Ids.Kind.TOOL.id("acme.orders/items.get")).enabled());
        assertFalse(entries.get(Ids.Kind.TOOL.id("acme.orders/items.list")).enabled());
        assertTrue(store.transaction(tenant, false, tx -> tx.enterprise().authorizationEpoch()) > epoch);
    }
}
