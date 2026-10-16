// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.jsonsdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URLClassLoader;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class JsonSdkTest {
    @TempDir Path directory;
    private byte[] example() throws Exception {
        try (var stream = getClass().getResourceAsStream("/hello-world/toolgate.json")) { return stream.readAllBytes(); }
    }

    @Test void compilationUsesJavaSdkDescriptorsAndRuntimeForBothDeclarations() throws Exception {
        var build = JsonSdk.stage(example(), directory.resolve("build"));
        JsonSdk.check(build);
        try (var loader = new URLClassLoader(new java.net.URL[]{build.jar().toUri().toURL()}, getClass().getClassLoader());
             var harness = io.ololabs.toolgate.testing.ToolHarness.builder().classLoader(loader).build()) {
            assertEquals("Hello World", harness.call("client_greet", java.util.Map.of()).output().path("message").asText());
            assertEquals("Hello Anupriya", harness.call("server_greet", java.util.Map.of("text", "Anupriya")).output().path("message").asText());
            assertTrue(harness.call("server_greet", java.util.Map.of("text", 5)).isError());
        }
        var manifest = JsonProject.JSON.readTree(Files.readAllBytes(build.staging().resolve("toolgate-package.json")));
        assertEquals(2, manifest.path("schemaVersion").intValue());
        assertEquals("JAVA", manifest.path("sdk").path("language").asText());
        assertEquals(2, manifest.path("tools").size());
        assertEquals("World", manifest.path("tools").get(0).path("descriptor").path("inputSchema").path("properties").path("text").path("default").asText());
    }

    @Test void sourceExportAndBuildAreDeterministicAcrossWorkDirectories() throws Exception {
        byte[] source = example();
        assertArrayEquals(JsonSdk.sourceArchive(source), JsonSdk.sourceArchive(source));
        byte[] exported = null;
        var names = new ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(JsonSdk.sourceArchive(source)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName()); if (entry.getName().equals("toolgate.json")) exported = zip.readAllBytes();
            }
        }
        assertEquals(java.util.List.of(".gitignore", "README.md", "toolgate.json"), names);
        var builder = JsonSdk.stage(source, directory.resolve("builder"));
        var cli = JsonSdk.stage(exported, directory.resolve("cli"));
        assertArrayEquals(Files.readAllBytes(builder.staging().resolve("artifacts/tool.jar")), Files.readAllBytes(cli.staging().resolve("artifacts/tool.jar")));
        assertArrayEquals(Files.readAllBytes(builder.staging().resolve("toolgate-package.json")), Files.readAllBytes(cli.staging().resolve("toolgate-package.json")));
    }

    @Test void rejectsUnbuiltCodeUnknownFieldsDuplicateKeysAndInvalidDefaults() throws Exception {
        String source = new String(example(), StandardCharsets.UTF_8);
        for (String invalid : java.util.List.of(
                source.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"source\": \"run me\""),
                source.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
                source.replace("\"schemaVersion\": 1", "\"schemaVersion\": 4294967297"),
                source.replace("\"maxLength\": 128", "\"maxLength\": 4294967424"),
                source.replace("\"sdkVersion\": \"0.10.0-dev\"", "\"sdkVersion\": \"9.9.9\""),
                source.replace("\"default\": \"World\"", "\"default\": 5"),
                source.replace("\"input\": \"text\"", "\"input\": \"secret\""),
                source.replace("\"concat\"", "\"eval\""), source + " {}")) {
            assertThrows(Exception.class, () -> JsonProject.parse(invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void literalsCannotInjectJavaSource() throws Exception {
        String text = "\"); System.exit(0); //";
        var project = JsonProject.parse(example());
        var document = project.document();
        ((com.fasterxml.jackson.databind.node.ObjectNode)document.path("tools").get(0).path("output").path("message").path("concat").get(0)).put("literal", text);
        var build = JsonSdk.stage(JsonProject.JSON.writeValueAsBytes(document), directory.resolve("quoted"));
        JsonSdk.check(build);
    }

    @Test void timeZoneDoesNotChangeSourceOrArtifactBytes() throws Exception {
        var original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            var source = JsonSdk.sourceArchive(example());
            var first = JsonSdk.stage(example(), directory.resolve("utc"));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            assertArrayEquals(source, JsonSdk.sourceArchive(example()));
            var second = JsonSdk.stage(example(), directory.resolve("india"));
            assertArrayEquals(Files.readAllBytes(first.staging().resolve("artifacts/tool.jar")), Files.readAllBytes(second.staging().resolve("artifacts/tool.jar")));
        } finally { java.util.TimeZone.setDefault(original); }
    }
}
