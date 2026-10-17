// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.contracts.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Import from the OLO Orgs Marketplace (marketplace design §8.3): a negative case for every verification step, a
 *  non-allowlisted origin and a redirect, an oversize package, the incompatibilities of §6.2 A, and the audit record. */
final class MarketplaceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "https://marketplace.example.test", ID = "olo-labs-marketplace";
    private static final String KEY_ID = "olo-marketplace-2026-1";
    private static final String BASE = "/api/toolgate/v1/tools/acme.orders/0.1.0";
    @TempDir Path directory;

    private KeyPair key, other;
    private ContractCodec codec; private PostgresStore store; private Ids.TenantId tenant; private DirectoryService.Actor actor;
    private ServerPackages packages; private Fake source; private byte[] bytes; private Map<String, byte[]> files;

    /** Serves fixed responses; enforces the size limit as the HTTPS source does. */
    static final class Fake implements MarketplaceSource {
        final Map<String, Response> responses = new HashMap<>(); final List<String> requested = new ArrayList<>();
        public Response get(String path, String accept, long maxBytes) {
            requested.add(path);
            var response = responses.get(path);
            if (response == null) return new Response(404, "application/json", "{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8));
            if (response.body().length > maxBytes) throw new Failure(ErrorCode.VALIDATION, 413, "Marketplace response too large");
            return response;
        }
        void json(String path, Object value) { responses.put(path, new Response(200, "application/json", json(value))); }
        static byte[] json(Object value) { try { return JSON.writeValueAsBytes(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    }

    @BeforeEach void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519"); key = generator.generateKeyPair(); other = generator.generateKeyPair();
        codec = new ContractCodec(); store = new PostgresStore(SqliteState.open(directory.resolve("marketplace.sqlite")), codec);
        tenant = new Ids.TenantId("tenant"); actor = TestSupport.seed(store, codec, tenant, "root");
        packages = new ServerPackages(store, codec, null, java.time.Clock.systemUTC());
        files = ServerPackagesTest.files(); bytes = ServerPackagesTest.zip(files);
        source = new Fake();
        publish(bytes, release -> {}, detail -> {});
    }

    private static byte[] raw(KeyPair pair) { var encoded = pair.getPublic().getEncoded(); return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length); }
    private Marketplace marketplace() {
        var settings = Marketplace.Settings.parse(ID, ORIGIN, KEY_ID + ":" + Base64.getEncoder().encodeToString(raw(key)));
        return new Marketplace(settings, source, packages, store, codec, java.time.Clock.systemUTC());
    }

    private ObjectNode signed(ObjectNode release, KeyPair signer) throws Exception {
        var signature = Signature.getInstance("Ed25519"); signature.initSign(signer.getPrivate()); signature.update(Canonical.jcs(release));
        return release.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
    }

    /** Serves the detail, the signed release record and the bytes, with changes applied before signing. */
    private void publish(byte[] data, Consumer<ObjectNode> release, Consumer<ObjectNode> detail) throws Exception {
        publish(data, release, detail, key);
    }
    private void publish(byte[] data, Consumer<ObjectNode> release, Consumer<ObjectNode> detail, KeyPair signer) throws Exception {
        var verified = PackageArchive.validate(bytes, document -> (JsonNode) codec.value(document));
        var record = JSON.createObjectNode().put("schema", Marketplace.RELEASE_SCHEMA).put("marketplace", ID).put("packageId", "acme.orders").put("version", "0.1.0")
            .put("packageDigest", verified.packageDigest()).put("descriptorSetDigest", verified.descriptorSetDigest())
            .put("archiveSha256", sha256(data)).put("publisherSlug", "acme").put("signedAt", "2026-10-12T18:45:00Z").put("keyId", KEY_ID);
        release.accept(record);
        var body = JSON.createObjectNode().put("packageId", "acme.orders").put("version", "0.1.0").put("title", "Orders").put("delisted", false)
            .put("downloadUrl", ORIGIN + BASE + "/package").put("releaseUrl", ORIGIN + BASE + "/release").put("archiveSha256", sha256(data));
        body.putObject("publisher").put("slug", "acme").put("displayName", "Acme");
        body.set("compatibility", JSON.readTree("{\"contractsVersion\":\"" + io.ololabs.toolgate.contracts.ContractSet.VERSION + "\",\"minimumToolHostVersion\":null,\"protocol\":{\"min\":2,\"max\":2},\"descriptorGeneration\":1,\"runtimes\":[{\"language\":\"java\",\"version\":\"17\"}]}"));
        detail.accept(body);
        source.json(BASE, body);
        source.json(BASE + "/release", signed(record, signer));
        source.responses.put(BASE + "/package", new MarketplaceSource.Response(200, Marketplace.PACKAGE_MEDIA_TYPE, data));
    }

    private static String sha256(byte[] data) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
    private Store.Reply install() { return marketplace().install(actor, "{\"packageId\":\"acme.orders\",\"version\":\"0.1.0\"}", "request-1"); }
    private void rejectedAt(String step) {
        var failure = assertThrows(Failure.class, this::install);
        assertEquals(422, failure.status(), failure.getMessage());
        assertTrue(failure.getMessage().startsWith("Marketplace import rejected at step " + step + ":"), failure.getMessage());
        assertEquals(0, store.transaction(tenant, false, tx -> tx.packages().versions(10)).size(), "nothing is stored");
    }

    @Test void importsAVerifiedPackageThroughTheExistingUploadAndAuditsWhereItCameFrom() throws Exception {
        var reply = install();
        assertEquals(201, reply.status());
        var version = JSON.readTree(reply.body());
        assertEquals("acme.orders", version.get("packageId").asText());
        assertEquals("VERIFIED", version.get("state").asText());
        assertEquals(List.of(BASE, BASE + "/release", BASE + "/package"), source.requested);
        var audit = JSON.readTree(store.<String>transaction(tenant, false, tx -> tx.auditPage(0, 50))).get("items");
        var operations = new ArrayList<String>(); String target = null;
        for (var item : audit) { operations.add(item.get("operation").asText()); if (item.get("operation").asText().equals("PACKAGE_MARKETPLACE_IMPORT")) target = item.get("target").asText(); }
        assertTrue(operations.containsAll(List.of("PACKAGE_MARKETPLACE_IMPORT", "PACKAGE_UPLOAD")), operations.toString());
        assertEquals("marketplace:" + ID + "/acme.orders@0.1.0 publisher:acme key:" + KEY_ID, target);
        // Importing the same version again answers the stored version; nothing is deployed, enabled or granted.
        assertEquals(200, install().status());
        assertTrue(store.transaction(tenant, false, tx -> tx.packages().deployments()).isEmpty());
    }

    @Test void step1RefusesADelistedVersion() throws Exception {
        publish(bytes, r -> {}, d -> d.put("delisted", true).remove("downloadUrl")); rejectedAt("1");
        publish(bytes, r -> {}, d -> d.remove("delisted")); rejectedAt("1");
        publish(bytes, r -> {}, d -> {}); source.responses.put(BASE + "/package", new MarketplaceSource.Response(410, "application/json", "{}".getBytes(StandardCharsets.UTF_8))); rejectedAt("1");
    }

    @Test void step2RefusesAnUnknownReleaseSchema() throws Exception {
        publish(bytes, r -> r.put("schema", "toolgate.marketplace.release/2"), d -> {}); rejectedAt("2");
        publish(bytes, r -> r.remove("marketplace"), d -> {}); rejectedAt("2");
    }

    @Test void step3RefusesAnUnpinnedKeyOrABadSignature() throws Exception {
        publish(bytes, r -> r.put("keyId", "unknown-key"), d -> {}); rejectedAt("3");
        publish(bytes, r -> {}, d -> {}, other); rejectedAt("3");
        publish(bytes, r -> {}, d -> {});
        var tampered = (ObjectNode) JSON.readTree(source.responses.get(BASE + "/release").body()); tampered.put("signedAt", "2026-10-13T00:00:00Z");
        source.json(BASE + "/release", tampered); rejectedAt("3");
    }

    @Test void step4RefusesAnotherMarketplace() throws Exception { publish(bytes, r -> r.put("marketplace", "another-marketplace"), d -> {}); rejectedAt("4"); }

    @Test void step5RefusesAnotherPackageOrVersion() throws Exception {
        publish(bytes, r -> r.put("packageId", "acme.other"), d -> {}); rejectedAt("5");
        publish(bytes, r -> r.put("version", "0.2.0"), d -> {}); rejectedAt("5");
        publish(bytes, r -> {}, d -> d.put("version", "0.2.0")); rejectedAt("5");
    }

    @Test void step6RefusesAnotherPublisher() throws Exception {
        publish(bytes, r -> r.put("publisherSlug", "other"), d -> {}); rejectedAt("6");
        // Signed and listed as `other`, but the validated manifest's id prefix is `acme`.
        publish(bytes, r -> r.put("publisherSlug", "other"), d -> ((ObjectNode) d.get("publisher")).put("slug", "other")); rejectedAt("6");
    }

    @Test void step7RefusesBytesThatWereNotSigned() throws Exception {
        publish(bytes, r -> r.put("archiveSha256", "0".repeat(64)), d -> {}); rejectedAt("7");
        publish(bytes, r -> {}, d -> {});
        var swapped = ServerPackagesTest.zip(new TreeMap<>(files) {{ put("CHECKSUMS.sha256", get("CHECKSUMS.sha256")); }});
        swapped[swapped.length - 30] ^= 1;
        source.responses.put(BASE + "/package", new MarketplaceSource.Response(200, Marketplace.PACKAGE_MEDIA_TYPE, swapped)); rejectedAt("7");
    }

    @Test void step8RefusesAPackageThatFailsControlsOwnChecks() throws Exception {
        var tampered = new TreeMap<>(files); tampered.put("artifacts/app.jar", "other".getBytes(StandardCharsets.UTF_8));
        publish(ServerPackagesTest.zip(tampered), r -> {}, d -> {}); rejectedAt("8");
        publish(bytes, r -> r.put("packageDigest", "sha256:" + "0".repeat(64)), d -> {}); rejectedAt("8");
        publish(bytes, r -> r.put("descriptorSetDigest", "sha256:" + "0".repeat(64)), d -> {}); rejectedAt("8");
        // Signed and listed as 0.2.0, but the manifest says 0.1.0.
        var base = "/api/toolgate/v1/tools/acme.orders/0.2.0";
        publish(bytes, r -> r.put("version", "0.2.0"), d -> d.put("version", "0.2.0").put("downloadUrl", ORIGIN + base + "/package").put("releaseUrl", ORIGIN + base + "/release"));
        for (var suffix : new String[]{"", "/release", "/package"}) source.responses.put(base + suffix, source.responses.get(BASE + suffix));
        var failure = assertThrows(Failure.class, () -> marketplace().install(actor, "{\"packageId\":\"acme.orders\",\"version\":\"0.2.0\"}", "request-2"));
        assertTrue(failure.getMessage().startsWith("Marketplace import rejected at step 8:"), failure.getMessage());
    }

    @Test void refusesAnotherOriginARedirectAndAnOversizePackage() throws Exception {
        publish(bytes, r -> {}, d -> d.put("downloadUrl", "https://evil.example.test" + BASE + "/package")); rejectedAt("origin");
        publish(bytes, r -> {}, d -> d.put("releaseUrl", "http://marketplace.example.test" + BASE + "/release")); rejectedAt("origin");
        publish(bytes, r -> {}, d -> {});
        source.responses.put(BASE + "/package", new MarketplaceSource.Response(302, "", new byte[0])); rejectedAt("origin");
        publish(bytes, r -> {}, d -> {});
        source.responses.put(BASE + "/package", new MarketplaceSource.Response(200, Marketplace.PACKAGE_MEDIA_TYPE, new byte[PackageArchive.MAX_PACKAGE_BYTES + 1]));
        assertEquals(413, assertThrows(Failure.class, this::install).status());
        // Settings accept an HTTPS origin only.
        assertThrows(IllegalArgumentException.class, () -> Marketplace.Settings.parse(ID, "http://marketplace.example.test", KEY_ID + ":" + Base64.getEncoder().encodeToString(raw(key))));
        assertThrows(IllegalArgumentException.class, () -> Marketplace.Settings.parse(ID, ORIGIN + "/path", KEY_ID + ":" + Base64.getEncoder().encodeToString(raw(key))));
        assertThrows(IllegalArgumentException.class, () -> Marketplace.Settings.parse(ID, ORIGIN, KEY_ID + ":short"));
        assertThrows(IllegalArgumentException.class, () -> Marketplace.Settings.parse(ID, ORIGIN, ""));
    }

    @Test void onlyTheSuperAdminImportsAndADisabledMarketplaceAnswersNothing() {
        var reader = new DirectoryService.Actor(tenant, "b".repeat(64), true, false, "nobody");
        assertEquals(403, assertThrows(Failure.class, () -> marketplace().install(reader, "{\"packageId\":\"acme.orders\",\"version\":\"0.1.0\"}", "r")).status());
        assertTrue(source.requested.isEmpty(), "nothing is downloaded for someone who may not import");
        var disabled = new Marketplace(Marketplace.Settings.disabled(), source, packages, store, codec, java.time.Clock.systemUTC());
        assertEquals(404, assertThrows(Failure.class, () -> disabled.install(actor, "{\"packageId\":\"acme.orders\",\"version\":\"0.1.0\"}", "r")).status());
        assertEquals(404, assertThrows(Failure.class, () -> disabled.media("m_" + "0".repeat(32))).status());
        assertEquals(400, assertThrows(Failure.class, () -> marketplace().install(actor, "{\"packageId\":\"../x\",\"version\":\"0.1.0\"}", "r")).status());
    }

    @Test void marksEachCatalogVersionCompatibleOrNotAndOffersTheNewestCompatible() throws Exception {
        var versions = JSON.readTree("""
            {"packageId":"acme.orders","latestVersion":"0.3.0","versions":[
              {"version":"0.3.0","delisted":false,"compatibility":{"contractsVersion":"2.0.0","protocol":{"min":2,"max":2},"descriptorGeneration":1,"runtimes":[{"language":"java","version":"17"}]}},
              {"version":"0.2.0","delisted":true,"compatibility":{"contractsVersion":"@CONTRACTS@","protocol":{"min":2,"max":2},"descriptorGeneration":1,"runtimes":[{"language":"java","version":"17"}]}},
              {"version":"0.1.0","delisted":false,"compatibility":{"contractsVersion":"@CONTRACTS@","protocol":{"min":2,"max":2},"descriptorGeneration":1,"runtimes":[{"language":"java","version":"17"}]}}]}""".replace("@CONTRACTS@", io.ololabs.toolgate.contracts.ContractSet.VERSION));
        source.json("/api/toolgate/v1/tools/acme.orders", versions);
        var reply = JSON.readTree(marketplace().versions(actor, "acme.orders").body());
        assertEquals("0.1.0", reply.get("installVersion").asText());
        assertFalse(reply.get("versions").get(0).get("control").get("compatible").asBoolean());
        assertTrue(reply.get("versions").get(0).get("control").get("reasons").get(0).asText().contains("contracts 2.0.0"));
        assertTrue(reply.get("versions").get(2).get("control").get("compatible").asBoolean());
        var status = JSON.readTree(marketplace().status(actor).body());
        assertEquals(Marketplace.fingerprint(raw(key)), status.get("keys").get(0).get("fingerprint").asText());
    }

    @Test void theMediaProxyAnswersOnlyImagesOfTheConfiguredMarketplace() {
        var png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        source.responses.put("/api/toolgate/v1/media/m_" + "a".repeat(32), new MarketplaceSource.Response(200, "image/png", png));
        source.responses.put("/api/toolgate/v1/media/m_" + "b".repeat(32), new MarketplaceSource.Response(200, "image/png", "<svg/>".getBytes(StandardCharsets.UTF_8)));
        source.responses.put("/api/toolgate/v1/media/m_" + "c".repeat(32), new MarketplaceSource.Response(200, "image/svg+xml", png));
        assertArrayEquals(png, marketplace().media("m_" + "a".repeat(32)).body());
        assertEquals(503, assertThrows(Failure.class, () -> marketplace().media("m_" + "b".repeat(32))).status());
        assertEquals(503, assertThrows(Failure.class, () -> marketplace().media("m_" + "c".repeat(32))).status());
        assertEquals(404, assertThrows(Failure.class, () -> marketplace().media("../catalog")).status());
        source.responses.put("/api/toolgate/v1/media/m_" + "d".repeat(32), new MarketplaceSource.Response(200, "image/png", new byte[1024 * 1024 + 1]));
        assertEquals(413, assertThrows(Failure.class, () -> marketplace().media("m_" + "d".repeat(32))).status());
    }
}
