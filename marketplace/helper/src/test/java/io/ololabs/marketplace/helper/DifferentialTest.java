// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import io.ololabs.toolgate.packagearchive.PackageArchive;
import io.ololabs.toolgate.packagearchive.PackageRejected;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * PR 3 step 3: the shared library against the unchanged {@code toolgate validate}, over ToolGate's package fixtures and,
 * when MARKETPLACE_SEED_DIR is set, every seed package. Where both accept, the digests must match. The library may
 * accept what the CLI rejects only for the cases in {@link CliValidator#REJECT_ONLY}; the CLI may never accept what
 * the library rejects.
 */
final class DifferentialTest {
    private DifferentialTest() {
    }

    public static void testLibraryAndCliAgreeExceptTheListedRejectOnlyCases() throws Exception {
        String toolgateDir = System.getenv("TOOLGATE_DIR");
        if (toolgateDir == null || toolgateDir.isEmpty()) {
            Check.skip("TOOLGATE_DIR is not set");
        }
        Config config = Fixtures.config(Fixtures.tempDir());
        if (!Files.isExecutable(config.toolgateCli())) {
            Check.skip("toolgate CLI not built at " + config.toolgateCli());
        }
        CliValidator cli = new CliValidator(config);
        Map<String, Path> fixtures = new TreeMap<>();
        List<Path> dirs = new ArrayList<>(List.of(Path.of(toolgateDir, "tests/fixtures/packages/cases"),
                Path.of(toolgateDir, "tool-sdk/conformance/fixtures/packages")));
        String seed = System.getenv("MARKETPLACE_SEED_DIR");
        if (seed != null && !seed.isEmpty() && Files.isDirectory(Path.of(seed, "packages"))) {
            dirs.add(Path.of(seed, "packages"));
        }
        for (Path dir : dirs) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".tgpkg"))
                        .forEach(p -> fixtures.put(dir.getFileName() + "/" + p.getFileName().toString().replace(".tgpkg", ""), p));
            }
        }
        Check.that(fixtures.size() > 70, "found the package fixtures: " + fixtures.size());
        var rejectOnly = new TreeSet<String>();
        var problems = new ArrayList<String>();
        int accepted = 0;
        for (var fixture : fixtures.entrySet()) {
            byte[] bytes = Files.readAllBytes(fixture.getValue());
            PackageArchive.Verified library = null;
            String libraryReason = null;
            try {
                library = PackageArchive.validate(bytes);
            } catch (PackageRejected e) {
                libraryReason = e.getMessage();
            }
            CliValidator.Result result = cli.validate(bytes);
            String name = fixture.getKey();
            if (library != null && result.valid()) {
                accepted++;
                if (!library.packageDigest().equals(result.packageDigest()) || !library.descriptorSetDigest().equals(result.descriptorSetDigest())) {
                    problems.add(name + ": digests differ");
                }
            } else if (library != null) {
                rejectOnly.add(name.substring(name.indexOf('/') + 1));
                System.out.println("  reject-only " + name + ": " + result.message());
            } else if (result.valid()) {
                problems.add(name + ": the CLI accepts what the library rejects (" + libraryReason + ")");
            }
        }
        Check.that(problems.isEmpty(), String.join("; ", problems));
        Check.equal(new TreeSet<>(CliValidator.REJECT_ONLY.keySet()), rejectOnly);
        Check.that(accepted >= 7, "valid fixtures are accepted by both: " + accepted);
        System.out.println("  differential: " + fixtures.size() + " packages, " + accepted + " accepted by both, "
                + rejectOnly.size() + " rejected only by the CLI");
    }
}
