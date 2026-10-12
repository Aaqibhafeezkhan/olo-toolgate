// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes {@code seed-manifest.json} for the seed build stage (implementation prompt §3.2 step 1).
 *
 * <p>Every folder of the two example builds that has a {@code build.gradle.kts} is a package source; a folder whose
 * build produced no package is listed with {@code "package": null}, so a missing package is reported, never dropped.
 *
 * <pre>seed-manifest &lt;TOOLGATE_DIR&gt; &lt;output dir&gt;</pre>
 */
public final class SeedManifest {
    static final Map<String, String> CATEGORIES = Map.of(
            "java-tools", "Operations",
            "mocked-tools", "Demo (mocked data)");

    private SeedManifest() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: seed-manifest <TOOLGATE_DIR> <output dir>");
            System.exit(2);
        }
        Path toolgate = Path.of(args[0]).toRealPath();
        Path out = Path.of(args[1]);
        Files.createDirectories(out.resolve("packages"));
        Map<String, Object> manifest = build(toolgate, out);
        Files.writeString(out.resolve("seed-manifest.json"), Json.write(manifest) + "\n", StandardCharsets.UTF_8);
        System.out.println("seed-manifest: " + Json.arr(manifest.get("entries")).size() + " example folders");
    }

    static Map<String, Object> build(Path toolgate, Path out) throws IOException {
        List<Object> entries = new ArrayList<>();
        for (String build : List.of("java-tools", "mocked-tools")) {
            Path root = toolgate.resolve("examples").resolve(build);
            List<Path> folders;
            try (Stream<Path> list = Files.list(root)) {
                folders = list.filter(p -> Files.isRegularFile(p.resolve("build.gradle.kts"))).sorted().toList();
            }
            for (Path folder : folders) {
                entries.add(entry(toolgate, build, folder, out));
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("toolgateRevision", System.getenv().getOrDefault("TOOLGATE_REVISION", "unknown"));
        manifest.put("entries", entries);
        return manifest;
    }

    private static Map<String, Object> entry(Path toolgate, String build, Path folder, Path out) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("folder", toolgate.relativize(folder).toString());
        entry.put("package", null);
        entry.put("packageId", null);
        entry.put("version", null);
        entry.put("category", CATEGORIES.get(build));
        entry.put("mocked", build.equals("mocked-tools"));
        Path readme = folder.resolve("README.md");
        entry.put("readme", Files.isRegularFile(readme) ? Files.readString(readme, StandardCharsets.UTF_8) : null);
        Path built = folder.resolve("build/toolgate");
        List<Path> packages = List.of();
        if (Files.isDirectory(built)) {
            try (Stream<Path> list = Files.list(built)) {
                packages = list.filter(p -> p.toString().endsWith(".tgpkg") && Files.isRegularFile(p)).sorted().toList();
            }
        }
        if (packages.size() == 1) {
            Path source = packages.get(0);
            Path target = out.resolve("packages").resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> m = Json.obj(Json.parse(PackageReader.read(Files.readAllBytes(target), Long.MAX_VALUE)
                    .get(PackageReader.MANIFEST)));
            entry.put("package", "packages/" + source.getFileName());
            entry.put("packageId", m.get("id"));
            entry.put("version", m.get("version"));
        } else if (packages.size() > 1) {
            entry.put("error", "more than one package in " + toolgate.relativize(built));
        }
        return entry;
    }
}
