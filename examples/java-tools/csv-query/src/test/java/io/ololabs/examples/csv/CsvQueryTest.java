// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvQueryTest {
    @TempDir
    Path data;

    @BeforeEach
    void writeData() throws IOException {
        Files.writeString(data.resolve("orders.csv"), """
                id,customer,region,status,amount
                1,Acme Corp,north,PAID,120.50
                2,"Globex, Inc",south,FAILED,900
                3,Acme Corp,north,FAILED,640
                4,Initech,south,PAID,75
                5,"Quote ""Q"" Ltd",east,FAILED,30
                """);
        Files.writeString(data.resolve("stock.tsv"), "sku\tqty\nA-1\t5\n");
        Files.writeString(data.resolve("notes.md"), "not data");
    }

    private ToolHarness harness() {
        return ToolHarness.builder().global("DataDir", data.toString()).build();
    }

    @Test
    void filtersSortsAndPicksColumns() {
        try (ToolHarness tools = harness()) {
            ToolResult result = tools.call("query", Map.of("file", "orders.csv", "where", List.of("status=failed", "amount>=100"),
                    "columns", List.of("customer", "amount"), "sortBy", "amount", "descending", true));
            JsonNode out = result.output();
            assertEquals("[\"customer\",\"amount\"]", out.path("columns").toString(), result.toString());
            assertEquals("[[\"Globex, Inc\",\"900\"],[\"Acme Corp\",\"640\"]]", out.path("rows").toString());
            assertEquals(2, out.path("matchedRows").asInt());
            assertEquals(5, out.path("totalRows").asInt());
        }
    }

    @Test
    void groupsWithCountAndSum() {
        try (ToolHarness tools = harness()) {
            JsonNode out = tools.call("query", Map.of("file", "orders.csv", "groupBy", "region", "sum", "amount",
                    "sortBy", "sum", "descending", true)).output();
            assertEquals("[\"region\",\"count\",\"sum\"]", out.path("columns").toString(), out.toString());
            assertEquals("[[\"south\",\"2\",\"975\"],[\"north\",\"2\",\"760.5\"],[\"east\",\"1\",\"30\"]]", out.path("rows").toString());
        }
    }

    @Test
    void containsAndQuotes() {
        try (ToolHarness tools = harness()) {
            JsonNode out = tools.call("query", Map.of("file", "orders.csv", "where", List.of("customer~\"q\""), "columns", List.of("customer"))).output();
            assertEquals("[[\"Quote \\\"Q\\\" Ltd\"]]", out.path("rows").toString(), out.toString());
        }
    }

    @Test
    void listsFilesWithColumns() {
        try (ToolHarness tools = harness()) {
            JsonNode files = tools.call("files", Map.of()).output().path("files");
            assertEquals(2, files.size(), files.toString());
            assertEquals("[\"sku\",\"qty\"]", files.get(1).path("columns").toString());
        }
    }

    @Test
    void refusesUnknownColumnsAndFilesOutsideTheDirectory() {
        try (ToolHarness tools = harness()) {
            assertEquals("INVALID_ARGUMENT", tools.call("query", Map.of("file", "orders.csv", "columns", List.of("secret"))).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("query", Map.of("file", "notes.md")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("query", Map.of("file", "../orders.csv")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = harness()) {
            tools.assertExamples();
        }
    }
}
