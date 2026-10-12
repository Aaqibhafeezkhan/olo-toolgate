// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** The HTTP contract of api.md §3, including every /sign refusal. */
final class ServerTest {
    private ServerTest() {
    }

    private static Map<String, Object> signBody(String ref, Object digest, String slug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileRef", ref);
        body.put("packageDigest", digest);
        body.put("authorizedPublisherSlug", slug);
        return body;
    }

    public static void testHealthNeedsNoTokenButEverythingElseDoes() throws Exception {
        try (HelperClient helper = new HelperClient(Fixtures.tempDir())) {
            Check.equal(200, helper.get("/health", null).status());
            Check.equal(401, helper.get("/keys", null).status());
            Check.equal(401, helper.get("/keys", "wrong-token-0123456789").status());
            Check.equal(401, helper.post("/inspect", Map.of("fileRef", "file:x"), null).status());
            Check.equal(401, helper.post("/sign", signBody("file:x", "sha256:" + "0".repeat(64), "acme"), "wrong").status());
            Check.equal(404, helper.get("/nothing", helper.config.token()).status());
        }
    }

    public static void testInspectThenSignAndVerifyWithPublishedKey() throws Exception {
        Path root = Fixtures.tempDir();
        byte[] bytes = Fixtures.valid();
        Files.write(root.resolve("acme.tgpkg"), bytes);
        try (HelperClient helper = new HelperClient(root)) {
            HelperClient.Response inspected = helper.post("/inspect", Map.of("fileRef", "file:acme.tgpkg"));
            Check.equal(200, inspected.status());
            Check.equal(true, inspected.body().get("valid"));
            Check.that(!Json.write(inspected.body()).contains("signature"), "inspect never signs");
            Object digest = inspected.body().get("packageDigest");

            HelperClient.Response signed = helper.post("/sign", signBody("file:acme.tgpkg", digest, "acme"));
            Check.equal(200, signed.status());
            Map<String, Object> record = signed.body();
            Check.equal("toolgate.marketplace.release/1", record.get("schema"));
            Check.equal("test-marketplace", record.get("marketplace"));
            Check.equal("acme.notify", record.get("packageId"));
            Check.equal("0.1.0", record.get("version"));
            Check.equal(digest, record.get("packageDigest"));
            Check.equal(inspected.body().get("descriptorSetDigest"), record.get("descriptorSetDigest"));
            Check.equal(Fixtures.sha256(bytes), record.get("archiveSha256"));
            Check.equal("acme", record.get("publisherSlug"));
            Check.that(((String) record.get("signedAt")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"), "signedAt");

            HelperClient.Response keys = helper.get("/keys", helper.config.token());
            Check.equal("test-revision", keys.body().get("toolgateRevision"));
            Map<String, Object> key = Json.obj(Json.arr(keys.body().get("keys")).get(0));
            Check.equal(record.get("keyId"), key.get("keyId"));
            byte[] publicKey = Base64.getDecoder().decode((String) key.get("publicKey"));
            Check.that(Signer.verifyRelease(record, publicKey), "verifies with the published key");
            Map<String, Object> tampered = new LinkedHashMap<>(record);
            tampered.put("packageDigest", "sha256:" + "0".repeat(64));
            Check.that(!Signer.verifyRelease(tampered, publicKey), "a tampered record fails");
        }
    }

    public static void testSignRefusals() throws Exception {
        Path root = Fixtures.tempDir();
        Files.write(root.resolve("acme.tgpkg"), Fixtures.valid());
        Files.write(root.resolve("toolgate.tgpkg"), Fixtures.pack(Fixtures.id("toolgate.notify")));
        Files.write(root.resolve("examples.tgpkg"), Fixtures.pack(Fixtures.id("examples.notify")));
        Files.write(root.resolve("broken.tgpkg"), "not a package".getBytes());
        try (HelperClient helper = new HelperClient(root)) {
            Object digest = helper.post("/inspect", Map.of("fileRef", "file:acme.tgpkg")).body().get("packageDigest");
            Check.equal("PREFIX_MISMATCH", helper.post("/sign", signBody("file:acme.tgpkg", digest, "evil")).code());
            Check.equal(403, helper.post("/sign", signBody("file:acme.tgpkg", digest, "evil")).status());
            Check.equal("DIGEST_MISMATCH",
                    helper.post("/sign", signBody("file:acme.tgpkg", "sha256:" + "0".repeat(64), "acme")).code());
            Check.equal("INVALID_PACKAGE",
                    helper.post("/sign", signBody("file:broken.tgpkg", "sha256:" + "0".repeat(64), "acme")).code());
            Check.equal("INVALID_REQUEST", helper.post("/sign", signBody("file:acme.tgpkg", digest, "Not A Slug")).code());
            Check.equal("INVALID_REQUEST", helper.post("/sign", signBody("file:acme.tgpkg", null, "acme")).code());

            Object reserved = helper.post("/inspect", Map.of("fileRef", "file:toolgate.tgpkg")).body().get("packageDigest");
            HelperClient.Response refused = helper.post("/sign", signBody("file:toolgate.tgpkg", reserved, "toolgate"));
            Check.equal(403, refused.status());
            Check.equal("RESERVED_PREFIX", refused.code());

            // examples is reserved too, but the system publisher "examples" may sign its own packages.
            Object examples = helper.post("/inspect", Map.of("fileRef", "file:examples.tgpkg")).body().get("packageDigest");
            Check.equal(200, helper.post("/sign", signBody("file:examples.tgpkg", examples, "examples")).status());
        }
    }

    public static void testInspectRejectsBadRequests() throws Exception {
        try (HelperClient helper = new HelperClient(Fixtures.tempDir())) {
            Check.equal(404, helper.post("/inspect", Map.of("fileRef", "file:absent.tgpkg")).status());
            Check.equal(400, helper.post("/inspect", Map.of("fileRef", "file:../../etc/passwd")).status());
            Check.equal(400, helper.post("/inspect", Map.of()).status());
        }
    }
}
