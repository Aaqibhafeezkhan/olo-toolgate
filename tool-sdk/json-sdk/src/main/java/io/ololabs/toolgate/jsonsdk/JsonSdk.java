// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.jsonsdk;

import io.ololabs.toolgate.sdkbuild.Packer;
import io.ololabs.toolgate.sdkbuild.Staging;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** The single compiler/stager used by CLI and Builder. All tool behavior is compiled by the Java SDK. */
public final class JsonSdk {
    private static final java.time.LocalDateTime ZIP_TIME = java.time.LocalDateTime.of(1980, 1, 2, 0, 0);
    public record Build(Path jar, Path staging, JsonProject project) {}
    /** Immutable build outputs consumed by CLI and Builder workers. A receipt is written only after verification. */
    public record Bundle(Path packageFile, Path sourceFile, String artifactDigest, String packageDigest,
                         String descriptorSetDigest, String sourceDigest, String sourceArchiveDigest) {}
    private JsonSdk() {}

    public static Build stage(byte[] source, Path work) throws IOException {
        JsonProject project = JsonProject.parse(source);
        if (Files.exists(work, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Build directory must be new");
        Files.createDirectories(work);
        Path sourceFile = work.resolve("source/io/ololabs/toolgate/generated/JsonTools.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, JavaSource.generate(project), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path classes = work.resolve("classes"), generated = work.resolve("generated");
        Files.createDirectories(classes); Files.createDirectories(generated);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JSON SDK builds require JDK 17 or later");
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "17", "-encoding", "UTF-8", "-parameters", "-classpath", compilerClasspath(),
                    "-processor", "io.ololabs.toolgate.sdk.processor.DescriptorProcessor", "-Atoolgate.descriptorGeneration=1",
                    "-d", classes.toString(), "-s", generated.toString());
            if (!Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjects(sourceFile)).call())) {
                throw new IllegalArgumentException("JSON SDK compilation rejected: " + diagnostics.getDiagnostics().stream()
                        .map(d -> d.getCode() + ": " + d.getMessage(java.util.Locale.ROOT)).toList());
            }
        }
        Path jar = work.resolve("tool.jar");
        try (var out = new JarOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(jar))); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> classes.relativize(p).toString())).toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTimeLocal(ZIP_TIME); out.putNextEntry(entry); Files.copy(file, out); out.closeEntry();
            }
        }
        var libraries = runtimeLibraries();
        return new Build(jar, Staging.stage(jar, libraries, project.version(), work.resolve("staging")), project);
    }

    /** SDK tests use the generated package's descriptors and handler, never an alternative JSON interpreter. */
    public static void check(Build build) throws IOException {
        try (var loader = new URLClassLoader(new URL[]{build.jar().toUri().toURL()}, JsonSdk.class.getClassLoader())) {
            // Pure CLIENT handlers can be tested without granting device resources.
            var report = io.ololabs.toolgate.sdkbuild.Checks.run(loader, build.jar().getParent());
            if (!report.passed()) throw new IllegalArgumentException("JSON SDK examples failed: " + report.problems());
        }
    }

    public static Path pack(Build build, Path output, Map<String, String> environment) throws IOException, InterruptedException {
        check(build);
        Packer.pack(build.staging(), output, environment);
        io.ololabs.toolgate.packagearchive.PackageArchive.validate(Files.readAllBytes(output));
        return output;
    }

    /** One build, one package, one canonical source export. Control delegates this operation to its build worker. */
    public static Bundle bundle(byte[] source, Path work, Path output, Map<String, String> environment) throws IOException, InterruptedException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Export directory must be new");
        var build = stage(source, work);
        Files.createDirectories(output);
        Path packageFile = output.resolve("package.tgpkg"), sourceFile = output.resolve("source.zip");
        pack(build, packageFile, environment);
        var verified = io.ololabs.toolgate.packagearchive.PackageArchive.validate(Files.readAllBytes(packageFile));
        byte[] archive = sourceArchive(source);
        Files.write(sourceFile, archive, StandardOpenOption.CREATE_NEW);
        var result = new Bundle(packageFile, sourceFile, digest(Files.readAllBytes(packageFile)), verified.packageDigest(),
                verified.descriptorSetDigest(), digest(build.project().source()), digest(archive));
        var receipt = JsonProject.JSON.createObjectNode().put("schemaVersion", 1).put("sdkVersion", JsonProject.sdkVersion())
                .put("packageId", build.project().id()).put("version", build.project().version())
                .put("packageFile", "package.tgpkg").put("sourceFile", "source.zip")
                .put("artifactDigest", result.artifactDigest()).put("packageDigest", result.packageDigest())
                .put("descriptorSetDigest", result.descriptorSetDigest()).put("sourceDigest", result.sourceDigest())
                .put("sourceArchiveDigest", result.sourceArchiveDigest());
        Files.write(output.resolve("build.json"), JsonProject.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        return result;
    }

    private static String digest(byte[] value) {
        try { return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Exactly one source export. ZIP bytes contain no build timestamps, machine paths or job identity. */
    public static byte[] sourceArchive(byte[] source) throws IOException {
        JsonProject project = JsonProject.parse(source);
        Map<String, byte[]> files = new TreeMap<>();
        files.put(JsonProject.FILE, project.source());
        files.put(".gitignore", "build/\ntoolgate.dev.json\n".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", ("# " + project.name() + "\n\nBuild with the matching ToolGate JSON SDK distribution (JDK 17+).\n\n"
                + "    toolgate-json-sdk check .\n    toolgate-json-sdk pack .\n\n"
                + "Commit this canonical project to your repository to edit and rebuild it.\n"
                + "To include it in ToolGate's core inventory, place it under core-mcp/<package> and select sdk=json.\n"
                + "The built .tgpkg uses the same package and execution contract as the Java SDK.\n").getBytes(StandardCharsets.UTF_8));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                var entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(ZIP_TIME); entry.setMethod(ZipEntry.STORED);
                var crc = new CRC32(); crc.update(file.getValue());
                entry.setSize(file.getValue().length); entry.setCompressedSize(file.getValue().length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Import limits: a project is one bounded toolgate.json, so a source ZIP is small. */
    public static final int MAX_ARCHIVE_BYTES = 1048576, MAX_ARCHIVE_ENTRIES = 64, MAX_FILE_BYTES = 65536;

    /**
     * The project in a source ZIP: the one {@value JsonProject#FILE}, validated and canonical. Accepts the export as-is
     * or rezipped (any compression or order, optionally inside one top folder); the export's README.md and .gitignore,
     * a local toolgate.dev.json and build/ output are ignored. Any other file is refused rather than silently dropped,
     * as are absolute or traversing names, duplicates and oversized entries.
     */
    public static byte[] projectFromArchive(byte[] archive) throws IOException {
        if (archive == null || archive.length > MAX_ARCHIVE_BYTES) throw new IOException("Source ZIP is too large");
        Map<String, byte[]> files = new TreeMap<>();
        int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("Source ZIP has too many entries");
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/") || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException("Unsafe name in source ZIP: " + name);
                if (entry.isDirectory()) continue;
                byte[] bytes = zip.readNBytes(MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) throw new IOException("File too large in source ZIP: " + name);
                if (files.put(name, bytes) != null) throw new IOException("Duplicate file in source ZIP: " + name);
            }
        }
        // One optional top folder, as zipping the unzipped project folder produces.
        String prefix = "";
        var tops = new java.util.TreeSet<String>();
        for (String name : files.keySet()) tops.add(name.contains("/") ? name.substring(0, name.indexOf('/') + 1) : "");
        if (tops.size() == 1 && !tops.first().isEmpty()) prefix = tops.first();
        byte[] project = null;
        for (var file : files.entrySet()) {
            String name = file.getKey().substring(prefix.length());
            if (name.equals(JsonProject.FILE)) project = file.getValue();
            else if (!List.of("README.md", ".gitignore", "toolgate.dev.json").contains(name) && !name.startsWith("build/"))
                throw new IOException("Unexpected file in source ZIP: " + file.getKey());
        }
        if (project == null) throw new IOException("Source ZIP has no " + JsonProject.FILE);
        try { return JsonProject.parse(project).source(); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }

    private static final List<Class<?>> RUNTIME = List.of(
            io.ololabs.toolgate.runtime.StandaloneRuntime.class, io.ololabs.toolgate.protocol.ProtocolCore.class,
            io.ololabs.toolgate.sdk.context.ToolContext.class, com.fasterxml.jackson.databind.ObjectMapper.class,
            com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class, org.sqlite.JDBC.class);

    private static Path location(Class<?> type) {
        try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (java.net.URISyntaxException | NullPointerException failure) { throw new IllegalStateException("Missing SDK artifact: " + type.getName(), failure); }
    }

    private static String compilerClasspath() {
        var paths = new java.util.LinkedHashSet<Path>();
        for (Class<?> type : RUNTIME) paths.add(location(type));
        paths.add(location(io.ololabs.toolgate.sdk.processor.DescriptorProcessor.class));
        return paths.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static List<Path> runtimeLibraries() {
        return RUNTIME.stream().map(JsonSdk::location).distinct().map(path -> {
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar"))
                throw new IllegalStateException("Install the SDK distribution before packing: " + path);
            return path;
        }).toList();
    }
}
