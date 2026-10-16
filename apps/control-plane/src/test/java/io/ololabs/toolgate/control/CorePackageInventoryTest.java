// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.CorePackageInventory;
import io.ololabs.toolgate.control.application.Canonical;
import io.ololabs.toolgate.jsonsdk.JsonProject;
import io.ololabs.toolgate.packagearchive.PackageArchive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CorePackageInventoryTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private byte[] artifact() throws Exception {
        var files = ServerPackagesTest.files();
        var manifest = (ObjectNode)json.readTree(files.get("toolgate-package.json"));
        ((ObjectNode)manifest.get("sdk")).put("version", JsonProject.sdkVersion());
        files.put("toolgate-package.json", json.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var entry : files.entrySet()) if (!entry.getKey().equals("CHECKSUMS.sha256")) checksums.append(Canonical.sha256(entry.getValue()).substring(7)).append("  ").append(entry.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ServerPackagesTest.zip(files);
    }
    private ObjectNode inventory(byte[] bytes) throws Exception {
        var verified = PackageArchive.validate(bytes); var manifest = json.readTree(verified.manifestBytes());
        var lock = json.createObjectNode().put("schemaVersion", 1).put("sdkVersion", JsonProject.sdkVersion());
        lock.putArray("packages").addObject().put("project", "any-project").put("sdk", "java-gradle")
                .put("id", manifest.path("id").asText()).put("version", manifest.path("version").asText())
                .put("artifact", "example.tgpkg").put("artifactSha256", Canonical.sha256(bytes).substring(7)).put("packageDigest", verified.packageDigest());
        Files.write(directory.resolve("example.tgpkg"), bytes);
        return lock;
    }
    private Path write(ObjectNode lock) throws Exception {
        return Files.write(directory.resolve("core-packages.lock.json"), json.writeValueAsBytes(lock));
    }
    @Test void verifiesAllPinsAndReturnsImmutableArtifactBytes() throws Exception {
        byte[] bytes = artifact(); var lock = write(inventory(bytes)); var loaded = CorePackageInventory.load(lock);
        assertEquals(1, loaded.size()); assertArrayEquals(bytes, loaded.getFirst().bytes());
        loaded.getFirst().bytes()[0] = 0;
        assertArrayEquals(bytes, loaded.getFirst().bytes());
        Files.write(directory.resolve("example.tgpkg"), new byte[]{1, 2});
        assertThrows(IOException.class, loaded.getFirst()::bytes);
        assertThrows(IOException.class, () -> CorePackageInventory.load(lock));
    }
    @Test void rejectsSubstitutionDuplicatesUnknownFieldsAndPathEscape() throws Exception {
        byte[] bytes = artifact();
        for (String field : new String[]{"artifact", "id", "version", "packageDigest"}) {
            var lock = inventory(bytes); var entry = (ObjectNode)lock.path("packages").get(0);
            entry.put(field, field.equals("artifact") ? "../example.tgpkg" : "unexpected");
            var path = write(lock); assertThrows(IOException.class, () -> CorePackageInventory.load(path), field);
        }
        var lock = inventory(bytes); lock.withArray("packages").add(lock.path("packages").get(0).deepCopy());
        var repeated = write(lock); assertThrows(IOException.class, () -> CorePackageInventory.load(repeated));
        lock = inventory(bytes); lock.put("activate", true);
        var unknown = write(lock); assertThrows(IOException.class, () -> CorePackageInventory.load(unknown));
        var duplicate = Files.writeString(directory.resolve("duplicate.json"), "{\"schemaVersion\":1,\"schemaVersion\":1}");
        assertThrows(IOException.class, () -> CorePackageInventory.load(duplicate));
    }
}
