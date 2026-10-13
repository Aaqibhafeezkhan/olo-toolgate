// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** The `.tgpkg` rules Control applies on upload, the same as the CLI's `package::validate` (plan.md §7.2, §7.6): only
 *  what the packager could have written (regular stored or deflated entries back to back, then the central directory and
 *  an end record with no comment), the exact file set against a canonical CHECKSUMS.sha256, artifact digests, every
 *  descriptor digest, the canonical `tools/` files and the descriptor set digest. Everything is checked from the central
 *  directory before any entry is inflated.
 *
 *  <p>This library uses only the JDK, so Control and the Marketplace helper run the same validator. */
public final class PackageArchive {
    public static final String CHECKSUMS = "CHECKSUMS.sha256", MANIFEST = "toolgate-package.json";
    public static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096, MAX_TOOLS = 64, MAX_DESCRIPTOR_BYTES = 1024 * 1024, MAX_RATIO = 100, RATIO_FLOOR = 1024 * 1024;
    private static final int LOCAL = 0x04034b50, CENTRAL = 0x02014b50, END = 0x06054b50, DESCRIPTOR = 0x08074b50;

    /** A package that passed every check. {@code files} is sorted by name; {@code toolDigests} maps each toolId to its
     *  descriptor digest, sorted by UTF-16 code units. */
    public static final class Verified {
        private final SortedMap<String, byte[]> files; private final Json manifest;
        private final String packageDigest, descriptorSetDigest; private final SortedMap<String, String> toolDigests;
        Verified(SortedMap<String, byte[]> files, Json manifest, String packageDigest, String descriptorSetDigest, SortedMap<String, String> toolDigests) {
            this.files = Collections.unmodifiableSortedMap(files); this.manifest = manifest; this.packageDigest = packageDigest;
            this.descriptorSetDigest = descriptorSetDigest; this.toolDigests = Collections.unmodifiableSortedMap(toolDigests);
        }
        public SortedMap<String, byte[]> files() { return files; }
        public Json manifest() { return manifest; }
        /** The exact bytes of toolgate-package.json. */
        public byte[] manifestBytes() { return files.get(MANIFEST); }
        /** {@code sha256:} of CHECKSUMS.sha256. */
        public String packageDigest() { return packageDigest; }
        public String descriptorSetDigest() { return descriptorSetDigest; }
        public SortedMap<String, String> toolDigests() { return toolDigests; }
    }

    private PackageArchive() {}
    private static PackageRejected reject(String reason) { return new PackageRejected(reason); }

    /** Validates a whole package. Throws {@link PackageRejected} for any package that breaks a rule. */
    public static Verified validate(byte[] data) {
        if (data.length > MAX_PACKAGE_BYTES) throw new PackageRejected(413, "Package too large");
        var files = read(data);
        var checksums = files.get(CHECKSUMS);
        if (checksums == null) throw reject(CHECKSUMS + " is missing");
        var listed = checksums(checksums);
        for (var e : files.entrySet()) {
            if (e.getKey().equals(CHECKSUMS) || e.getKey().startsWith("signatures/")) continue;
            var hex = listed.get(e.getKey());
            if (hex == null) throw reject("not listed in " + CHECKSUMS + ": " + e.getKey());
            if (!("sha256:" + hex).equals(Canonical.sha256(e.getValue()))) throw reject("digest differs from " + CHECKSUMS + ": " + e.getKey());
        }
        for (var path : listed.keySet()) if (!files.containsKey(path)) throw reject("listed in " + CHECKSUMS + " but missing: " + path);
        var manifestBytes = files.get(MANIFEST);
        if (manifestBytes == null) throw reject(MANIFEST + " is missing");
        Json manifest;
        // Decoded as Control always has: bytes that are not UTF-8 become U+FFFD.
        try { manifest = Json.parse(new String(manifestBytes, StandardCharsets.UTF_8)); }
        catch (Json.ParseException e) { throw reject(MANIFEST + " is not strict JSON: " + e.getMessage()); }
        if (!manifest.isObject() || manifest.path("schemaVersion").asInt() != 2 || !manifest.path("id").isTextual() || !manifest.path("version").isTextual())
            throw reject(MANIFEST + " is not a schemaVersion 2 manifest with a text id and version");
        String packageId = manifest.get("id").asText();
        if (!packageId.matches("^[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}$")) throw reject("not a package id: " + packageId);

        var runtimes = new HashSet<String>();
        var java = new HashSet<String>(); var jars = new HashSet<String>();
        for (var runtime : manifest.path("runtimes")) { runtimes.add(runtime.path("runtimeId").asText()); if ("java".equals(runtime.path("language").asText())) java.add(runtime.path("runtimeId").asText()); }
        var artifactIds = new HashSet<String>(); var artifactPaths = new HashSet<String>();
        for (var artifact : manifest.path("artifacts")) {
            String id = artifact.path("artifactId").asText(), path = artifact.path("path").asText();
            if (id.isEmpty() || !artifactIds.add(id) || !artifactPaths.add(path)) throw reject("artifact without an id, or a duplicate artifact: " + id);
            if (!path.startsWith("artifacts/")) throw reject("artifact path must be under artifacts/: " + path);
            var hex = listed.get(path);
            if (hex == null || !artifact.path("artifactDigest").asText().equals("sha256:" + hex)) throw reject("manifest artifact digest differs from " + CHECKSUMS + ": " + path);
            if (artifact.has("sizeBytes") && artifact.get("sizeBytes").asLong() != files.get(path).length) throw reject("manifest artifact size differs: " + path);
            if (artifact.has("runtimeId") && !runtimes.contains(artifact.get("runtimeId").asText())) throw reject("artifact names an undeclared runtime: " + id);
            if ("application/java-archive".equals(artifact.path("mediaType").asText())) jars.add(id);
        }
        var limits = manifest.path("limits");
        long maxTools = Math.min(limits.path("maxTools").asLong(0), MAX_TOOLS), maxBytes = Math.min(limits.path("maxDescriptorBytes").asLong(0), MAX_DESCRIPTOR_BYTES);
        var tools = manifest.path("tools");
        if (!tools.isArray()) throw reject("tools is not an array");
        if (tools.size() > maxTools) throw reject(tools.size() + " tools exceed maxTools " + maxTools);
        var generation = manifest.path("sdk").path("descriptorGeneration");
        var digests = new TreeMap<String, String>(); var toolFiles = new HashSet<String>();
        for (var tool : tools) {
            var descriptor = tool.path("descriptor");
            String toolId = descriptor.path("toolId").asText();
            int slash = toolId.indexOf('/');
            if (slash < 0 || !toolId.substring(0, slash).equals(packageId)) throw reject("toolId is not in package " + packageId + ": " + toolId);
            if (!descriptor.path("descriptorGeneration").equals(generation)) throw reject(toolId + ": descriptorGeneration differs from sdk.descriptorGeneration");
            byte[] canonical;
            try { canonical = Canonical.bytes(descriptor); }
            catch (IllegalArgumentException e) { throw reject(toolId + ": the descriptor has no canonical form: " + e.getMessage()); }
            if (canonical.length > maxBytes) throw reject(toolId + ": descriptor is over maxDescriptorBytes");
            String digest = Canonical.sha256(canonical);
            if (!digest.equals(tool.path("toolDescriptorDigest").asText())) throw reject(toolId + ": toolDescriptorDigest does not match the descriptor");
            String file = "tools/" + toolId.substring(slash + 1) + ".json";
            if (!Arrays.equals(files.get(file), canonical)) throw reject(file + " is missing or not the canonical descriptor");
            toolFiles.add(file);
            for (var id : tool.path("artifactIds")) if (!artifactIds.contains(id.asText())) throw reject(toolId + ": unknown artifact " + id.asText());
            if (tool.has("runtimeId") && !runtimes.contains(tool.get("runtimeId").asText())) throw reject(toolId + ": undeclared runtime");
            // A Java tool is one self-contained jar, so a device needs only a JRE to run it.
            if (java.contains(tool.path("runtimeId").asText()) && (tool.path("artifactIds").size() != 1 || !jars.contains(tool.path("artifactIds").path(0).asText())))
                throw reject(toolId + ": a Java tool must run from one self-contained jar");
            if (digests.put(toolId, digest) != null) throw reject("two tools share a toolId: " + toolId);
        }
        for (var path : files.keySet()) if (path.startsWith("tools/") && !toolFiles.contains(path)) throw reject(path + " matches no tool in the manifest");
        var set = new ArrayList<Json>();
        digests.forEach((id, d) -> { var entry = new LinkedHashMap<String, Json>(); entry.put("toolId", Json.string(id)); entry.put("toolDescriptorDigest", Json.string(d)); set.add(Json.object(entry)); }); // TreeMap: UTF-16 order
        String setDigest = Canonical.digest(Json.array(set));
        if (!setDigest.equals(manifest.path("descriptorSetDigest").asText())) throw reject("descriptorSetDigest does not match the tools");
        return new Verified(files, manifest, Canonical.sha256(checksums), setDigest, digests);
    }

    /** Canonical CHECKSUMS.sha256: UTF-8, LF only, `<64 lowercase hex>  <NFC path>` per line, sorted bytewise. */
    static Map<String, String> checksums(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes) || text.isEmpty() || !text.endsWith("\n") || text.contains("\r"))
            throw reject(CHECKSUMS + " is not LF-terminated canonical text");
        var listed = new LinkedHashMap<String, String>(); byte[] previous = null;
        for (var line : text.substring(0, text.length() - 1).split("\n", -1)) {
            int split = line.indexOf("  ");
            if (split != 64 || !line.substring(0, 64).matches("[0-9a-f]{64}")) throw reject(CHECKSUMS + " line is not `<64 lowercase hex>  <path>`");
            String path = line.substring(66); name(path);
            if (path.equals(CHECKSUMS) || path.startsWith("signatures/")) throw reject(CHECKSUMS + " must not list " + path);
            byte[] raw = path.getBytes(StandardCharsets.UTF_8);
            if (previous != null && Arrays.compareUnsigned(previous, raw) >= 0) throw reject(CHECKSUMS + " is not sorted bytewise without duplicates");
            previous = raw; listed.put(path, line.substring(0, 64));
        }
        return listed;
    }

    static void name(String name) {
        if (name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 512 || name.startsWith("/") || name.contains("\\") || name.contains(":")
            || name.chars().anyMatch(Character::isISOControl)) throw reject("unsafe entry path");
        for (var segment : name.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw reject("unsafe entry path");
        if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) throw reject("entry path is not NFC");
    }

    private static int u16(byte[] d, long at) { if (at < 0 || at + 2 > d.length) throw reject("archive is truncated"); int i = (int) at; return (d[i] & 0xff) | (d[i + 1] & 0xff) << 8; }
    private static long u32(byte[] d, long at) { if (at < 0 || at + 4 > d.length) throw reject("archive is truncated"); int i = (int) at; return (d[i] & 0xffL) | (d[i + 1] & 0xffL) << 8 | (d[i + 2] & 0xffL) << 16 | (d[i + 3] & 0xffL) << 24; }

    private static final class Entry {
        final String name; final int flags, method; final long crc, compressed, size, offset;
        Entry(String name, int flags, int method, long crc, long compressed, long size, long offset) {
            this.name = name; this.flags = flags; this.method = method; this.crc = crc; this.compressed = compressed; this.size = size; this.offset = offset;
        }
    }

    static SortedMap<String, byte[]> read(byte[] data) {
        if (data.length < 22) throw reject("not a zip archive");
        int end = data.length - 22;
        if (u32(data, end) != END || u16(data, end + 20) != 0 || u16(data, end + 4) != 0 || u16(data, end + 6) != 0)
            throw reject("archive does not end with an end-of-central-directory record and no comment");
        int count = u16(data, end + 10);
        if (u16(data, end + 8) != count || count > MAX_ENTRIES) throw reject("too many entries, or a multi-disk archive");
        long cdSize = u32(data, end + 12), cdOffset = u32(data, end + 16);
        if (cdOffset + cdSize != end) throw reject("the central directory does not end at the end record");
        var entries = new ArrayList<Entry>(); long at = cdOffset; var folded = new HashSet<String>(); long total = 0;
        for (int i = 0; i < count; i++) {
            if (u32(data, at) != CENTRAL) throw reject("bad central directory header");
            int madeBy = u16(data, at + 4), flags = u16(data, at + 8), method = u16(data, at + 10);
            long crc = u32(data, at + 16), compressed = u32(data, at + 20), size = u32(data, at + 24);
            int nameLength = u16(data, at + 28), extra = u16(data, at + 30), comment = u16(data, at + 32);
            long external = u32(data, at + 38), offset = u32(data, at + 42);
            if (at + 46 + nameLength > data.length) throw reject("archive is truncated");
            var raw = Arrays.copyOfRange(data, (int) at + 46, (int) at + 46 + nameLength);
            String name;
            try { name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString(); } catch (CharacterCodingException e) { throw reject("entry name is not UTF-8"); }
            if ((flags & 0x1) != 0 || (flags & 0x40) != 0 || (flags & 0x2000) != 0 || (method != 0 && method != 8)) throw reject("encrypted entry or unsupported method: " + name);
            if (compressed == 0xffffffffL || size == 0xffffffffL || offset == 0xffffffffL) throw reject("ZIP64 entry: " + name);
            long kind = (external >> 16) & 0170000;
            if ((madeBy >> 8) == 3 && kind != 0 && kind != 0100000) throw reject("entry is not a regular file: " + name);
            if ((external & 0x10) != 0 || name.endsWith("/")) throw reject("directory entry: " + name);
            name(name);
            if (!folded.add(Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFC))) throw reject("entry names collide: " + name);
            if (method == 0 && compressed != size) throw reject("stored entry sizes differ: " + name);
            if (size > RATIO_FLOOR && size > compressed * MAX_RATIO) throw reject("entry is over the compression-ratio limit: " + name);
            total += size;
            if (total > MAX_PACKAGE_BYTES * 4L) throw reject("package is over the uncompressed size limit");
            entries.add(new Entry(name, flags, method, crc, compressed, size, offset));
            at += 46 + nameLength + extra + comment;
        }
        if (at != end) throw reject("the central directory has trailing bytes");
        var files = new TreeMap<String, byte[]>(); long expected = 0;
        for (var entry : entries) {
            if (entry.offset != expected) throw reject("entries are not back to back: " + entry.name);
            long start = entry.offset;
            if (u32(data, start) != LOCAL) throw reject("bad local header: " + entry.name);
            int localFlags = u16(data, start + 6), localMethod = u16(data, start + 8), localName = u16(data, start + 26), localExtra = u16(data, start + 28);
            if (start + 30 + localName > data.length) throw reject("archive is truncated");
            if (localFlags != entry.flags || localMethod != entry.method
                || !Arrays.equals(Arrays.copyOfRange(data, (int) start + 30, (int) start + 30 + localName), entry.name.getBytes(StandardCharsets.UTF_8)))
                throw reject("local header differs from the central directory: " + entry.name);
            boolean descriptor = (entry.flags & 0x8) != 0;
            if (!descriptor && (u32(data, start + 14) != entry.crc || u32(data, start + 18) != entry.compressed || u32(data, start + 22) != entry.size))
                throw reject("local header differs from the central directory: " + entry.name);
            long body = start + 30 + localName + localExtra, bodyEnd = body + entry.compressed;
            if (bodyEnd > cdOffset) throw reject("entry overlaps the central directory: " + entry.name);
            long next = bodyEnd;
            if (descriptor) next += u32(data, bodyEnd) == DESCRIPTOR ? 16 : 12;
            expected = next;
            byte[] bytes = inflate(entry, data, (int) body, (int) entry.compressed);
            var crc = new CRC32(); crc.update(bytes);
            if (crc.getValue() != entry.crc) throw reject("CRC mismatch: " + entry.name);
            files.put(entry.name, bytes);
        }
        if (expected != cdOffset) throw reject("bytes between the last entry and the central directory");
        return files;
    }

    private static byte[] inflate(Entry entry, byte[] data, int offset, int length) {
        if (entry.method == 0) return Arrays.copyOfRange(data, offset, offset + length);
        var inflater = new Inflater(true);
        try {
            inflater.setInput(data, offset, length);
            var out = new byte[(int) entry.size + 1]; int n = 0;
            while (!inflater.finished() && n < out.length) { int read = inflater.inflate(out, n, out.length - n); if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break; n += read; }
            if (n != entry.size || !inflater.finished()) throw reject("entry does not inflate to its size: " + entry.name);
            return Arrays.copyOf(out, n);
        } catch (DataFormatException e) { throw reject("entry does not inflate: " + entry.name); }
        finally { inflater.end(); }
    }
}
