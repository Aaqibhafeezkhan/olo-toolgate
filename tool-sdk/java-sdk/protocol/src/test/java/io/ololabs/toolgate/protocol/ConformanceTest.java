// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The conformance cases the protocol layer and its transports pass for the java target (tool-sdk/conformance). Each case also
 * targets other SDKs, so its status stays authored until they pass it too.
 */
class ConformanceTest {
    static final Map<String, String> RUNS = Map.ofEntries(
            Map.entry("mcp-core/header-mismatch", "StreamableHttpServerTest#headerAndMetaErrorsFollowTheSpecifiedOrderAndStatuses"),
            Map.entry("mcp-core/listen", "StreamableHttpServerTest#listenAcknowledgesTagsAndFilters"),
            Map.entry("tasks/status-push", "StreamableHttpServerTest#taskStatusChangesArePushedToListenersThatDeclaredTasks"),
            Map.entry("official-sdk/tasks-status-push", "StreamableHttpServerTest#taskStatusChangesArePushedToListenersThatDeclaredTasks"),
            Map.entry("legacy/stdio-era-lock", "StdioServerTest#theConnectionIsLockedToItsFirstEra"),
            Map.entry("official-sdk/stdio-era", "StdioServerTest#theConnectionIsLockedToItsFirstEra"));

    @Test
    void everyMappedCaseExistsAndTargetsJava() throws IOException {
        Map<String, JsonNode> cases = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(System.getProperty("toolgate.conformance")))) {
            files = walk.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        }
        for (Path file : files) {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
            JsonNode list = root.isObject() ? root.path("cases") : root;
            for (JsonNode c : list) if (c.has("id")) cases.put(c.get("id").asText(), c);
        }
        for (Map.Entry<String, String> run : RUNS.entrySet()) {
            JsonNode c = cases.get(run.getKey());
            assertNotNull(c, run.getKey());
            assertTrue(c.path("targets").toString().contains("\"java\""), run.getKey() + " does not target java");
            String[] parts = run.getValue().split("#");
            Class<?> type = assertDoesNotThrow(() -> Class.forName(getClass().getPackageName() + "." + parts[0]));
            for (String method : parts[1].split(",")) {
                assertDoesNotThrow(() -> type.getDeclaredMethod(method), run.getValue());
            }
        }
    }
}
