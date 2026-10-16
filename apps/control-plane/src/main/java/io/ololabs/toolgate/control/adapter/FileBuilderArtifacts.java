// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.BuilderArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deployment-owned directory belongs in the data backup. Names come only from verified SHA-256 bytes. */
public final class FileBuilderArtifacts implements BuilderArtifacts {
    private final Path root;
    private static final int MAXIMUM = io.ololabs.toolgate.packagearchive.PackageArchive.MAX_PACKAGE_BYTES;
    public FileBuilderArtifacts(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Builder artifact directory");
        this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    private static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Path path(String digest) throws IOException {
        if (digest == null || !digest.matches("sha256:[a-f0-9]{64}")) throw new IOException("Invalid build artifact digest");
        return root.resolve(digest.substring(7));
    }
    @Override public String put(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAXIMUM) throw new IOException("Invalid build artifact size");
        String digest = digest(bytes); Path destination = path(digest);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { read(digest); return digest; }
        Path pending = Files.createTempFile(root, "build-", ".partial");
        try {
            if (Files.getFileStore(pending).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.write(pending, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(pending, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { read(digest); }
        } finally { Files.deleteIfExists(pending); }
        read(digest);
        return digest;
    }
    @Override public byte[] read(String digest) throws IOException {
        Path file = path(digest);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAXIMUM) throw new IOException("Build artifact is unavailable");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAXIMUM + 1); }
        if (bytes.length == 0 || bytes.length > MAXIMUM) throw new IOException("Invalid build artifact size");
        if (!digest.equals(digest(bytes))) throw new IOException("Build artifact verification failed");
        return bytes;
    }
}
