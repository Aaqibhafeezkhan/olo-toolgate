// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Test packages built with the real {@code toolgate pack}, plus a zip writer for malformed variants. */
final class Fixtures {
    private Fixtures() {
    }

    static Path toolgateDir() {
        String dir = System.getenv("TOOLGATE_DIR");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("TOOLGATE_DIR is not set; run through marketplace/ci/run.sh");
        }
        return Path.of(dir);
    }

    static Path marketplaceDir() {
        String dir = System.getenv("MARKETPLACE_DIR");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("MARKETPLACE_DIR is not set; run through marketplace/ci/run.sh");
        }
        return Path.of(dir);
    }

    static Path cli() {
        String cli = System.getenv("TOOLGATE_CLI");
        if (cli == null || cli.isBlank() || !Files.isExecutable(Path.of(cli))) {
            throw new IllegalStateException("TOOLGATE_CLI is not set to the toolgate binary; run through marketplace/ci/run.sh");
        }
        return Path.of(cli);
    }

    static Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("helper-test-");
        dir.toFile().deleteOnExit();
        return dir;
    }

    /** A config for tests: a file root, a test key and the real CLI. */
    static Config config(Path fileRoot, long maxPackageBytes, long maxExpandedBytes) throws Exception {
        return new Config("test-marketplace", "test-token-0123456789", keyFile(), "test-key-1", "", cli(),
                maxPackageBytes, maxExpandedBytes, Duration.ofSeconds(30), 0, 2, Config.DEFAULT_RESERVED,
                Set.of("examples"), null, "us-east-1", null, null, null, fileRoot, "test-revision");
    }

    static Config config(Path fileRoot) throws Exception {
        return config(fileRoot, 50L * 1024 * 1024, 200L * 1024 * 1024);
    }

    private static Path key;

    /** A PKCS#8 PEM Ed25519 key, like scripts/init-signing-key.sh writes. */
    static synchronized Path keyFile() throws Exception {
        if (key == null) {
            byte[] pkcs8 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(pkcs8) + "\n-----END PRIVATE KEY-----\n";
            key = tempDir().resolve("signing.pem");
            Files.writeString(key, pem);
        }
        return key;
    }

    static Signer signer(Config config) {
        return Signer.load(config.signingKeyFile(), config.signingKeyId(), config.retiredKeys());
    }

    static Inspector inspector(Config config) {
        return new Inspector(config, new CliValidator(config), signer(config));
    }

    /** The template manifest (examples.notify renamed to acme.notify, digests removed), edited by {@code change}. */
    @SuppressWarnings("unchecked")
    static byte[] pack(Consumer<Map<String, Object>> change, byte[] jar) throws Exception {
        Map<String, Object> manifest;
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixture-manifest.json")) {
            manifest = (Map<String, Object>) Json.parse(in.readAllBytes());
        }
        change.accept(manifest);
        Path staging = tempDir();
        Files.createDirectories(staging.resolve("artifacts"));
        Files.writeString(staging.resolve("toolgate-package.json"), Json.write(manifest));
        Files.write(staging.resolve("artifacts/tool.jar"), jar);
        Path out = staging.resolve("out.tgpkg");
        Process p = new ProcessBuilder(cli().toString(), "pack", staging.toString(), "-o", out.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("toolgate pack failed: " + output);
        }
        return Files.readAllBytes(out);
    }

    static byte[] pack(Consumer<Map<String, Object>> change) throws Exception {
        return pack(change, "not really a jar".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] valid() throws Exception {
        return pack(m -> { });
    }

    /** Renames the package (and its tool ids) to {@code id}. */
    static Consumer<Map<String, Object>> id(String id) {
        return m -> {
            m.put("id", id);
            for (Object tool : Json.arr(m.get("tools"))) {
                Map<String, Object> d = Json.obj(Json.obj(tool).get("descriptor"));
                String toolId = (String) d.get("toolId");
                d.put("toolId", id + toolId.substring(toolId.indexOf('/')));
            }
        };
    }

    static Map<String, Object> descriptor(Map<String, Object> manifest) {
        return Json.obj(Json.obj(Json.arr(manifest.get("tools")).get(0)).get("descriptor"));
    }

    static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                files.put(e.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    static String sha256(byte[] bytes) {
        return Inspector.sha256(bytes);
    }

    /** The canonical CHECKSUMS.sha256 for a file set, as the packager writes it. */
    static byte[] checksums(Map<String, byte[]> files) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, byte[]> e : new TreeMap<>(files).entrySet()) {
            if (!e.getKey().equals("CHECKSUMS.sha256")) {
                out.append(sha256(e.getValue())).append("  ").append(e.getKey()).append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One zip entry: name, bytes, Unix mode and whether to deflate it. */
    record Entry(String name, byte[] bytes, int mode, boolean deflate) {
        Entry(String name, byte[] bytes) {
            this(name, bytes, 0100644, false);
        }
    }

    static List<Entry> entries(Map<String, byte[]> files) {
        return new TreeMap<>(files).entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
    }

    /** Writes entries in order the way the ToolGate packager does, without its name checks. */
    static byte[] zip(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            byte[] body = entry.bytes();
            int method = 0;
            if (entry.deflate()) {
                Deflater deflater = new Deflater(9, true);
                deflater.setInput(body);
                deflater.finish();
                ByteArrayOutputStream d = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!deflater.finished()) {
                    d.write(buf, 0, deflater.deflate(buf));
                }
                body = d.toByteArray();
                method = 8;
            }
            CRC32 crc = new CRC32();
            crc.update(entry.bytes());
            int offset = out.size();
            ByteBuffer local = le(30);
            local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) method)
                    .putShort((short) 0).putShort((short) 33).putInt((int) crc.getValue()).putInt(body.length)
                    .putInt(entry.bytes().length).putShort((short) name.length).putShort((short) 0);
            out.writeBytes(local.array());
            out.writeBytes(name);
            out.writeBytes(body);
            ByteBuffer c = le(46);
            c.putInt(0x02014b50).putShort((short) ((3 << 8) | 20)).putShort((short) 20).putShort((short) 0)
                    .putShort((short) method).putShort((short) 0).putShort((short) 33).putInt((int) crc.getValue())
                    .putInt(body.length).putInt(entry.bytes().length).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(entry.mode() << 16).putInt(offset);
            central.writeBytes(c.array());
            central.writeBytes(name);
        }
        int cdOffset = out.size();
        out.writeBytes(central.toByteArray());
        ByteBuffer end = le(22);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) entries.size())
                .putShort((short) entries.size()).putInt(central.size()).putInt(cdOffset).putShort((short) 0);
        out.writeBytes(end.array());
        return out.toByteArray();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
