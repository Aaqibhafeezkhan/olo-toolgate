// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The conformance cases this runner passes for the java target (tool-sdk/conformance). Each case also targets other
 * SDKs, so its status stays authored until they pass it too.
 */
class ConformanceTest {
    static final Map<String, String> RUNS = Map.ofEntries(
            Map.entry("descriptors/canonical-bytes", "DescriptorVectorTest#clientToolMatchesTool01,serverToolMatchesTool02,taskToolWithNonAsciiNamesMatchesTool03"),
            Map.entry("route-analysis/path-variable-regex", "DeclarationTest#routesAreChecked"),
            Map.entry("descriptors/set-digest", "DescriptorVectorTest#setDigestMatchesSet01"),
            Map.entry("descriptors/excludes-deployment", "DescriptorVectorTest#deploymentValuesAndExamplesDoNotChangeTheDigest"),
            Map.entry("descriptors/generation-required", "DeclarationTest#generationIsRequiredAndHasNoDefault"),
            Map.entry("declarations/identifier-grammar", "DeclarationTest#identifiersFollowTheirGrammars,toolIdIsAtMost128Characters"),
            Map.entry("declarations/reserved-prefixes", "DeclarationTest#reservedPrefixesAreRejected"),
            Map.entry("declarations/variable-name-unique", "DeclarationTest#variableNamesAreUniqueIgnoringCase"),
            Map.entry("declarations/tool-defaults", "DeclarationTest#toolAttributesTakeTheSpecifiedDefaults"),
            Map.entry("declarations/requires-business-key-default", "DeclarationTest#nonIdempotentDestructiveToolsRequireABusinessKey"),
            Map.entry("declarations/async-duration", "DeclarationTest#asyncToolsDefaultTo900000Ms"),
            Map.entry("declarations/title-text-alias", "DeclarationTest#titleAndTextAreExclusiveAliases"),
            Map.entry("declarations/client-tool-restrictions", "DeclarationTest#clientToolsCannotDeclareEgressOrSecrets"),
            Map.entry("declarations/no-override", "DeclarationTest#toolLevelDeclarationsNeverOverrideScopeLevelOnes"),
            Map.entry("declarations/typed-grouped-identical", "DeclarationTest#typedAndGroupedFormsGiveIdenticalBytes"),
            Map.entry("declarations/closed-objects", "DeclarationTest#objectsAreClosedUnlessExplicitMaps"),
            Map.entry("declarations/examples-limit", "DeclarationTest#atMostEightExamples"));

    @Test
    void everyMappedCaseExistsAndTargetsJava() throws IOException {
        Path root = Path.of(System.getProperty("toolgate.conformance"));
        String cases = Files.readString(root.resolve("sdk-unit/declarations.json"), StandardCharsets.UTF_8)
                + Files.readString(root.resolve("contract-descriptor/descriptors.json"), StandardCharsets.UTF_8)
                + Files.readString(root.resolve("contract-descriptor/route-analysis.json"), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> run : RUNS.entrySet()) {
            int at = cases.indexOf("\"id\": \"" + run.getKey() + "\"");
            assertTrue(at >= 0, run.getKey());
            int end = cases.indexOf("\"status\"", at);
            assertTrue(cases.substring(at, end).contains("\"java\""), run.getKey() + " does not target java");
            String[] parts = run.getValue().split("#");
            Class<?> type = assertDoesNotThrow(() -> Class.forName(getClass().getPackageName() + "." + parts[0]));
            for (String method : parts[1].split(",")) {
                assertDoesNotThrow(() -> type.getDeclaredMethod(method), run.getValue());
            }
        }
    }
}
