// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.FileBuilderArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuilderArtifactsTest {
    @TempDir Path directory;
    @Test void artifactsAreImmutableVerifiedAndCannotEscapeTheirDirectory() throws Exception {
        var artifacts = new FileBuilderArtifacts(directory);
        byte[] original = "sealed output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = artifacts.put(original);
        assertEquals(digest, artifacts.put(original));
        assertArrayEquals(original, artifacts.read(digest));
        assertThrows(java.io.IOException.class, () -> artifacts.read("../escape"));
        assertThrows(java.io.IOException.class, () -> artifacts.read("sha256:" + "0".repeat(64)));
        Files.writeString(directory.resolve(digest.substring(7)), "tampered");
        assertThrows(java.io.IOException.class, () -> artifacts.read(digest));
        assertThrows(java.io.IOException.class, () -> artifacts.put(original));
    }
}
