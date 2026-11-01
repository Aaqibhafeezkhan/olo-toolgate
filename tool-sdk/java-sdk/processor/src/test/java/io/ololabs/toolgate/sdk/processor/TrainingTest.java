// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static io.ololabs.toolgate.sdk.processor.Compilation.GENERATION;
import static io.ololabs.toolgate.sdk.processor.Compilation.tools;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** {@code @Training} and {@code @TrainingValues} become {@code META-INF/toolgate/training/annotations.json}. */
class TrainingTest {
    private static Compilation.Result ok(Compilation.Result r) {
        assertTrue(r.success(), r.errors());
        return r;
    }

    private static void fails(Compilation.Result r, String... fragments) {
        assertFalse(r.success(), "compiled");
        for (String f : fragments) assertTrue(r.errors().contains(f), () -> "missing \"" + f + "\" in:\n" + r.errors());
    }

    private static String training(Compilation.Result r) {
        return new String(r.resource(TrainingHints.RESOURCE), StandardCharsets.UTF_8);
    }

    @Test
    void hintsAreWrittenBesideTheDescriptorsInTheAnnotationsFormat() {
        var r = ok(tools("", """
                @ServerTool(name = "query", title = "T", description = "D")
                @Training(
                        phrasings = {"Show {file} sorted by {sortBy}", "Top {limit} rows of {file}"},
                        defaultPhrasings = "Read {file}",
                        negatives = "How do I open a CSV in Excel?",
                        parameters = @TrainingValues(name = "sortBy", examples = {"amount", "date"}),
                        prompts = @TrainingPrompt(prompt = "Show orders.csv", arguments = "{\\"file\\":\\"orders.csv\\"}"))
                public Out run(
                        @Param(description = "File") @TrainingValues(examples = {"orders.csv", "42"}) String file,
                        @Param(description = "Sort") String sortBy,
                        @Param(description = "Limit", required = false) @TrainingValues(examples = {"5", "10"}) Integer limit,
                        @Param(description = "Desc", required = false)
                        @TrainingValues(phrasings = @ValuePhrasing(value = "true", phrasings = {"largest first"})) Boolean descending) {
                    return null;
                }
            """, GENERATION));
        assertEquals("{\"tools\":{\"query\":{"
                + "\"defaultPhrasings\":[\"Read {file}\"],"
                + "\"examples\":[{\"arguments\":{\"file\":\"orders.csv\"},\"prompt\":\"Show orders.csv\"}],"
                + "\"negatives\":[\"How do I open a CSV in Excel?\"],"
                + "\"parameters\":{"
                + "\"descending\":{\"phrasings\":{\"true\":[\"largest first\"]}},"
                + "\"file\":{\"examples\":[\"orders.csv\",\"42\"]},"
                + "\"limit\":{\"examples\":[5,10]},"
                + "\"sortBy\":{\"examples\":[\"amount\",\"date\"]}},"
                + "\"phrasings\":[\"Show {file} sorted by {sortBy}\",\"Top {limit} rows of {file}\"]}}}", training(r));
        // Hints never reach a descriptor, so editing them does not change a digest.
        assertFalse(r.descriptor("query").contains("largest first"));
    }

    @Test
    void aPackageWithoutHintsWritesNoFile() {
        var r = ok(tools("", """
                @ServerTool(name = "plain", title = "T", description = "D")
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION));
        assertFalse(Files.exists(r.out().resolve(TrainingHints.RESOURCE)));
    }

    @Test
    void hintsAreCheckedAgainstTheInputs() {
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                @Training(phrasings = "Look up {city}")
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION), "names {city}, which is not an input");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                @Training(parameters = @TrainingValues(name = "city", examples = "Paris"))
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION), "city, which is not an input");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                public Out run(@Param(description = "N") @TrainingValues(examples = "ten") int n) { return null; }
            """, GENERATION), "is not JSON");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                public Out run(@Param(description = "N") @TrainingValues(examples = "1.5") int n) { return null; }
            """, GENERATION), "does not suit input n");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                public Out run(@Param(description = "B")
                        @TrainingValues(phrasings = @ValuePhrasing(value = "yes", phrasings = "sure")) boolean b) { return null; }
            """, GENERATION), "true or false, not yes");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                public Out run(@Param(description = "U", enumValues = {"C", "F"})
                        @TrainingValues(phrasings = @ValuePhrasing(value = "K", phrasings = "kelvin")) String u) { return null; }
            """, GENERATION), "has no value K");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                @Training(negatives = "What is {id}?")
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION), "negatives are said as written");
        fails(tools("", """
                @ServerTool(name = "t", title = "T", description = "D")
                @Training(prompts = @TrainingPrompt(prompt = "Go", arguments = "{\\"other\\":1}"))
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION), "passes other, which is not an input");
        fails(tools("", """
                @Training(phrasings = "x")
                public Out notATool() { return null; }
                @ServerTool(name = "t", title = "T", description = "D")
                public Out run(@Param(description = "Id") String id) { return null; }
            """, GENERATION), "@Training belongs on a @ServerTool or @ClientTool handler");
    }
}
