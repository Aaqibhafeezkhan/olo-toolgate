// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.ololabs.toolgate.packagearchive.PackageArchive;
import io.ololabs.toolgate.jsonsdk.JsonProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Immutable build output, verified in full before deployment uploads any package. Contains no grant or activation. */
public final class CorePackageInventory {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "sdkVersion", "packages");
    private static final Set<String> PACKAGE_FIELDS = Set.of("project", "sdk", "id", "version", "artifact", "artifactSha256", "packageDigest");
    public record Artifact(String id, String version, String packageDigest, Path path, String artifactSha256) {
        /** Recheck exact bytes at use, so full-inventory preflight does not retain every package in memory. */
        public byte[] bytes() throws IOException {
            byte[] bytes = read(path, PackageArchive.MAX_PACKAGE_BYTES);
            if (!sha256(bytes).equals(artifactSha256)) throw new IOException("Core package changed after preflight");
            return bytes;
        }
    }
    private CorePackageInventory() {}
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] read(Path file, int maximum) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Regular core package file required");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Core package file exceeds limit");
            return bytes;
        }
    }
    private static Set<String> fields(JsonNode node) { return node != null && node.isObject() ? node.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()) : Set.of(); }
    private static String text(JsonNode node, String key) throws IOException {
        if (!node.path(key).isTextual() || node.path(key).asText().isEmpty()) throw new IOException("Invalid core package field");
        return node.path(key).asText();
    }
    public static List<Artifact> load(Path lock) throws IOException {
        Path absolute = lock.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException("Core package inventory links are forbidden");
        var root = JSON.readTree(read(absolute, 65536));
        if (!fields(root).equals(ROOT_FIELDS) || !root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                || root.path("schemaVersion").intValue() != 1 || !text(root, "sdkVersion").equals(JsonProject.sdkVersion())
                || !root.path("packages").isArray() || root.path("packages").isEmpty() || root.path("packages").size() > 32) throw new IOException("Unsupported core package inventory");
        var result = new ArrayList<Artifact>(); var ids = new HashSet<String>(); var files = new HashSet<String>();
        for (var entry : root.path("packages")) {
            if (!fields(entry).equals(PACKAGE_FIELDS)) throw new IOException("Invalid core package entry");
            String id = text(entry, "id"), version = text(entry, "version"), filename = text(entry, "artifact");
            text(entry, "project"); text(entry, "sdk");
            if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,250}\\.tgpkg") || !ids.add(id) || !files.add(filename)) throw new IOException("Duplicate or unsafe core package");
            Path artifact = absolute.getParent().resolve(filename);
            byte[] bytes = read(artifact, PackageArchive.MAX_PACKAGE_BYTES);
            try {
                String digest = sha256(bytes);
                if (!digest.equals(text(entry, "artifactSha256"))) throw new IOException("Core package artifact digest mismatch");
                var verified = PackageArchive.validate(bytes);
                var manifest = JSON.readTree(verified.manifestBytes());
                if (!id.equals(manifest.path("id").asText()) || !version.equals(manifest.path("version").asText())
                        || !JsonProject.sdkVersion().equals(manifest.path("sdk").path("version").asText())
                        || !verified.packageDigest().equals(text(entry, "packageDigest"))) throw new IOException("Core package identity mismatch");
                result.add(new Artifact(id, version, verified.packageDigest(), artifact, digest));
            } catch (io.ololabs.toolgate.packagearchive.PackageRejected | IllegalArgumentException invalid) { throw new IOException("Core package verification failed", invalid); }
        }
        return List.copyOf(result);
    }
}
