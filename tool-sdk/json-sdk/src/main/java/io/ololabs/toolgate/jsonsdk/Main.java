// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.jsonsdk;

import java.nio.file.Files;
import java.nio.file.Path;

/** JSON SDK distribution entry point; Builder uses JsonSdk rather than reimplementing compilation. */
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3 || !java.util.Set.of("bundle", "dev", "check", "pack", "source").contains(args[0])) {
            System.err.println("usage: toolgate-json-sdk bundle|dev|check|pack|source [project-directory] [output]");
            System.exit(2); return;
        }
        try {
            Path root = Path.of(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
            Path input = root.resolve(JsonProject.FILE);
            if (Files.isSymbolicLink(input) || Files.size(input) > JsonProject.MAX_BYTES) throw JsonProject.invalid("source file");
            byte[] source;
            try (var stream = Files.newInputStream(input, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { source = stream.readNBytes(JsonProject.MAX_BYTES + 1); }
            var project = JsonProject.parse(source);
            if (args[0].equals("bundle")) {
                Path output = args.length > 2 ? Path.of(args[2]).toAbsolutePath().normalize() : root.resolve("build/exports");
                var watchdog = watchdog();
                try { JsonSdk.bundle(source, root.resolve("build/run-" + java.util.UUID.randomUUID()), output, System.getenv()); }
                finally { if (watchdog != null) watchdog.shutdownNow(); }
                System.out.println("JSON SDK bundle passed: " + project.id() + "@" + project.version());
                return;
            }
            Path output = Path.of(args.length > 2 ? args[2] : root.resolve("build/toolgate/" + project.id() + "-" + project.version()
                    + (args[0].equals("source") ? ".source.zip" : ".tgpkg")).toString()).toAbsolutePath().normalize();
            if (args[0].equals("source")) {
                Files.createDirectories(output.getParent()); Files.write(output, JsonSdk.sourceArchive(source));
            } else {
                Files.createDirectories(root.resolve("build"));
                Path work = root.resolve("build/run-" + java.util.UUID.randomUUID());
                var build = JsonSdk.stage(source, work);
                if (args[0].equals("dev")) {
                    JsonSdk.check(build);
                    String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                    var process = new ProcessBuilder(java, "-jar", build.staging().resolve("artifacts/tool.jar").toString()).directory(root.toFile()).inheritIO();
                    process.environment().put("TOOLGATE_MODE", "development");
                    System.exit(process.start().waitFor());
                } else if (args[0].equals("pack")) JsonSdk.pack(build, output, System.getenv());
                else JsonSdk.check(build);
            }
            System.out.println("JSON SDK " + args[0] + " passed: " + project.id() + "@" + project.version());
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException rejected) {
            System.err.println(rejected.getMessage()); System.exit(1);
        }
    }
    /** The worker enforces its own lifetime too, so a lost web-process supervisor cannot leave an unbounded compiler. */
    private static java.util.concurrent.ScheduledExecutorService watchdog() {
        String setting = System.getProperty("toolgate.sdk.workerTimeoutMs");
        if (setting == null) return null;
        long millis = Long.parseLong(setting);
        if (millis < 1 || millis > 300000) throw new IllegalArgumentException("Invalid SDK worker deadline");
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "sdk-worker-deadline"); thread.setDaemon(true); return thread;
        });
        timer.schedule(() -> System.exit(124), millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        return timer;
    }
}
