// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdkbuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the staging directory {@code toolgate pack} turns into a {@code .tgpkg} (tool-sdk/cli/README.md): the
 * {@code toolgate-package.json} source manifest, the tool jar as {@code artifacts/app.jar} and its runtime
 * dependencies as {@code artifacts/libs.zip}. Digests are left to the packer. The Tool Host runs every tool with
 * {@code app.jar} and the jars in {@code libs.zip} on the class path and the standalone runtime's {@code Main} as the
 * entry point, the same server {@code toolgateDev} starts.
 */
public final class Staging {
    public static final String MANIFEST = "toolgate-package.json";
    public static final String ENTRY_POINT = "io.ololabs.toolgate.runtime.Main";
    public static final String RUNTIME_ID = "jvm";
    /** The Java release every SDK artifact targets. */
    public static final String JAVA_RELEASE = "17";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");
    /** A fixed date for every zip entry, so the same jars give the same bytes. */
    private static final long ZIP_TIME = 315_532_800_000L;

    private Staging() {}

    /**
     * Stages a package.
     *
     * @param appJar the jar the build made from the tool sources, with {@code META-INF/toolgate}
     * @param libraries the runtime class path besides {@code appJar}; directories are refused
     * @param version the package version, SemVer 2.0.0
     * @param out the staging directory; replaced
     */
    public static Path stage(Path appJar, List<Path> libraries, String version, Path out) throws IOException {
        if (version == null || !SEMVER.matcher(version).matches()) {
            throw new IllegalArgumentException("the package version must be SemVer 2.0.0, not " + version);
        }
        ObjectNode index;
        List<JsonNode> descriptors = new ArrayList<>();
        try (ZipFile jar = new ZipFile(appJar.toFile())) {
            index = (ObjectNode) read(jar, "META-INF/toolgate/package.json", appJar
                    + " has no META-INF/toolgate/package.json; compile with the ToolGate processor and -Atoolgate.descriptorGeneration=1");
            for (JsonNode tool : index.path("tools")) {
                String name = tool.path("name").asText();
                descriptors.add(read(jar, "META-INF/toolgate/tools/" + name + ".json", "the descriptor of " + name + " is missing from " + appJar));
            }
        }
        if (descriptors.isEmpty()) throw new IllegalArgumentException(appJar + " declares no tools");

        delete(out);
        Files.createDirectories(out.resolve("artifacts"));
        Files.copy(appJar, out.resolve("artifacts/app.jar"));
        writeLibraries(libraries, out.resolve("artifacts/libs.zip"));

        Properties sdk = sdkProperties();
        ObjectNode m = JSON.createObjectNode();
        m.put("schemaVersion", 2);
        m.put("kind", "SDK");
        m.put("id", index.path("id").asText());
        m.put("version", version);
        m.put("publisher", index.path("publisher").asText());
        ObjectNode sdkInfo = m.putObject("sdk");
        sdkInfo.put("language", "JAVA");
        sdkInfo.put("version", sdk.getProperty("sdkVersion"));
        sdkInfo.putObject("protocol").put("min", 2).put("max", 2);
        sdkInfo.put("descriptorGeneration", index.path("descriptorGeneration").asInt(1));
        m.putObject("compatibility").put("contractsVersion", sdk.getProperty("contractsVersion"));
        m.putArray("runtimes").addObject().put("runtimeId", RUNTIME_ID).put("language", "java").put("version", JAVA_RELEASE);
        ArrayNode tools = m.putArray("tools");
        for (JsonNode d : descriptors) {
            ObjectNode t = tools.addObject();
            t.set("descriptor", d);
            t.put("runtimeId", RUNTIME_ID);
            t.put("entryPoint", ENTRY_POINT);
            t.putArray("artifactIds").add("app").add("libs");
        }
        ArrayNode artifacts = m.putArray("artifacts");
        artifacts.addObject().put("artifactId", "app").put("path", "artifacts/app.jar").put("mediaType", "application/java-archive")
                .put("runtimeId", RUNTIME_ID);
        artifacts.addObject().put("artifactId", "libs").put("path", "artifacts/libs.zip").put("mediaType", "application/zip")
                .put("runtimeId", RUNTIME_ID);
        m.putArray("credentialReferences");
        m.putArray("connections");
        m.putArray("composition");
        m.putObject("limits").put("maxTools", 64).put("maxDescriptorBytes", 1_048_576);
        Files.write(out.resolve(MANIFEST), JSON.writeValueAsBytes(m));
        return out;
    }

    /** The SDK and contracts versions this library was built with. */
    static Properties sdkProperties() {
        Properties p = new Properties();
        try (InputStream in = Staging.class.getResourceAsStream("sdk.properties")) {
            if (in == null) throw new IllegalStateException("sdk.properties is missing from toolgate-sdk-build");
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private static JsonNode read(ZipFile jar, String path, String missing) throws IOException {
        ZipEntry entry = jar.getEntry(path);
        if (entry == null) throw new IllegalArgumentException(missing);
        try (InputStream in = jar.getInputStream(entry)) {
            return JSON.readTree(in);
        }
    }

    /** A deterministic zip of the libraries under {@code lib/}: entries sorted, stored dates fixed. */
    private static void writeLibraries(List<Path> libraries, Path zip) throws IOException {
        Map<String, Path> byName = new TreeMap<>();
        for (Path lib : libraries) {
            if (!Files.isRegularFile(lib)) throw new IllegalArgumentException("a runtime library must be a jar, not " + lib);
            Path prior = byName.put(lib.getFileName().toString(), lib);
            if (prior != null && !Files.isSameFile(prior, lib)) {
                throw new IllegalArgumentException("two runtime libraries are both named " + lib.getFileName() + ": " + prior + " and " + lib);
            }
        }
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, Path> lib : byName.entrySet()) {
                ZipEntry entry = new ZipEntry("lib/" + lib.getKey());
                entry.setTime(ZIP_TIME);
                out.putNextEntry(entry);
                Files.copy(lib.getValue(), out);
                out.closeEntry();
            }
        }
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(p);
        }
    }
}
