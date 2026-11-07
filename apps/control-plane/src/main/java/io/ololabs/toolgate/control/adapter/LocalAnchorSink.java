// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.AuditAnchorSink;
import io.ololabs.toolgate.control.application.Failure;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Append-only directory sink with object-lock-like semantics: objects are created through an
 * atomic hard link (which fails if the name exists), synced, made read-only and never replaced.
 * The directory should be a separately administered mount that Control can create in but not rewrite.
 */
public final class LocalAnchorSink implements AuditAnchorSink {
    private static final Pattern NAME = Pattern.compile("tenants/[0-9a-f-]{36}/audit-anchors/[0-9]{19}-[0-9a-f]{64}\\.json");
    private static final int LIMIT = 8192;
    private final Path root;

    public LocalAnchorSink(Path root) {
        try {
            this.root = root.toRealPath();
            if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        } catch (IOException | IllegalArgumentException invalid) { throw new IllegalStateException("Existing anchor sink directory required", invalid); }
    }

    public String kind() { return "local"; }

    private Path path(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw Failure.validation();
        var path = root.resolve(name).normalize();
        if (!path.startsWith(root)) throw Failure.validation();
        return path;
    }

    public Receipt publish(String name, byte[] content) {
        if (content == null || content.length == 0 || content.length > LIMIT) throw Failure.validation();
        var target = path(name);
        try {
            Files.createDirectories(target.getParent());
            if (!target.getParent().toRealPath().startsWith(root)) throw Failure.validation();
            var staged = Files.createTempFile(target.getParent(), ".anchor-", ".tmp");
            try {
                try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(java.nio.ByteBuffer.wrap(content)); channel.force(true);
                }
                try { Files.createLink(target, staged); }
                catch (FileAlreadyExistsException exists) {
                    if (!java.util.Arrays.equals(read(name), content)) throw new Conflict(name);
                    return receipt(name, content);
                }
            } finally { Files.deleteIfExists(staged); }
            target.toFile().setReadOnly();
            syncDirectory(target.getParent());
            return receipt(name, content);
        } catch (IOException unavailable) { throw Failure.unavailable(); }
    }

    public byte[] read(String name) {
        var target = path(name);
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > LIMIT) throw Failure.validation();
            return Files.readAllBytes(target);
        } catch (IOException unavailable) { throw Failure.unavailable(); }
    }

    private Receipt receipt(String name, byte[] content) {
        try { return new Receipt(kind(), name, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void syncDirectory(Path directory) {
        // POSIX directories can be synced to persist the new entry; Windows refuses to open them.
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException unsupported) { /* best effort on non-POSIX hosts */ }
    }
}
