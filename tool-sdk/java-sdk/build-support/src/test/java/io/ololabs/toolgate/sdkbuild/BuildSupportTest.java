// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdkbuild;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** Staging, checks and packing, over the tools this module's tests compile. Conformance: dev-server/build-plugins (shared core). */
class BuildSupportTest {
    /** The compiled test tools as a jar, as a build's jar task makes it. */
    static Path appJar() throws Exception {
        Path classes = Path.of(acme.stock.StockTools.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path jar = Files.createTempDirectory("toolgate-build").resolve("stock-1.0.0.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file);
                Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                String name = classes.relativize(p).toString().replace('\\', '/');
                if (name.startsWith("io/")) continue;
                out.putNextEntry(new JarEntry(name));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    static Path library(String name) throws IOException {
        Path lib = Files.createTempDirectory("toolgate-lib").resolve(name);
        Files.write(lib, ("fixture " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return lib;
    }

    @Test
    void stagingWritesTheSourceManifestAndBothArtifacts() throws Exception {
        Path out = Files.createTempDirectory("toolgate-staging");
        Staging.stage(appJar(), List.of(library("b.jar"), library("a.jar")), "1.2.0", out);
        JsonNode m = Staging.JSON.readTree(out.resolve("toolgate-package.json").toFile());
        assertEquals(2, m.path("schemaVersion").asInt());
        assertEquals("acme.stock", m.path("id").asText());
        assertEquals("1.2.0", m.path("version").asText());
        assertEquals("Acme", m.path("publisher").asText());
        assertEquals("JAVA", m.path("sdk").path("language").asText());
        assertFalse(m.path("compatibility").path("contractsVersion").asText().contains("$"), "versions are filled in at build time");
        JsonNode tool = m.path("tools").get(0);
        assertEquals("acme.stock/stock.level", tool.path("descriptor").path("toolId").asText());
        assertEquals(Staging.ENTRY_POINT, tool.path("entryPoint").asText());
        assertEquals("[\"app\",\"libs\"]", tool.path("artifactIds").toString());
        assertFalse(tool.has("toolDescriptorDigest"), "digests are the packer's");
        assertTrue(Files.isRegularFile(out.resolve("artifacts/app.jar")));
        try (ZipFile libs = new ZipFile(out.resolve("artifacts/libs.zip").toFile())) {
            List<String> names = new java.util.ArrayList<>();
            java.util.Collections.list(libs.entries()).forEach(e -> names.add(e.getName()));
            assertEquals(List.of("lib/a.jar", "lib/b.jar"), names);
        }
    }

    @Test
    void stagingIsDeterministicAndRefusesBadInput() throws Exception {
        Path jar = appJar();
        List<Path> libs = List.of(library("a.jar"));
        Path one = Staging.stage(jar, libs, "1.0.0", Files.createTempDirectory("toolgate-one"));
        Path two = Staging.stage(jar, libs, "1.0.0", Files.createTempDirectory("toolgate-two"));
        for (String f : List.of("toolgate-package.json", "artifacts/libs.zip")) {
            assertArrayEquals(Files.readAllBytes(one.resolve(f)), Files.readAllBytes(two.resolve(f)), f);
        }
        assertThrows(IllegalArgumentException.class, () -> Staging.stage(jar, libs, "1.0", Files.createTempDirectory("x")));
        assertThrows(IllegalArgumentException.class, () -> Staging.stage(jar, List.of(library("a.jar"), library("a.jar")), "1.0.0",
                Files.createTempDirectory("x")), "two libraries with one name");
        Path empty = Files.createTempDirectory("toolgate-empty").resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(empty))) {
            out.putNextEntry(new JarEntry("x.txt"));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Staging.stage(empty, libs, "1.0.0", Files.createTempDirectory("x")));
        assertTrue(e.getMessage().contains("descriptorGeneration"), e.getMessage());
    }

    @Test
    void checkRunsTheExamplesWithTheDevFileAsFixtures() throws Exception {
        Path project = Files.createTempDirectory("toolgate-project");
        Files.writeString(project.resolve("toolgate.dev.json"), "{\"globals\": {\"Warehouse\": \"north\"}}");
        Checks.Report report = Checks.run(getClass().getClassLoader(), project);
        assertEquals(2, report.ran().size(), report.toString());
        assertEquals(1, report.problems().size(), report.toString());
        assertTrue(report.problems().get(0).contains("expected error OUT_OF_STOCK but got UNKNOWN_SKU"), report.toString());
    }

    @Test
    void checkFailsWhenTheDevFileIsTracked() throws Exception {
        assumeTrue(git(Path.of("."), "--version"), "git is installed");
        Path project = Files.createTempDirectory("toolgate-repo");
        Files.writeString(project.resolve("toolgate.dev.json"), "{\"globals\": {\"Warehouse\": \"north\"}}");
        assertTrue(git(project, "init", "-q"));
        assertFalse(Checks.tracked(project, "toolgate.dev.json"));
        assertTrue(git(project, "add", "toolgate.dev.json"));
        assertTrue(Checks.tracked(project, "toolgate.dev.json"));
        assertTrue(Checks.run(getClass().getClassLoader(), project).problems().stream().anyMatch(p -> p.contains("tracked by git")));
    }

    @Test
    void theEntryPointReportsUsageAndFailures() throws Exception {
        assertEquals(2, Main.run(new String[0]));
        assertEquals(2, Main.run(new String[] {"stage", "--jar"}));
        assertEquals(1, Main.run(new String[] {"stage", "--jar", appJar().toString(), "--version", "x", "--out", Files.createTempDirectory("x").toString()}));
    }

    @Test
    void packingUsesTheToolgateCli() throws Exception {
        assertEquals(null, Packer.cli(Map.of("PATH", "")));
        assertEquals("/opt/tg", Packer.cli(Map.of("TOOLGATE_CLI", "/opt/tg")));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Packer.pack(Path.of("staging"), Path.of("out.tgpkg"), Map.of("PATH", "")));
        assertTrue(missing.getMessage().contains("toolgate CLI was not found"), missing.getMessage());
        String cli = System.getProperty("toolgate.cli", "");
        assumeTrue(!cli.isEmpty() && Files.isExecutable(Path.of(cli)), "the toolgate CLI is built (set TOOLGATE_CLI)");
        Path staging = Staging.stage(appJar(), List.of(library("a.jar")), "1.0.0", Files.createTempDirectory("toolgate-pack"));
        Path file = staging.resolveSibling("acme.stock-1.0.0.tgpkg");
        String digests = Packer.pack(staging, file, Map.of("TOOLGATE_CLI", cli));
        assertTrue(Files.size(file) > 0);
        assertTrue(digests.contains("packageDigest"), digests);
    }

    private static boolean git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        command.addAll(List.of(args));
        try {
            return new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
