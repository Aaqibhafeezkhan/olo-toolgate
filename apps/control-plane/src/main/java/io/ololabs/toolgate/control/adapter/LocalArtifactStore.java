// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.ArtifactStore;
import io.ololabs.toolgate.control.application.Failure;
import java.nio.file.*;

/** Quickstart-only content-addressed custody; fleet authorization still precedes reads. */
public final class LocalArtifactStore implements ArtifactStore {
    private final Path root;
    public LocalArtifactStore(Path root) {
        if (!root.isAbsolute() || Files.isSymbolicLink(root)) throw new IllegalArgumentException();
        this.root = root;
    }
    /** Write-once: an existing file must already hold exactly these bytes. */
    public void mirror(String digest, byte[] bytes) {
        try {
            if (!digest.matches("[a-f0-9]{64}") || bytes.length < 1 || bytes.length > 32768 || !java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest)) throw Failure.validation();
            var target = root.resolve(digest + ".json");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { if (!java.util.Arrays.equals(download(digest, bytes.length), bytes)) throw Failure.conflict(); return; }
            var temporary = Files.createTempFile(root, ".mirror-", ".tmp");
            try { Files.write(temporary, bytes); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); } finally { Files.deleteIfExists(temporary); }
        } catch (java.io.IOException | java.security.GeneralSecurityException failure) { throw Failure.unavailable(); }
    }
    public byte[] download(String digest, long expectedBytes) {
        if (!digest.matches("[a-f0-9]{64}") || expectedBytes < 1 || expectedBytes > 32768) throw Failure.validation();
        try (var input = Files.newInputStream(root.resolve(digest + ".json"), LinkOption.NOFOLLOW_LINKS)) {
            var bytes = input.readNBytes((int) expectedBytes + 1);
            if (bytes.length != expectedBytes || !java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest)) throw Failure.unavailable();
            return bytes;
        } catch (java.io.IOException | java.security.GeneralSecurityException failure) { throw Failure.unavailable(); }
    }
}
