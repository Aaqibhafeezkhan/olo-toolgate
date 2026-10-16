// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Deterministic client-tool image data (ADR 026): one layer holding the verified artifact, the base config with that
 *  layer appended, and manifests/index addressed by digest. Pure functions; nothing here runs or parses author code. */
public final class OciImages {
    public static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json", OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json",
        OCI_CONFIG = "application/vnd.oci.image.config.v1+json", OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip",
        DOCKER_LIST = "application/vnd.docker.distribution.manifest.list.v2+json", DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json",
        DOCKER_CONFIG = "application/vnd.docker.container.image.v1+json", DOCKER_LAYER = "application/vnd.docker.image.rootfs.diff.tar.gzip";
    public static final String EPOCH = "1970-01-01T00:00:00Z";
    /** The sandbox user and working directory the client runs every managed tool with. */
    public static final String USER = "65532:65532", WORKDIR = "/work";
    private static final ObjectMapper JSON = new ObjectMapper();
    private OciImages() {}

    public record Layer(byte[] gzip, String digest, String diffId) {}
    public record Blob(String digest, byte[] bytes) {}

    public static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Stable bytes: keys sorted, no insignificant whitespace. */
    public static byte[] bytes(JsonNode value) {
        try { return JSON.writeValueAsBytes(sorted(value)); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) { var out = JSON.createObjectNode(); new TreeSet<String>(iterable(value.fieldNames())).forEach(k -> out.set(k, sorted(value.get(k)))); return out; }
        if (value.isArray()) { var out = JSON.createArrayNode(); value.forEach(v -> out.add(sorted(v))); return out; }
        return value;
    }
    private static <T> List<T> iterable(Iterator<T> it) { var out = new ArrayList<T>(); it.forEachRemaining(out::add); return out; }

    /** The artifact at {@code /opt/tool/<packageId>/tool.jar}: root-owned, read-only, zero timestamps. */
    public static Layer layer(String packageId, byte[] jar) {
        if (!packageId.matches("[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}")) throw Failure.validation();
        var tar = new ByteArrayOutputStream();
        entry(tar, "opt/", 0755, '5', new byte[0]);
        entry(tar, "opt/tool/", 0755, '5', new byte[0]);
        entry(tar, "opt/tool/" + packageId + "/", 0755, '5', new byte[0]);
        entry(tar, "opt/tool/" + packageId + "/tool.jar", 0444, '0', jar);
        tar.writeBytes(new byte[1024]);
        byte[] raw = tar.toByteArray();
        var zipped = new ByteArrayOutputStream();
        // java.util.zip writes a fixed header (no name, mtime 0), so equal input gives equal bytes.
        try (var gzip = new GZIPOutputStream(zipped)) { gzip.write(raw); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        byte[] gz = zipped.toByteArray();
        return new Layer(gz, sha256(gz), sha256(raw));
    }

    private static void entry(ByteArrayOutputStream out, String path, int mode, char type, byte[] content) {
        byte[] header = new byte[512];
        byte[] name = path.getBytes(StandardCharsets.US_ASCII);
        if (name.length > 255) throw Failure.validation();
        if (name.length <= 100) System.arraycopy(name, 0, header, 0, name.length);
        else {
            int split = path.lastIndexOf('/', Math.min(path.length() - 2, 155));
            if (split <= 0 || path.length() - split - 1 > 100) throw Failure.validation();
            byte[] prefix = path.substring(0, split).getBytes(StandardCharsets.US_ASCII), rest = path.substring(split + 1).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(rest, 0, header, 0, rest.length); System.arraycopy(prefix, 0, header, 345, prefix.length);
        }
        octal(header, 100, 8, mode); octal(header, 108, 8, 0); octal(header, 116, 8, 0);
        octal(header, 124, 12, content.length); octal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0'; header[264] = '0';
        System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, header, 265, 4);
        System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, header, 297, 4);
        int sum = 0; for (byte b : header) sum += b & 0xff;
        String checksum = String.format("%06o", sum);
        System.arraycopy(checksum.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 6); header[154] = 0; header[155] = ' ';
        out.writeBytes(header); out.writeBytes(content);
        int pad = (512 - content.length % 512) % 512; out.writeBytes(new byte[pad]);
    }
    private static void octal(byte[] header, int offset, int length, long value) {
        String text = String.format("%0" + (length - 1) + "o", value);
        if (text.length() != length - 1) throw Failure.validation();
        System.arraycopy(text.getBytes(StandardCharsets.US_ASCII), 0, header, offset, length - 1);
    }

    /** The base config with the layer appended, a fixed history entry and no entrypoint or command: the client always
     *  supplies the launch vector, user and working directory. */
    public static byte[] config(JsonNode base, Layer layer, String createdBy) {
        var config = (ObjectNode) base.deepCopy();
        config.put("created", EPOCH);
        var runtime = config.path("config").isObject() ? (ObjectNode) config.get("config") : config.putObject("config");
        runtime.remove("Entrypoint"); runtime.remove("Cmd"); runtime.remove("Healthcheck"); runtime.remove("OnBuild");
        runtime.put("User", USER).put("WorkingDir", WORKDIR);
        var rootfs = config.path("rootfs").isObject() ? (ObjectNode) config.get("rootfs") : null;
        if (rootfs == null || !"layers".equals(rootfs.path("type").asText()) || !rootfs.path("diff_ids").isArray()) throw Failure.validation();
        ((ArrayNode) rootfs.get("diff_ids")).add(layer.diffId());
        var history = config.path("history").isArray() ? (ArrayNode) config.get("history") : config.putArray("history");
        history.addObject().put("created", EPOCH).put("created_by", createdBy);
        return bytes(config);
    }

    /** The base manifest with the new config and the layer appended, in the base's media-type family. */
    public static byte[] manifest(JsonNode base, String mediaType, byte[] config, Layer layer) {
        boolean docker = DOCKER_MANIFEST.equals(mediaType);
        if (!docker && !OCI_MANIFEST.equals(mediaType)) throw Failure.validation();
        var manifest = JSON.createObjectNode().put("schemaVersion", 2).put("mediaType", mediaType);
        manifest.putObject("config").put("mediaType", docker ? DOCKER_CONFIG : OCI_CONFIG).put("digest", sha256(config)).put("size", config.length);
        var layers = manifest.putArray("layers");
        for (var l : base.path("layers")) layers.add(l.deepCopy());
        layers.addObject().put("mediaType", docker ? DOCKER_LAYER : OCI_LAYER).put("digest", layer.digest()).put("size", layer.gzip().length);
        return bytes(manifest);
    }

    public record Platform(String os, String architecture, String variant, String digest, int size, String mediaType) {}
    public static byte[] index(List<Platform> platforms) {
        var index = JSON.createObjectNode().put("schemaVersion", 2).put("mediaType", OCI_INDEX);
        var manifests = index.putArray("manifests");
        for (var p : platforms) {
            var entry = manifests.addObject().put("mediaType", p.mediaType()).put("digest", p.digest()).put("size", p.size());
            var platform = entry.putObject("platform").put("architecture", p.architecture()).put("os", p.os());
            if (p.variant() != null) platform.put("variant", p.variant());
        }
        return bytes(index);
    }

    /** The approved JRE's version as the client's version probe reports it: {@code jdk-21.0.12.1+1} becomes {@code 21.0.12+1}. */
    public static String javaVersion(JsonNode config) {
        for (var e : config.path("config").path("Env")) {
            String value = e.asText();
            if (!value.startsWith("JAVA_VERSION=")) continue;
            String version = value.substring(13).replaceFirst("^jdk-?", "").replaceFirst("\\+.*$", "");
            var parts = version.split("\\.");
            if (parts.length == 4 && Arrays.stream(parts).allMatch(p -> p.matches("0|[1-9][0-9]{0,8}"))) return parts[0] + "." + parts[1] + "." + parts[2] + "+" + parts[3];
            if (parts.length == 3 && Arrays.stream(parts).allMatch(p -> p.matches("0|[1-9][0-9]{0,8}"))) return version;
        }
        throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.VALIDATION, 422, "The approved base image does not declare its Java version");
    }
}
