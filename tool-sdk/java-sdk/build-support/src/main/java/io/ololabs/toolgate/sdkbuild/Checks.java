// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdkbuild;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ExampleResult;
import io.ololabs.toolgate.testing.ToolHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code toolgateCheck} / {@code toolgate:check}: the checks a build runs before packaging. {@code toolgate.dev.json}
 * must not be tracked by git (runtime-modes.md §5), and every declared {@code @Example} must pass in test mode
 * (execution.md §7), with the dev file's globals, secrets, claims and destinations as fixtures.
 */
public final class Checks {
    /** What the checks found: the problems, and a line per example that ran. */
    public record Report(List<String> problems, List<String> ran) {
        public boolean passed() {
            return problems.isEmpty();
        }
    }

    private Checks() {}

    public static Report run(ClassLoader tools, Path projectDirectory) throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        Path devFile = projectDirectory.resolve("toolgate.dev.json");
        if (Files.exists(devFile) && tracked(projectDirectory, "toolgate.dev.json")) {
            problems.add("toolgate.dev.json is tracked by git; it holds local secrets and fixture claims, so untrack it and add it to .gitignore");
        }
        ToolHarness.Builder harness = ToolHarness.builder().classLoader(tools);
        if (Files.isRegularFile(devFile)) {
            JsonNode dev = Staging.JSON.readTree(devFile.toFile());
            dev.path("globals").properties().forEach(e -> harness.global(e.getKey(), e.getValue()));
            dev.path("secrets").properties().forEach(e -> harness.secret(e.getKey(), e.getValue()));
            dev.path("claims").properties().forEach(e -> harness.claim(e.getKey(), e.getValue()));
            for (Map.Entry<String, JsonNode> e : dev.path("destinations").properties()) harness.destination(e.getKey(), e.getValue().asText());
        }
        try (ToolHarness h = harness.build()) {
            for (ExampleResult r : h.runExamples()) {
                ran.add(r.toString());
                if (!r.passed()) problems.add(r.toString());
            }
        }
        return new Report(problems, ran);
    }

    /** Whether git tracks {@code file}; false when the directory is not a repository or git is missing. */
    static boolean tracked(Path directory, String file) {
        try {
            Path executable = null;
            String path = System.getenv("PATH");
            if (path == null) return false;
            String name = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "git.exe" : "git";
            for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path folder = Path.of(entry);
                if (!folder.isAbsolute()) continue;
                Path candidate = folder.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    candidate = candidate.toRealPath();
                    if (!candidate.startsWith(directory.toRealPath())) { executable = candidate; break; }
                }
            }
            if (executable == null) return false;
            Process git = new ProcessBuilder(executable.toString(), "ls-files", "--error-unmatch", "--", file).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!git.waitFor(30, TimeUnit.SECONDS)) {
                git.destroyForcibly();
                return false;
            }
            return git.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
