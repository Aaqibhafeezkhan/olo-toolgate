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
        return library(name, Map.of("lib/" + name + ".txt", "fixture " + name));
    }

    /** A dependency jar with these entries, signed-looking and with its own manifest, as published jars often are. */
    static Path library(String name, Map<String, String> entries) throws IOException {
        Path lib = Files.createTempDirectory("toolgate-lib").resolve(name);
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "some.Other");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(lib), manifest)) {
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(Map.of("META-INF/LIB.SF", "signature", "module-info.class", "module"))
                    .entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return lib;
    }

    static List<String> names(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> names = new java.util.ArrayList<>();
            java.util.Collections.list(zip.entries()).forEach(e -> names.add(e.getName()));
            return names;
        }
    }

    static String text(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void stagingWritesTheSourceManifestAndOneSelfContainedJar() throws Exception {
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
        assertEquals("[\"tool\"]", tool.path("artifactIds").toString());
        assertEquals("[{\"artifactId\":\"tool\",\"path\":\"artifacts/tool.jar\",\"mediaType\":\"application/java-archive\",\"runtimeId\":\"jvm\"}]",
                m.path("artifacts").toString());
        assertFalse(tool.has("toolDescriptorDigest"), "digests are the packer's");
        try (Stream<Path> files = Files.walk(out.resolve("artifacts"))) {
            assertEquals(List.of(out.resolve(Staging.TOOL_JAR)), files.filter(Files::isRegularFile).collect(Collectors.toList()));
        }
        Path fat = out.resolve(Staging.TOOL_JAR);
        List<String> names = names(fat);
        assertEquals(List.of("META-INF/", "META-INF/MANIFEST.MF"), names.subList(0, 2), "the manifest leads, as JarInputStream expects");
        assertTrue(names.contains("lib/a.jar.txt") && names.contains("lib/b.jar.txt"), names.toString());
        assertTrue(names.contains("META-INF/toolgate/package.json") && names.contains("acme/stock/StockTools.class"), names.toString());
        assertFalse(names.contains("META-INF/LIB.SF") || names.contains("module-info.class"), "signatures and module descriptors are dropped");
        assertTrue(text(fat, "META-INF/MANIFEST.MF").contains("Main-Class: " + Staging.ENTRY_POINT), "java -jar serves the tools");
        try (java.util.jar.JarInputStream in = new java.util.jar.JarInputStream(Files.newInputStream(fat))) {
            assertEquals(Staging.ENTRY_POINT, in.getManifest().getMainAttributes().getValue("Main-Class"));
        }
    }

    @Test
    void theFatJarMergesServicesAndRefusesTwoVersionsOfAClass() throws Exception {
        Path a = library("a.jar", Map.of("META-INF/services/x.Spi", "a.Impl\n# comment\n", "shared.txt", "from a", "p/Same.class", "same"));
        Path b = library("b.jar", Map.of("META-INF/services/x.Spi", "b.Impl\na.Impl\n", "shared.txt", "from b", "p/Same.class", "same"));
        Path out = Staging.stage(appJar(), List.of(a, b), "1.0.0", Files.createTempDirectory("toolgate-fat"));
        Path fat = out.resolve(Staging.TOOL_JAR);
        assertEquals("a.Impl\nb.Impl\n", text(fat, "META-INF/services/x.Spi"));
        assertEquals("from a", text(fat, "shared.txt"), "the first jar on the class path wins a resource");
        Path c = library("c.jar", Map.of("p/Same.class", "different"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Staging.stage(appJar(), List.of(a, c), "1.0.0", Files.createTempDirectory("toolgate-clash")));
        assertTrue(e.getMessage().contains("p/Same.class"), e.getMessage());
        Path notAJar = Files.createTempDirectory("toolgate-bad").resolve("bad.jar");
        Files.writeString(notAJar, "not a jar");
        assertThrows(IllegalArgumentException.class, () -> Staging.stage(appJar(), List.of(notAJar), "1.0.0", Files.createTempDirectory("x")));
    }

    @Test
    void stagingIsDeterministicAndRefusesBadInput() throws Exception {
        Path jar = appJar();
        List<Path> libs = List.of(library("a.jar"));
        Path one = Staging.stage(jar, libs, "1.0.0", Files.createTempDirectory("toolgate-one"));
        Path two = Staging.stage(jar, libs, "1.0.0", Files.createTempDirectory("toolgate-two"));
        for (String f : List.of("toolgate-package.json", Staging.TOOL_JAR)) {
            assertArrayEquals(Files.readAllBytes(one.resolve(f)), Files.readAllBytes(two.resolve(f)), f);
        }
        assertThrows(IllegalArgumentException.class, () -> Staging.stage(jar, libs, "1.0", Files.createTempDirectory("x")));
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
        IllegalStateException absent = assertThrows(IllegalStateException.class,
                () -> Packer.pack(Path.of("staging"), Files.createTempDirectory("x").resolve("out.tgpkg"), Map.of("TOOLGATE_CLI", "/nonexistent/toolgate")));
        assertTrue(absent.getMessage().contains("cannot run the toolgate CLI"), absent.getMessage());
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
