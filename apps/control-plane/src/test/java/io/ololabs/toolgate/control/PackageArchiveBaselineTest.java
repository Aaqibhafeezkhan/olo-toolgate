// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.PackageArchive;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

/** The regression baseline for Control's package rules: what upload decides for every fixture, with the digests of
 *  the ones it accepts. tests/fixtures/packages/baseline.json was recorded before PackageArchive moved into
 *  backend-libs/package-archive, and must not change without a reason. Set TOOLGATE_WRITE_PACKAGE_BASELINE=true to
 *  rewrite it. */
final class PackageArchiveBaselineTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static Path root() {
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
            if (Files.isRegularFile(dir.resolve("VERSION")) && Files.isDirectory(dir.resolve("tests/fixtures/packages"))) return dir;
        throw new IllegalStateException("cannot find the repository root");
    }

    /** Every fixture, keyed by a name relative to the repository root. */
    static TreeMap<String, byte[]> fixtures() throws Exception {
        var root = root(); var out = new TreeMap<String, byte[]>();
        for (var dir : new String[]{"tests/fixtures/packages/cases", "tool-sdk/conformance/fixtures/packages"})
            try (var files = Files.list(root.resolve(dir))) {
                for (var file : files.filter(p -> p.toString().endsWith(".tgpkg")).toList())
                    out.put(dir + "/" + file.getFileName(), Files.readAllBytes(file));
            }
        return out;
    }

    @Test
    void controlDecidesEveryFixtureAsRecorded() throws Exception {
        var codec = new ContractCodec();
        var actual = JSON.createObjectNode();
        actual.put("$comment", "Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0. Written by PackageArchiveBaselineTest.");
        var results = actual.putObject("fixtures");
        for (var fixture : fixtures().entrySet()) {
            ObjectNode result = results.putObject(fixture.getKey());
            try {
                var verified = PackageArchive.validate(fixture.getValue(), document -> (JsonNode) codec.value(document));
                result.put("result", "accept").put("packageId", verified.manifest().get("id").asText())
                    .put("packageDigest", verified.packageDigest()).put("descriptorSetDigest", verified.descriptorSetDigest())
                    .put("files", verified.files().size());
            } catch (Failure failure) {
                result.put("result", "reject").put("status", failure.status());
            } catch (RuntimeException error) {
                result.put("result", "error").put("exception", error.getClass().getSimpleName());
            }
        }
        var file = root().resolve("tests/fixtures/packages/baseline.json");
        if ("true".equals(System.getenv("TOOLGATE_WRITE_PACKAGE_BASELINE"))) Files.writeString(file, JSON.writeValueAsString(actual) + "\n");
        assertEquals(JSON.readTree(file.toFile()), actual, "Control's package decisions differ from tests/fixtures/packages/baseline.json");
    }
}
