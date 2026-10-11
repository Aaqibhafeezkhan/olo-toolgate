// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

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
 * The conformance cases the standalone runtime passes for the java target (tool-sdk/conformance). Each case also
 * targets other SDKs, so its status stays authored until they pass it too.
 */
class ConformanceTest {
    static final Map<String, String> RUNS = Map.ofEntries(
            Map.entry("mcp-core/discover", "RuntimeTest#discoverReportsVersionsAndDigests"),
            Map.entry("mcp-core/per-request-meta", "RuntimeTest#perRequestMetaIsRequired"),
            Map.entry("mcp-core/unsupported-version", "RuntimeTest#perRequestMetaIsRequired"),
            Map.entry("mcp-core/missing-capability", "RuntimeTest#asyncToolsNeedTheTasksExtension"),
            Map.entry("mcp-core/result-type", "RuntimeTest#echoReturnsStructuredContentWithDefaultsApplied,errorsMapToTheirCodes"),
            Map.entry("mcp-core/schemas-exact", "RuntimeTest#toolsListServesTheDescriptorSchemasExactly"),
            Map.entry("runtime/input-validation", "RuntimeTest#invalidArgumentsNeverReachTheHandler,longOutsideTheSafeRangeIsRejected"),
            Map.entry("runtime/size-limits", "RuntimeTest#sizeLimitsAreEnforced"),
            Map.entry("runtime/concurrency", "RuntimeTest#concurrencyLimitFailsAfterAboutOneSecond"),
            Map.entry("runtime/deadline", "RuntimeTest#deadlineExceeded"),
            Map.entry("runtime/reentrant", "RuntimeTest#concurrentInvocationsSeeOnlyTheirOwnArguments"),
            Map.entry("error-model/input-error", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("error-model/declared-business", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("error-model/undeclared", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("error-model/retryable-downgrade", "RuntimeTest#errorsMapToTheirCodes,retryableIsDowngradedForLedgerTools"),
            Map.entry("error-model/uncaught", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("error-model/result-shape", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("error-model/redaction", "RuntimeTest#secretsAndSensitiveInputsAreRedacted,messagesAndDataAreCapped"),
            Map.entry("error-model/output-schema", "RuntimeTest#outputMustMatchTheOutputSchema"),
            Map.entry("context/undeclared-name", "RuntimeTest#errorsMapToTheirCodes"),
            Map.entry("startup/client-tools", "RuntimeTest#startupRefusals"),
            Map.entry("startup/test-mode", "RuntimeTest#startupRefusals"),
            Map.entry("startup/audit-banner", "RuntimeTest#theStartupBannerSaysThereIsNoAudit"),
            Map.entry("runtime/cancel-grace", "TasksTest#aToolThatIgnoresCancellationIsAbandonedAfterTheGrace"),
            Map.entry("context/request-input", "TasksTest#requestInputMovesTheTaskToInputRequiredUntilTasksUpdate"),
            Map.entry("tasks/capability-per-request", "TasksTest#tasksAreOfferedOnlyToRequestsThatDeclareThem,syncToolsStaySyncAndTheOldTaskHintIsIgnored"),
            Map.entry("tasks/foreign-identity", "TasksTest#anotherOwnerGetsNotFoundExactlyAsForAnUnknownId"),
            Map.entry("tasks/ttl-purge", "TasksTest#tasksArePurgedAfterTheirTtl"),
            Map.entry("tasks/cancel", "TasksTest#cancelStopsTheToolAndIsIdempotentOnceTerminal,aToolThatIgnoresCancellationIsAbandonedAfterTheGrace"),
            Map.entry("tasks/cancel-if-abandoned", "TasksTest#cancelIfAbandonedCancelsAfterThreeMissedPolls"),
            Map.entry("tasks/input-required", "TasksTest#requestInputMovesTheTaskToInputRequiredUntilTasksUpdate"),
            Map.entry("startup/development-explicit", "HttpTest#onlyLoopbackAddressesStart"));

    @Test
    void everyMappedCaseExistsAndTargetsJava() throws IOException {
        Map<String, JsonNode> cases = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(System.getProperty("toolgate.conformance")))) {
            files = walk.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        }
        for (Path file : files) {
            JsonNode root = Fixtures.JSON.readTree(file.toFile());
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
