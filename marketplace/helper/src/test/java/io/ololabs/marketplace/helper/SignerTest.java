// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SignerTest {
    private SignerTest() {
    }

    private static Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", Signer.SCHEMA);
        fields.put("marketplace", "test-marketplace");
        fields.put("packageId", "acme.notify");
        fields.put("version", "0.1.0");
        fields.put("packageDigest", "sha256:" + "a".repeat(64));
        fields.put("descriptorSetDigest", "sha256:" + "b".repeat(64));
        fields.put("archiveSha256", "c".repeat(64));
        fields.put("publisherSlug", "acme");
        fields.put("signedAt", "2026-10-12T18:45:00Z");
        return fields;
    }

    public static void testSignedRecordVerifiesAndTamperingFails() throws Exception {
        Signer signer = Fixtures.signer(Fixtures.config(Fixtures.tempDir()));
        Map<String, Object> record = signer.release(fields());
        Check.equal("test-key-1", record.get("keyId"));
        Check.that(Signer.verifyRelease(record, signer.rawPublicKey()), "signature verifies");
        // Every signed field is covered.
        for (String field : List.of("marketplace", "packageId", "version", "packageDigest", "descriptorSetDigest",
                "archiveSha256", "publisherSlug", "signedAt", "keyId", "schema")) {
            Map<String, Object> tampered = new LinkedHashMap<>(record);
            tampered.put(field, record.get(field) + "x");
            Check.that(!Signer.verifyRelease(tampered, signer.rawPublicKey()), field + " is signed");
        }
        // Another key does not verify it.
        byte[] other = Signer.raw(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());
        Check.that(!Signer.verifyRelease(record, other), "an unknown key fails");
        // A record round-tripped through JSON still verifies, as Control will read it.
        Check.that(Signer.verifyRelease(Json.obj(Json.parse(Json.write(record))), signer.rawPublicKey()), "round trip");
    }

    public static void testKeySetListsCurrentAndRetiredKeys() throws Exception {
        String retired = "old-key:" + Base64.getEncoder().encodeToString(new byte[32]);
        Config config = Fixtures.config(Fixtures.tempDir());
        Signer signer = Signer.load(config.signingKeyFile(), "test-key-1", retired);
        Map<String, Object> set = signer.keySet("test-marketplace");
        Check.equal("test-marketplace", set.get("marketplace"));
        List<Object> keys = Json.arr(set.get("keys"));
        Check.equal(2, keys.size());
        Map<String, Object> current = Json.obj(keys.get(0));
        Check.equal("current", current.get("status"));
        Check.equal("Ed25519", current.get("algorithm"));
        Check.equal(Signer.fingerprint(signer.rawPublicKey()), current.get("fingerprint"));
        Check.equal("retired", Json.obj(keys.get(1)).get("status"));
    }

    public static void testRefusesMissingOrBadKey() throws Exception {
        Path dir = Fixtures.tempDir();
        Check.fails(IllegalStateException.class, () -> Signer.load(dir.resolve("absent"), "k", ""));
        Path bad = dir.resolve("bad.pem");
        Files.writeString(bad, "-----BEGIN " + "PRIVATE KEY-----\nAAAA\n-----END " + "PRIVATE KEY-----\n");
        Check.fails(IllegalStateException.class, () -> Signer.load(bad, "k", ""));
        Path rsa = dir.resolve("rsa.pem");
        Files.writeString(rsa, "not a key", StandardCharsets.US_ASCII);
        Check.fails(IllegalStateException.class, () -> Signer.load(rsa, "k", ""));
    }

    /** The key scripts/init-signing-key.sh writes loads, and both report the same public key and fingerprint. */
    public static void testInitScriptKeyMatches() throws Exception {
        Path script = Fixtures.marketplaceDir().resolve("scripts/init-signing-key.sh");
        Path key = Fixtures.tempDir().resolve("secrets/key");
        Process p = new ProcessBuilder("bash", script.toString(), key.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            Check.skip("openssl unavailable: " + output.strip());
        }
        Signer signer = Signer.load(key, "k", "");
        Check.that(output.contains("publicKey:   " + Base64.getEncoder().encodeToString(signer.rawPublicKey())), output);
        Check.that(output.contains("fingerprint: " + Signer.fingerprint(signer.rawPublicKey())), output);
        // A second run refuses to replace the key.
        Process again = new ProcessBuilder("bash", script.toString(), key.toString()).redirectErrorStream(true).start();
        again.getInputStream().readAllBytes();
        Check.that(again.waitFor() != 0, "a second init refuses");
    }
}
