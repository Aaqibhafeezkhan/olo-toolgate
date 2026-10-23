// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.control.application.McpDefinitions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

/** Control's definition digest agrees with the shared vectors in tool-sdk/spec/mcp-proxy. */
final class McpDefinitionsTest {
    @Test void digestsMatchTheSharedVectors() throws Exception {
        var vectors = new ObjectMapper().readTree(Files.readAllBytes(PackageArchiveBaselineTest.root().resolve("tool-sdk/spec/mcp-proxy/definition-digest.json")));
        assertTrue(vectors.get("cases").size() >= 8);
        for (var vector : vectors.get("cases"))
            assertEquals(vector.get("digest").textValue(), McpDefinitions.digest(vector.get("tool")), vector.get("name").textValue());
    }

    @Test void refusesAnythingButAnObject() {
        assertThrows(IllegalArgumentException.class, () -> McpDefinitions.digest(new ObjectMapper().createArrayNode()));
    }
}
