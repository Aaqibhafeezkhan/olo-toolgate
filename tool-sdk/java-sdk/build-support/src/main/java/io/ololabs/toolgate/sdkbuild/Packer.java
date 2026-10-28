// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdkbuild;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code toolgate pack} on a staging directory. The CLI is the single packager behind every SDK (platform-requirements.md §4), so
 * the build integrations never write a {@code .tgpkg} themselves. It is {@code TOOLGATE_CLI} when set, else
 * {@code toolgate} on the {@code PATH}.
 */
public final class Packer {
    private Packer() {}

    /** Packs {@code staging} into {@code file} and returns what the CLI printed (the digests). */
    public static String pack(Path staging, Path file, Map<String, String> env) throws IOException, InterruptedException {
        String cli = cli(env);
        if (cli == null) {
            throw new IllegalStateException("the toolgate CLI was not found; install it or set TOOLGATE_CLI. The package is staged at " + staging);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Process p;
        try {
            p = new ProcessBuilder(List.of(cli, "pack", staging.toString(), "-o", file.toString())).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot run the toolgate CLI at " + cli + ": " + e.getMessage() + ". The package is staged at " + staging);
        }
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("toolgate pack failed:\n" + output);
        return output;
    }

    static String cli(Map<String, String> env) {
        String configured = env.get("TOOLGATE_CLI");
        if (configured != null && !configured.isBlank()) return configured;
        String path = env.getOrDefault("PATH", "");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path candidate = Path.of(dir, windows ? "toolgate.exe" : "toolgate");
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }
}
