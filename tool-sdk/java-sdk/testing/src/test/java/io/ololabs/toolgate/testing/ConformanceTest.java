// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The conformance cases ToolHarness passes for the java target (tool-sdk/conformance). Each case also targets other
 * SDKs, so its status stays authored until they pass it too.
 */
class ConformanceTest {
    static final Map<String, String> RUNS = Map.ofEntries(
            Map.entry("dev-server/tool-harness", "ToolHarnessTest#routeCallsAreAnsweredFromRecordedExchangesAndRecordedByRouteAndVariables"),
            Map.entry("dev-server/examples-as-self-tests", "ToolHarnessTest#examplesRunWithTheirOwnRecordedExchanges"));

    @Test
    void everyMappedCaseExistsAndTargetsJava() throws IOException {
        Path file = Path.of(System.getProperty("toolgate.conformance"), "sdk-unit/dev-server.json");
        Map<String, JsonNode> cases = new HashMap<>();
        for (JsonNode c : RecordedExchange.JSON.readTree(file.toFile()).path("cases")) cases.put(c.path("id").asText(), c);
        for (Map.Entry<String, String> run : RUNS.entrySet()) {
            JsonNode c = cases.get(run.getKey());
            assertNotNull(c, run.getKey());
            assertTrue(c.path("targets").toString().contains("\"java\""), run.getKey() + " does not target java");
            String[] parts = run.getValue().split("#");
            Class<?> type = assertDoesNotThrow(() -> Class.forName(getClass().getPackageName() + "." + parts[0]));
            for (String method : parts[1].split(",")) assertDoesNotThrow(() -> type.getDeclaredMethod(method), run.getValue());
        }
    }
}
