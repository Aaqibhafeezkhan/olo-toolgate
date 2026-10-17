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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the staging directory {@code toolgate pack} turns into a {@code .tgpkg} (tool-sdk/cli/README.md): the
 * {@code toolgate-package.json} source manifest and one self-contained executable, {@code artifacts/tool.jar}, a fat
 * jar of the tool jar and every runtime dependency whose {@code Main-Class} is the standalone runtime's {@code Main}.
 * Digests are left to the packer. A device needs only a JRE: the Tool Host runs it with {@code java -cp tool.jar Main},
 * and {@code java -jar tool.jar} serves the same tools over stdio as a plain MCP server, as {@code toolgateDev} does.
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
    public static final String TOOL_JAR = "artifacts/tool.jar";
    /** A fixed date for every zip entry, so the same jars give the same bytes. */
    private static final java.time.LocalDateTime ZIP_TIME = java.time.LocalDateTime.of(1980, 1, 2, 0, 0);

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
        writeFatJar(appJar, libraries, out.resolve(TOOL_JAR));

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
            t.putArray("artifactIds").add("tool");
        }
        m.putArray("artifacts").addObject().put("artifactId", "tool").put("path", TOOL_JAR).put("mediaType", "application/java-archive")
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

    /**
     * One deterministic fat jar: the tool jar's entries, then each library's, in class-path order. A resource two jars
     * both carry is taken from the first, except {@code META-INF/services} files, whose providers are merged. Two
     * different copies of one class are refused, since either choice could break the tool. Signatures, indexes,
     * {@code module-info} and the libraries' manifests are dropped; entries are sorted and dated alike.
     */
    static void writeFatJar(Path appJar, List<Path> libraries, Path target) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        Map<String, Path> origin = new TreeMap<>();
        Map<String, Set<String>> services = new TreeMap<>();
        Set<Path> seen = new LinkedHashSet<>();
        boolean multiRelease = false;
        List<Path> jars = new ArrayList<>();
        jars.add(appJar);
        jars.addAll(libraries);
        for (Path jarPath : jars) {
            if (!Files.isRegularFile(jarPath)) throw new IllegalArgumentException("a runtime library must be a jar, not " + jarPath);
            if (!seen.add(jarPath.toAbsolutePath().normalize())) continue;
            try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"))) multiRelease = true;
                for (ZipEntry entry : java.util.Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || dropped(name)) continue;
                    byte[] bytes;
                    try (InputStream in = jar.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    if (name.startsWith("META-INF/services/") && name.indexOf('/', 18) < 0) {
                        Set<String> providers = services.computeIfAbsent(name, k -> new LinkedHashSet<>());
                        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                            String provider = line.replaceFirst("#.*", "").trim();
                            if (!provider.isEmpty()) providers.add(provider);
                        }
                        continue;
                    }
                    byte[] prior = entries.putIfAbsent(name, bytes);
                    if (prior == null) {
                        origin.put(name, jarPath);
                    } else if (name.endsWith(".class") && !Arrays.equals(prior, bytes)) {
                        throw new IllegalArgumentException("two different copies of " + name + ", in " + origin.get(name) + " and " + jarPath
                                + "; exclude one of the dependencies so the tool runs one version");
                    }
                }
            } catch (ZipException e) {
                throw new IllegalArgumentException("a runtime library must be a jar, not " + jarPath, e);
            }
        }
        for (Map.Entry<String, Set<String>> service : services.entrySet()) {
            entries.put(service.getKey(), (String.join("\n", service.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, ENTRY_POINT);
        main.putValue("Created-By", "ToolGate SDK " + sdkProperties().getProperty("sdkVersion"));
        if (multiRelease) main.put(Attributes.Name.MULTI_RELEASE, "true");
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        java.io.ByteArrayOutputStream manifestBytes = new java.io.ByteArrayOutputStream();
        manifest.write(manifestBytes);
        ordered.put(JarFile.MANIFEST_NAME, manifestBytes.toByteArray());
        ordered.putAll(entries);
        Set<String> directories = new java.util.TreeSet<>();
        for (String name : ordered.keySet()) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) directories.add(name.substring(0, slash + 1));
        }
        try (OutputStream file = new java.io.BufferedOutputStream(Files.newOutputStream(target)); ZipOutputStream out = new ZipOutputStream(file)) {
            // META-INF/ and the manifest come first, as JarInputStream expects.
            List<String> names = new ArrayList<>();
            names.add("META-INF/");
            names.add(JarFile.MANIFEST_NAME);
            for (String directory : directories) if (!directory.equals("META-INF/")) names.add(directory);
            for (String name : entries.keySet()) names.add(name);
            names.sort(Comparator.comparing((String n) -> n.equals("META-INF/") ? 0 : n.equals(JarFile.MANIFEST_NAME) ? 1 : 2)
                    .thenComparing(n -> n));
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(ZIP_TIME);
                out.putNextEntry(entry);
                if (!name.endsWith("/")) out.write(ordered.get(name));
                out.closeEntry();
            }
        }
    }

    /** Entries a fat jar must not carry from its parts. */
    private static boolean dropped(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF") || upper.equals("META-INF/INDEX.LIST")) return true;
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) return true;
        if (upper.startsWith("META-INF/") && upper.indexOf('/', 9) < 0
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"))) {
            return true;
        }
        return false;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(p);
        }
    }
}
