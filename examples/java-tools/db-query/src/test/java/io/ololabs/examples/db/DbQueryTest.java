// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbQueryTest {
    @TempDir
    Path dir;
    String file;

    @BeforeEach
    void seed() throws Exception {
        file = dir.resolve("demo").toString();
        try (Connection admin = DriverManager.getConnection("jdbc:h2:file:" + file, "sa", "admin-secret")) {
            RunScript.execute(admin, new java.io.FileReader("demo/seed.sql"));
        }
    }

    private ToolHarness harness(String allowedTables) {
        return ToolHarness.builder().global("DatabaseFile", file).secret("DbPassword", "change-me")
                .global("AllowedTables", allowedTables).build();
    }

    @Test
    void listsTablesAndColumns() {
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("tables", Map.of());
            JsonNode tables = result.output().path("tables");
            assertEquals(2, tables.size(), result.toString());
            assertEquals("CUSTOMERS", tables.get(0).path("name").asText());
            assertEquals("ID", tables.get(0).path("columns").get(0).path("name").asText());
        }
    }

    @Test
    void looksUpRowsByKey() {
        try (ToolHarness tools = harness("orders")) {
            JsonNode out = tools.call("row", Map.of("table", "orders", "column", "status", "value", "PENDING")).output();
            assertEquals(2, out.path("rows").size(), out.toString());
            assertEquals("1004", out.path("rows").get(0).get(0).asText());
            assertEquals("TABLE_NOT_FOUND", tools.call("row", Map.of("table", "customers", "column", "id", "value", "1")).errorCode());
            assertEquals("TABLE_NOT_FOUND", tools.call("row", Map.of("table", "orders", "column", "nope", "value", "1")).errorCode());
        }
    }

    @Test
    void runsAReadOnlyQueryWithParameters() {
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("query", Map.of(
                    "sql", "SELECT c.city, COUNT(*) AS n, SUM(o.amount) AS total FROM orders o JOIN customers c ON c.id = o.customer_id"
                            + " WHERE o.status <> ? GROUP BY c.city ORDER BY total DESC",
                    "parameters", List.of("CANCELLED")));
            JsonNode out = result.output();
            assertEquals("[\"CITY\",\"N\",\"TOTAL\"]", out.path("columns").toString(), result.toString());
            assertEquals("[\"Pune\",\"3\",\"3360.49\"]", out.path("rows").get(0).toString());
        }
    }

    @Test
    void capsRows() {
        try (ToolHarness tools = harness("*")) {
            JsonNode out = tools.call("query", Map.of("sql", "SELECT id FROM orders ORDER BY id", "limit", 2)).output();
            assertEquals(2, out.path("rows").size());
            assertTrue(out.path("truncated").asBoolean());
        }
    }

    @Test
    void refusesAnythingButOneRead() {
        try (ToolHarness tools = harness("*")) {
            for (String sql : List.of("UPDATE orders SET amount = 0", "SELECT 1; DROP TABLE orders", "SELECT * FROM orders -- x",
                    "SELECT CSVREAD('/etc/passwd')", "WITH x AS (SELECT 1) SELECT * INTO y FROM x")) {
                assertEquals("INVALID_ARGUMENT", tools.call("query", Map.of("sql", sql)).errorCode(), sql);
            }
            // A forbidden word inside a string literal is only data.
            JsonNode out = tools.call("query", Map.of("sql", "SELECT 'DROP TABLE; --' AS t")).output();
            assertEquals("[[\"DROP TABLE; --\"]]", out.path("rows").toString(), out.toString());
        }
    }

    @Test
    void theAccountCannotWriteEvenIfTheCheckIsBypassed() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + file + ";ACCESS_MODE_DATA=r", "reader", "change-me")) {
            Exception e = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> c.createStatement().executeQuery("SELECT FILE_READ('demo/seed.sql')"));
            assertTrue(e.getMessage().toLowerCase().contains("admin") || e.getMessage().contains("90"), e.getMessage());
        }
    }

    @Test
    void reportsDatabaseErrors() {
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("query", Map.of("sql", "SELECT nope FROM orders"));
            assertEquals("QUERY_FAILED", result.errorCode(), result.toString());
            assertTrue(result.errorMessage().contains("NOPE"), result.errorMessage());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = harness("*")) {
            tools.assertExamples();
        }
    }
}
