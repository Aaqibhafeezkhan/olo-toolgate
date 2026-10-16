// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.SdkBuildWorker;
import io.ololabs.toolgate.jsonsdk.JsonProject;
import io.ololabs.toolgate.jsonsdk.JsonSdk;
import io.ololabs.toolgate.packagearchive.PackageArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Fixed compiler process for the closed JSON SDK language. No command or code supplied by the user is launched. */
public final class JsonSdkProcessWorker implements SdkBuildWorker {
    private final Path javaExecutable, sdkLibraries, cli, workRoot;
    private final Duration timeout;

    public JsonSdkProcessWorker(Path java, Path sdkLibraries, Path cli, Path workRoot, Duration timeout) throws IOException {
        this.javaExecutable = executable(java); this.cli = executable(cli);
        Path compiler = this.javaExecutable.resolveSibling(this.javaExecutable.getFileName().toString().endsWith(".exe") ? "javac.exe" : "javac");
        if (!Files.isRegularFile(compiler, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SDK worker requires a complete JDK");
        this.sdkLibraries = sdkLibraries.toRealPath(LinkOption.NOFOLLOW_LINKS);
        this.workRoot = workRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(sdkLibraries) || !Files.isDirectory(this.sdkLibraries) || Files.isSymbolicLink(workRoot)
                || !Files.isDirectory(this.workRoot) || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IOException("Invalid SDK worker configuration");
        this.timeout = timeout;
    }

    private static Path executable(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(path)) throw new IOException("Invalid SDK worker executable");
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    @Override public Outputs build(byte[] source) throws IOException, InterruptedException {
        byte[] canonical = JsonProject.parse(source).source();
        Path job = Files.createTempDirectory(workRoot, "sdk-");
        try {
            if (Files.getFileStore(job).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(job, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path temporary = Files.createDirectory(job.resolve("tmp"));
            Files.write(job.resolve(JsonProject.FILE), canonical, StandardOpenOption.CREATE_NEW);
            Path output = job.resolve("exports");
            var command = new ProcessBuilder(javaExecutable.toString(), "-Xmx512m", "-XX:ActiveProcessorCount=2", "-XX:-UsePerfData",
                    "-Djava.io.tmpdir=" + temporary,
                    "-Dtoolgate.sdk.workerTimeoutMs=" + timeout.toMillis(),
                    "-cp", sdkLibraries + java.io.File.separator + "*", "io.ololabs.toolgate.jsonsdk.Main", "bundle", job.toString(), output.toString())
                    .directory(job.toFile()).redirectErrorStream(true);
            command.environment().clear();
            command.environment().put("TOOLGATE_CLI", cli.toString());
            command.environment().put("PATH", javaExecutable.getParent() + java.io.File.pathSeparator + cli.getParent());
            command.environment().put("LANG", "C.UTF-8");
            command.environment().put("TMPDIR", temporary.toString());
            command.environment().put("TEMP", temporary.toString());
            command.environment().put("TMP", temporary.toString());
            Process process = command.start();
            try (var drains = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var drained = drains.submit(() -> {
                    try (var stream = process.getInputStream()) {
                        byte[] buffer = new byte[4096]; int count = 0, read;
                        while ((read = stream.read(buffer)) != -1) {
                            count += read;
                            if (count > 65536) { process.destroyForcibly(); throw new IOException("SDK worker output exceeded its limit"); }
                        }
                    }
                    return true;
                });
                try {
                    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("SDK worker timed out");
                    drained.get(10, TimeUnit.SECONDS);
                    if (process.exitValue() != 0) throw new IOException("SDK worker rejected the project");
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new IOException("SDK worker failed", failure);
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
            byte[] packaged = bounded(output.resolve("package.tgpkg"), PackageArchive.MAX_PACKAGE_BYTES);
            byte[] exported = bounded(output.resolve("source.zip"), 131072);
            PackageArchive.validate(packaged);
            if (!Arrays.equals(exported, JsonSdk.sourceArchive(canonical))) throw new IOException("SDK source export does not match its project");
            bounded(output.resolve("build.json"), 8192);
            return new Outputs(packaged, exported);
        } finally {
            // job is created here under the configured work root, never supplied by a caller.
            if (job.getParent().equals(workRoot)) {
                try (var files = Files.walk(job)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static byte[] bounded(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum) throw new IOException("Invalid SDK build output");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Invalid SDK build output");
            return bytes;
        }
    }
}
