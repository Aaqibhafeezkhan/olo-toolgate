// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Implementation prompt §3.2: every package the seed stage produced goes through {@code /inspect} and {@code /sign}
 * as the system publisher {@code examples}, and the discovered set matches {@code seed/expected-packages.json}.
 * {@code knownMissing} ids are reported, not failed, unless {@code MARKETPLACE_SEED_STRICT=true}.
 */
final class SeedTest {
    private SeedTest() {
    }

    public static void testEverySeedPackageInspectsAndSigns() throws Exception {
        String seed = System.getenv("MARKETPLACE_SEED_DIR");
        if (seed == null || seed.isBlank()) {
            Check.skip("MARKETPLACE_SEED_DIR is not set; ci/run.sh builds the seed and sets it");
        }
        Path seedDir = Path.of(seed);
        Map<String, Object> expected = Json.obj(Json.parse(Files.readAllBytes(
                Fixtures.marketplaceDir().resolve("seed/expected-packages.json"))));
        TreeSet<String> required = new TreeSet<>();
        Json.arr(expected.get("required")).forEach(id -> required.add((String) id));
        Map<String, String> knownMissing = new LinkedHashMap<>();
        for (Object missing : Json.arr(expected.get("knownMissing"))) {
            knownMissing.put((String) Json.obj(missing).get("id"), (String) Json.obj(missing).get("folder"));
        }
        boolean strict = "true".equals(System.getenv("MARKETPLACE_SEED_STRICT"));

        List<String> problems = new ArrayList<>();
        TreeSet<String> published = new TreeSet<>();
        Map<String, Object> manifest = Json.obj(Json.parse(Files.readAllBytes(seedDir.resolve("seed-manifest.json"))));
        try (HelperClient helper = new HelperClient(seedDir)) {
            for (Object item : Json.arr(manifest.get("entries"))) {
                Map<String, Object> entry = Json.obj(item);
                String folder = (String) entry.get("folder");
                String file = Json.str(entry.get("package"));
                if (file == null) {
                    if (!knownMissing.containsValue(folder)) {
                        problems.add(folder + ": the build produced no package" + (entry.containsKey("error") ? " (" + entry.get("error") + ")" : ""));
                    }
                    continue;
                }
                String ref = "file:" + file;
                HelperClient.Response inspected = helper.post("/inspect", Map.of("fileRef", ref));
                if (inspected.status() != 200 || !Boolean.TRUE.equals(inspected.body().get("valid"))) {
                    problems.add(folder + ": /inspect refused it: " + Json.write(inspected.body()));
                    continue;
                }
                String id = (String) inspected.body().get("packageId");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("fileRef", ref);
                body.put("packageDigest", inspected.body().get("packageDigest"));
                body.put("authorizedPublisherSlug", "examples");
                HelperClient.Response signed = helper.post("/sign", body);
                byte[] key = Base64.getDecoder().decode((String) Json.obj(Json.arr(
                        helper.get("/keys", helper.config.token()).body().get("keys")).get(0)).get("publicKey"));
                if (signed.status() != 200 || !Signer.verifyRelease(signed.body(), key)) {
                    problems.add(folder + ": /sign refused it or the signature does not verify: " + Json.write(signed.body()));
                    continue;
                }
                published.add(id);
                System.out.println("  seed " + id + "@" + inspected.body().get("version") + " " + inspected.body().get("packageDigest"));
            }
        }
        for (String id : required) {
            if (!published.contains(id)) {
                problems.add(id + " is required by expected-packages.json but was not published");
            }
        }
        for (String id : published) {
            if (!required.contains(id) && !knownMissing.containsKey(id)) {
                problems.add(id + " was discovered but is not in expected-packages.json; add it");
            }
        }
        for (Map.Entry<String, String> missing : knownMissing.entrySet()) {
            String state = published.contains(missing.getKey()) ? "now publishes; move it to required" : "still missing";
            System.out.println("  knownMissing " + missing.getKey() + " (" + missing.getValue() + "): " + state);
            if (strict) {
                problems.add(missing.getKey() + " is in knownMissing and MARKETPLACE_SEED_STRICT=true");
            }
        }
        System.out.println("  seed: " + published.size() + " published, " + required.size() + " required, "
                + knownMissing.size() + " known missing");
        Check.that(problems.isEmpty(), "seed check failed:\n  " + String.join("\n  ", problems));
    }
}
