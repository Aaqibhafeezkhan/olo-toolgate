// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Design §12: malformed packages are rejected, and inspection extracts the expected fields. */
final class ValidationTest {
    private ValidationTest() {
    }

    private static Map<String, Object> inspect(byte[] bytes) throws Exception {
        return Fixtures.inspector(Fixtures.config(Fixtures.tempDir())).inspect(bytes, null, null);
    }

    private static Map<String, Object> inspect(Config config, byte[] bytes) {
        return Fixtures.inspector(config).inspect(bytes, null, null);
    }

    private static void rejected(Map<String, Object> result, String code) {
        Check.equal(false, result.get("valid"));
        List<Object> errors = Json.arr(result.get("errors"));
        Check.that(!errors.isEmpty(), "a rejection has a reason");
        Check.equal(code, Json.obj(errors.get(0)).get("code"));
    }

    public static void testValidPackageExtractsTechnicalFields() throws Exception {
        byte[] bytes = Fixtures.valid();
        Map<String, Object> result = inspect(bytes);
        Check.equal(true, result.get("valid"));
        Check.equal("acme.notify", result.get("packageId"));
        Check.equal("0.1.0", result.get("version"));
        Check.equal("java", result.get("runtime"));
        Check.equal(Fixtures.sha256(bytes), result.get("archiveSha256"));
        Check.equal("sha256:" + Fixtures.sha256(Fixtures.unzip(bytes).get("CHECKSUMS.sha256")), result.get("packageDigest"));
        Check.that(((String) result.get("descriptorSetDigest")).matches("sha256:[0-9a-f]{64}"), "descriptorSetDigest");
        Check.equal("none", result.get("apiChange"));
        Check.equal(null, result.get("requiredBump"));
        Map<String, Object> tool = Json.obj(Json.arr(result.get("tools")).get(0));
        Check.equal("notify.send", tool.get("name"));
        Check.equal("Send a notification", tool.get("title"));
        Check.equal("NON_IDEMPOTENT", tool.get("effect"));
        // The real settings of examples.notify (design §6).
        List<String> settings = new ArrayList<>();
        for (Object s : Json.arr(result.get("requiredSettings"))) {
            Map<String, Object> m = Json.obj(s);
            settings.add(m.get("kind") + ":" + m.get("name") + ":" + m.get("type") + ":" + m.get("default"));
        }
        Check.equal(List.of("destination:WebhookHost:null:null", "secret:WebhookApiKey:STRING:null",
                "global:AllowedChannels:STRING:ops-alerts", "global:Source:STRING:toolgate"), settings);
        Map<String, Object> compatibility = Json.obj(result.get("compatibility"));
        Check.equal("1.0.0", compatibility.get("contractsVersion"));
        Check.equal(null, compatibility.get("minimumToolHostVersion"));
        Check.equal(Map.of("min", 2L, "max", 2L), compatibility.get("protocol"));
        Check.equal(1L, compatibility.get("descriptorGeneration"));
        Check.equal(List.of(Map.of("language", "java", "version", "17")), compatibility.get("runtimes"));
        Map<String, Object> listing = Json.obj(result.get("listing"));
        Check.equal("Send a notification", listing.get("title"));
        Check.equal(List.of("notify"), listing.get("tags"));
    }

    public static void testInspectNeverSigns() throws Exception {
        String json = Json.write(inspect(Fixtures.valid()));
        Check.that(!json.contains("signature") && !json.contains("\"release\"") && !json.contains("keyId"),
                "inspect returns no signature: " + json);
    }

    public static void testNotAZip() throws Exception {
        rejected(inspect("hello".getBytes(StandardCharsets.UTF_8)), "ARCHIVE");
    }

    public static void testChecksumError() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        files.put("artifacts/tool.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        rejected(inspect(Fixtures.zip(Fixtures.entries(files))), "CHECKSUM");
    }

    public static void testRewrittenArchiveStillValidates() throws Exception {
        // Guards the malformed cases: our zip writer alone does not make a package invalid.
        Map<String, Object> result = inspect(Fixtures.zip(Fixtures.entries(Fixtures.unzip(Fixtures.valid()))));
        Check.equal(true, result.get("valid"));
    }

    public static void testTraversalEntry() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        List<Fixtures.Entry> entries = new ArrayList<>(Fixtures.entries(files));
        entries.add(new Fixtures.Entry("../evil.sh", "x".getBytes(StandardCharsets.UTF_8)));
        rejected(inspect(Fixtures.zip(entries)), "ARCHIVE_PATH");
        entries.set(entries.size() - 1, new Fixtures.Entry("/etc/evil", "x".getBytes(StandardCharsets.UTF_8)));
        rejected(inspect(Fixtures.zip(entries)), "ARCHIVE_PATH");
    }

    public static void testSymlinkEntry() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        List<Fixtures.Entry> entries = new ArrayList<>(Fixtures.entries(files));
        entries.add(new Fixtures.Entry("link", "/etc/passwd".getBytes(StandardCharsets.UTF_8), 0120777, false));
        Check.equal(false, inspect(Fixtures.zip(entries)).get("valid"));
    }

    public static void testDecompressionRatioLimit() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        List<Fixtures.Entry> entries = new ArrayList<>(Fixtures.entries(files));
        entries.add(new Fixtures.Entry("sbom/bomb.json", new byte[4 * 1024 * 1024], 0100644, true));
        rejected(inspect(Fixtures.zip(entries)), "SIZE");
    }

    public static void testMarketplaceExpandedSizeLimit() throws Exception {
        byte[] big = Fixtures.pack(m -> { }, new byte[3 * 1024 * 1024]);
        Config config = Fixtures.config(Fixtures.tempDir(), 50L * 1024 * 1024, 2L * 1024 * 1024);
        rejected(inspect(config, big), "SIZE");
        Config small = Fixtures.config(Fixtures.tempDir(), 1024 * 1024, 200L * 1024 * 1024);
        rejected(inspect(small, big), "SIZE");
    }

    public static void testJavaToolNeedsExactlyOneJar() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        Map<String, Object> manifest = Json.obj(Json.parse(files.get("toolgate-package.json")));
        byte[] second = "second jar".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifactDigest", "sha256:" + Fixtures.sha256(second));
        artifact.put("artifactId", "extra");
        artifact.put("mediaType", "application/java-archive");
        artifact.put("path", "artifacts/extra.jar");
        artifact.put("runtimeId", "jvm");
        artifact.put("sizeBytes", (long) second.length);
        Json.arr(manifest.get("artifacts")).add(artifact);
        Json.arr(Json.obj(Json.arr(manifest.get("tools")).get(0)).get("artifactIds")).add("extra");
        files.put("toolgate-package.json", Json.write(manifest).getBytes(StandardCharsets.UTF_8));
        files.put("artifacts/extra.jar", second);
        files.put("CHECKSUMS.sha256", Fixtures.checksums(files));
        rejected(inspect(Fixtures.zip(Fixtures.entries(files))), "RUNTIME");
    }

    public static void testMissingManifest() throws Exception {
        Map<String, byte[]> files = Fixtures.unzip(Fixtures.valid());
        files.remove("toolgate-package.json");
        files.put("CHECKSUMS.sha256", Fixtures.checksums(files));
        rejected(inspect(Fixtures.zip(Fixtures.entries(files))), "MANIFEST");
    }

    public static void testOnlyJavaRuntimes() throws Exception {
        byte[] python = Fixtures.pack(m -> Json.obj(Json.arr(m.get("runtimes")).get(0)).put("language", "python"));
        rejected(inspect(python), "RUNTIME");
    }

    public static void testDirectoryNeverReachesTheCli() throws Exception {
        Config config = Fixtures.config(Fixtures.tempDir());
        Path dir = Fixtures.tempDir();
        Files.writeString(dir.resolve("build.gradle.kts"), "throw GradleException(\"must not run\")");
        Check.fails(IllegalArgumentException.class, () -> new CliValidator(config).run(dir));
        Path link = dir.resolve("link.tgpkg");
        Files.createSymbolicLink(link, dir);
        Check.fails(IllegalArgumentException.class, () -> new CliValidator(config).run(link));
    }

    public static void testSemVerAgainstPreviousVersion() throws Exception {
        Config config = Fixtures.config(Fixtures.tempDir());
        Inspector inspector = Fixtures.inspector(config);
        byte[] v010 = Fixtures.valid();
        // A new optional input in 0.1.1 is a compatible change; a patch is enough before 1.0.
        byte[] v011 = Fixtures.pack(m -> {
            m.put("version", "0.1.1");
            Json.obj(Json.obj(Fixtures.descriptor(m).get("inputSchema")).get("properties"))
                    .put("tag", Json.parse("{\"type\":\"string\"}"));
        });
        Map<String, Object> ok = inspector.inspect(v011, v010, "0.1.0");
        Check.equal(true, ok.get("valid"));
        Check.equal("compatible", ok.get("apiChange"));
        Check.equal("patch", ok.get("requiredBump"));
        // A new required input is breaking: 0.1.1 is too small, 0.2.0 is enough.
        java.util.function.Consumer<Map<String, Object>> breaking = m -> {
            Map<String, Object> input = Json.obj(Fixtures.descriptor(m).get("inputSchema"));
            Json.obj(input.get("properties")).put("tag", Json.parse("{\"type\":\"string\"}"));
            Json.arr(input.get("required")).add("tag");
        };
        byte[] tooSmall = Fixtures.pack(breaking.andThen(m -> m.put("version", "0.1.1")));
        Map<String, Object> refused = inspector.inspect(tooSmall, v010, "0.1.0");
        rejected(refused, "SEMVER");
        Check.equal("breaking", refused.get("apiChange"));
        Check.equal("minor", refused.get("requiredBump"));
        byte[] enough = Fixtures.pack(breaking.andThen(m -> m.put("version", "0.2.0")));
        Check.equal(true, inspector.inspect(enough, v010, "0.1.0").get("valid"));
        // Not newer than the previous version.
        rejected(inspector.inspect(v010, v010, "0.1.0"), "SEMVER");
    }
}
