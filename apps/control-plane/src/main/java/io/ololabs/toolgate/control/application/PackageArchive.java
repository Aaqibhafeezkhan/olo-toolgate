// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** The `.tgpkg` rules Control applies on upload, the same as the CLI's `package::validate` (plan.md §7.2, §7.6): only
 *  what the packager could have written (regular stored or deflated entries back to back, then the central directory and
 *  an end record with no comment), the exact file set against a canonical CHECKSUMS.sha256, artifact digests, every
 *  descriptor digest, the canonical `tools/` files and the descriptor set digest. Everything is checked from the central
 *  directory before any entry is inflated. */
public final class PackageArchive {
    public static final String CHECKSUMS = "CHECKSUMS.sha256", MANIFEST = "toolgate-package.json";
    public static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096, MAX_TOOLS = 64, MAX_DESCRIPTOR_BYTES = 1024 * 1024, MAX_RATIO = 100, RATIO_FLOOR = 1024 * 1024;
    private static final int LOCAL = 0x04034b50, CENTRAL = 0x02014b50, END = 0x06054b50, DESCRIPTOR = 0x08074b50;

    /** A package that passed every check. */
    public record Verified(Map<String, byte[]> files, JsonNode manifest, String packageDigest, String descriptorSetDigest) {}

    private PackageArchive() {}
    private static Failure reject() { return Failure.validation(); }

    public static Verified validate(byte[] data, java.util.function.Function<String, JsonNode> json) {
        if (data.length > MAX_PACKAGE_BYTES) throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.VALIDATION, 413, "Package too large");
        var files = read(data);
        var checksums = files.get(CHECKSUMS);
        if (checksums == null) throw reject();
        var listed = checksums(checksums);
        for (var e : files.entrySet()) {
            if (e.getKey().equals(CHECKSUMS) || e.getKey().startsWith("signatures/")) continue;
            var hex = listed.get(e.getKey());
            if (hex == null || !("sha256:" + hex).equals(Canonical.sha256(e.getValue()))) throw reject();
        }
        for (var path : listed.keySet()) if (!files.containsKey(path)) throw reject();
        var manifestBytes = files.get(MANIFEST);
        if (manifestBytes == null) throw reject();
        JsonNode manifest = json.apply(new String(manifestBytes, StandardCharsets.UTF_8));
        if (!manifest.isObject() || manifest.path("schemaVersion").asInt() != 2 || !manifest.path("id").isTextual() || !manifest.path("version").isTextual()) throw reject();
        String packageId = manifest.get("id").asText();
        if (!packageId.matches("^[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}$")) throw reject();

        var runtimes = new HashSet<String>();
        var java = new HashSet<String>(); var jars = new HashSet<String>();
        for (var runtime : manifest.path("runtimes")) {runtimes.add(runtime.path("runtimeId").asText());if ("java".equals(runtime.path("language").asText()))java.add(runtime.path("runtimeId").asText());}
        var artifactIds = new HashSet<String>(); var artifactPaths = new HashSet<String>();
        for (var artifact : manifest.path("artifacts")) {
            String id = artifact.path("artifactId").asText(), path = artifact.path("path").asText();
            if (id.isEmpty() || !artifactIds.add(id) || !artifactPaths.add(path) || !path.startsWith("artifacts/")) throw reject();
            var hex = listed.get(path);
            if (hex == null || !artifact.path("artifactDigest").asText().equals("sha256:" + hex)) throw reject();
            if (artifact.has("sizeBytes") && artifact.get("sizeBytes").asLong() != files.get(path).length) throw reject();
            if (artifact.has("runtimeId") && !runtimes.contains(artifact.get("runtimeId").asText())) throw reject();
            if ("application/java-archive".equals(artifact.path("mediaType").asText())) jars.add(id);
        }
        var limits = manifest.path("limits");
        long maxTools = Math.min(limits.path("maxTools").asLong(0), MAX_TOOLS), maxBytes = Math.min(limits.path("maxDescriptorBytes").asLong(0), MAX_DESCRIPTOR_BYTES);
        var tools = manifest.path("tools");
        if (!tools.isArray() || tools.size() > maxTools) throw reject();
        var generation = manifest.path("sdk").path("descriptorGeneration");
        var digests = new TreeMap<String, String>(); var toolFiles = new HashSet<String>();
        for (var tool : tools) {
            var descriptor = tool.path("descriptor");
            String toolId = descriptor.path("toolId").asText();
            int slash = toolId.indexOf('/');
            if (slash < 0 || !toolId.substring(0, slash).equals(packageId)) throw reject();
            if (!descriptor.path("descriptorGeneration").equals(generation)) throw reject();
            byte[] canonical = Canonical.bytes(descriptor);
            if (canonical.length > maxBytes) throw reject();
            String digest = Canonical.sha256(canonical);
            if (!digest.equals(tool.path("toolDescriptorDigest").asText())) throw reject();
            String file = "tools/" + toolId.substring(slash + 1) + ".json";
            if (!Arrays.equals(files.get(file), canonical)) throw reject();
            toolFiles.add(file);
            for (var id : tool.path("artifactIds")) if (!artifactIds.contains(id.asText())) throw reject();
            if (tool.has("runtimeId") && !runtimes.contains(tool.get("runtimeId").asText())) throw reject();
            // A Java tool is one self-contained jar, so a device needs only a JRE to run it.
            if (java.contains(tool.path("runtimeId").asText()) && (tool.path("artifactIds").size() != 1 || !jars.contains(tool.path("artifactIds").get(0).asText()))) throw reject();
            if (digests.put(toolId, digest) != null) throw reject();
        }
        for (var path : files.keySet()) if (path.startsWith("tools/") && !toolFiles.contains(path)) throw reject();
        var set = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        digests.forEach((id, d) -> set.addObject().put("toolId", id).put("toolDescriptorDigest", d)); // TreeMap: UTF-16 order
        String setDigest = Canonical.digest(set);
        if (!setDigest.equals(manifest.path("descriptorSetDigest").asText())) throw reject();
        return new Verified(files, manifest, Canonical.sha256(checksums), setDigest);
    }

    /** Canonical CHECKSUMS.sha256: UTF-8, LF only, `<64 lowercase hex>  <NFC path>` per line, sorted bytewise. */
    static Map<String, String> checksums(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes) || text.isEmpty() || !text.endsWith("\n") || text.contains("\r")) throw reject();
        var listed = new LinkedHashMap<String, String>(); byte[] previous = null;
        for (var line : text.substring(0, text.length() - 1).split("\n", -1)) {
            int split = line.indexOf("  ");
            if (split != 64 || !line.substring(0, 64).matches("[0-9a-f]{64}")) throw reject();
            String path = line.substring(66); name(path);
            if (path.equals(CHECKSUMS) || path.startsWith("signatures/")) throw reject();
            byte[] raw = path.getBytes(StandardCharsets.UTF_8);
            if (previous != null && Arrays.compareUnsigned(previous, raw) >= 0) throw reject();
            previous = raw; listed.put(path, line.substring(0, 64));
        }
        return listed;
    }

    static void name(String name) {
        if (name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 512 || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw reject();
        if (name.chars().anyMatch(Character::isISOControl)) throw reject();
        for (var segment : name.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw reject();
        if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) throw reject();
    }

    private static int u16(byte[] d, long at) { if (at < 0 || at + 2 > d.length) throw reject(); int i = (int) at; return (d[i] & 0xff) | (d[i + 1] & 0xff) << 8; }
    private static long u32(byte[] d, long at) { if (at < 0 || at + 4 > d.length) throw reject(); int i = (int) at; return (d[i] & 0xffL) | (d[i + 1] & 0xffL) << 8 | (d[i + 2] & 0xffL) << 16 | (d[i + 3] & 0xffL) << 24; }
    private record Entry(String name, int flags, int method, long crc, long compressed, long size, long offset) {}

    static Map<String, byte[]> read(byte[] data) {
        if (data.length < 22) throw reject();
        int end = data.length - 22;
        if (u32(data, end) != END || u16(data, end + 20) != 0 || u16(data, end + 4) != 0 || u16(data, end + 6) != 0) throw reject();
        int count = u16(data, end + 10);
        if (u16(data, end + 8) != count || count > MAX_ENTRIES) throw reject();
        long cdSize = u32(data, end + 12), cdOffset = u32(data, end + 16);
        if (cdOffset + cdSize != end) throw reject();
        var entries = new ArrayList<Entry>(); long at = cdOffset; var folded = new HashSet<String>(); long total = 0;
        for (int i = 0; i < count; i++) {
            if (u32(data, at) != CENTRAL) throw reject();
            int madeBy = u16(data, at + 4), flags = u16(data, at + 8), method = u16(data, at + 10);
            long crc = u32(data, at + 16), compressed = u32(data, at + 20), size = u32(data, at + 24);
            int nameLength = u16(data, at + 28), extra = u16(data, at + 30), comment = u16(data, at + 32);
            long external = u32(data, at + 38), offset = u32(data, at + 42);
            if (at + 46 + nameLength > data.length) throw reject();
            var raw = Arrays.copyOfRange(data, (int) at + 46, (int) at + 46 + nameLength);
            var decoder = StandardCharsets.UTF_8.newDecoder();
            String name;
            try { name = decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString(); } catch (java.nio.charset.CharacterCodingException e) { throw reject(); }
            if ((flags & 0x1) != 0 || (flags & 0x40) != 0 || (flags & 0x2000) != 0 || (method != 0 && method != 8)) throw reject();
            if (compressed == 0xffffffffL || size == 0xffffffffL || offset == 0xffffffffL) throw reject();
            long kind = (external >> 16) & 0170000;
            if ((madeBy >> 8) == 3 && kind != 0 && kind != 0100000) throw reject();
            if ((external & 0x10) != 0 || name.endsWith("/")) throw reject();
            name(name);
            if (!folded.add(Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFC))) throw reject();
            if (method == 0 && compressed != size) throw reject();
            if (size > RATIO_FLOOR && size > compressed * MAX_RATIO) throw reject();
            total += size;
            if (total > MAX_PACKAGE_BYTES * 4L) throw reject();
            entries.add(new Entry(name, flags, method, crc, compressed, size, offset));
            at += 46 + nameLength + extra + comment;
        }
        if (at != end) throw reject();
        var files = new TreeMap<String, byte[]>(); long expected = 0;
        for (var entry : entries) {
            if (entry.offset() != expected) throw reject();
            long start = entry.offset();
            if (u32(data, start) != LOCAL) throw reject();
            int localFlags = u16(data, start + 6), localMethod = u16(data, start + 8), localName = u16(data, start + 26), localExtra = u16(data, start + 28);
            if (start + 30 + localName > data.length) throw reject();
            if (localFlags != entry.flags() || localMethod != entry.method()
                || !Arrays.equals(Arrays.copyOfRange(data, (int) start + 30, (int) start + 30 + localName), entry.name().getBytes(StandardCharsets.UTF_8))) throw reject();
            boolean descriptor = (entry.flags() & 0x8) != 0;
            if (!descriptor && (u32(data, start + 14) != entry.crc() || u32(data, start + 18) != entry.compressed() || u32(data, start + 22) != entry.size())) throw reject();
            long body = start + 30 + localName + localExtra, bodyEnd = body + entry.compressed();
            if (bodyEnd > cdOffset) throw reject();
            long next = bodyEnd;
            if (descriptor) next += u32(data, bodyEnd) == DESCRIPTOR ? 16 : 12;
            expected = next;
            byte[] bytes = inflate(entry, data, (int) body, (int) entry.compressed());
            var crc = new java.util.zip.CRC32(); crc.update(bytes);
            if (crc.getValue() != entry.crc()) throw reject();
            files.put(entry.name(), bytes);
        }
        if (expected != cdOffset) throw reject();
        return files;
    }

    private static byte[] inflate(Entry entry, byte[] data, int offset, int length) {
        if (entry.method() == 0) return Arrays.copyOfRange(data, offset, offset + length);
        var inflater = new java.util.zip.Inflater(true);
        try {
            inflater.setInput(data, offset, length);
            var out = new byte[(int) entry.size() + 1]; int n = 0;
            while (!inflater.finished() && n < out.length) { int read = inflater.inflate(out, n, out.length - n); if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break; n += read; }
            if (n != entry.size() || !inflater.finished()) throw reject();
            return Arrays.copyOf(out, n);
        } catch (java.util.zip.DataFormatException e) { throw reject(); }
        finally { inflater.end(); }
    }
}
