// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.JsonSdkProcessWorker;
import io.ololabs.toolgate.jsonsdk.JsonSdk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Real compiler and canonical CLI; no build mocks. Also runs in the foundation core-package gate. */
@EnabledIfSystemProperty(named = "toolgate.cli", matches = ".+")
class JsonSdkWorkerTest {
    private Path javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"); }
    private byte[] source() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                json.readTree(Path.of(System.getProperty("toolgate.fixtures")).toFile()).path("SdkBuilderSave").path("source").asText());
        source.put("sdkVersion", System.getProperty("toolgate.productVersion"));
        return json.writeValueAsBytes(source);
    }
    @Test void builderWorkerAndStandaloneSdkProduceIdenticalPackagesAndSourceArchives() throws Exception {
        Path root = Path.of(System.getProperty("toolgate.workerWorkRoot")); Files.createDirectories(root);
        Path workerJobs = Files.createTempDirectory(root, "worker-proof-");
        Files.writeString(workerJobs.resolve("keep.txt"), "preserve unrelated data");
        var worker = new JsonSdkProcessWorker(javaExecutable(), Path.of(System.getProperty("toolgate.sdkLibraries")),
                Path.of(System.getProperty("toolgate.cli")), workerJobs, Duration.ofMinutes(2));
        var outputs = worker.build(source());
        Path proof = root.resolve("api-" + UUID.randomUUID());
        var direct = JsonSdk.bundle(source(), proof.resolve("work"), proof.resolve("exports"), Map.of("TOOLGATE_CLI", System.getProperty("toolgate.cli")));
        assertArrayEquals(Files.readAllBytes(direct.packageFile()), outputs.packageBytes());
        assertArrayEquals(Files.readAllBytes(direct.sourceFile()), outputs.sourceArchive());
        assertEquals("preserve unrelated data", Files.readString(workerJobs.resolve("keep.txt")));
        try (var files = Files.list(workerJobs)) { assertEquals(1, files.count()); }
    }
    @Test void timeoutKillsCompilationAndCleansOnlyItsOwnWorkspace() throws Exception {
        Path root = Path.of(System.getProperty("toolgate.workerWorkRoot")); Files.createDirectories(root);
        Path work = Files.createTempDirectory(root, "timeout-proof-");
        var worker = new JsonSdkProcessWorker(javaExecutable(), Path.of(System.getProperty("toolgate.sdkLibraries")),
                Path.of(System.getProperty("toolgate.cli")), work, Duration.ofMillis(1));
        assertThrows(java.io.IOException.class, () -> worker.build(source()));
        try (var files = Files.list(work)) { assertEquals(0, files.count()); }
    }
    @Test void compilerProcessEnforcesItsDeadlineWithoutAParentSupervisor() throws Exception {
        Path root = Path.of(System.getProperty("toolgate.workerWorkRoot")); Files.createDirectories(root);
        Path work = Files.createTempDirectory(root, "self-deadline-"); Files.write(work.resolve("toolgate.json"), source());
        var process = new ProcessBuilder(javaExecutable().toString(), "-Dtoolgate.sdk.workerTimeoutMs=1", "-cp",
                System.getProperty("toolgate.sdkLibraries") + java.io.File.separator + "*", "io.ololabs.toolgate.jsonsdk.Main", "bundle", work.toString(), work.resolve("exports").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(124, process.exitValue());
            assertFalse(Files.exists(work.resolve("exports/build.json")));
        } finally { process.destroyForcibly(); }
    }
}
