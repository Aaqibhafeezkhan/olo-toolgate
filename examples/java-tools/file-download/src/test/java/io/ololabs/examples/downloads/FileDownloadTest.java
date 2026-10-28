// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.downloads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.RecordedExchange;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDownloadTest {
    @TempDir
    Path downloads;

    private ToolHarness harness(String publicBaseUrl) {
        return ToolHarness.builder()
                .global("DownloadDir", downloads.toString())
                .global("PublicBaseUrl", publicBaseUrl)
                .exchange(RecordedExchange.on("file.get").variable("folder", "reports").variable("file", "q3.csv")
                        .replyText(200, "region,total\nnorth,10\n").replyHeader("Content-Type", "text/csv"))
                .build();
    }

    @Test
    void savesTheFileAndReturnsWhereItLanded() throws IOException {
        try (ToolHarness tools = harness("https://files.example.com/dl")) {
            ToolResult result = tools.call("fetch", Map.of("path", "reports/q3.csv"));
            JsonNode out = result.output();
            Path saved = downloads.toRealPath().resolve("q3.csv");
            assertEquals(saved.toString(), out.path("path").asText(), result.toString());
            assertEquals(saved.toUri().toString(), out.path("fileUrl").asText());
            assertEquals("https://files.example.com/dl/q3.csv", out.path("publicUrl").asText());
            assertEquals("region,total\nnorth,10\n", Files.readString(saved));
            assertEquals(22, out.path("sizeBytes").asLong());
            assertEquals("text/csv", out.path("contentType").asText());
            assertEquals("file.get", tools.routeCalls().get(0).route());
        }
    }

    @Test
    void neverOverwritesUnlessAsked() throws IOException {
        Files.writeString(downloads.resolve("q3.csv"), "old");
        try (ToolHarness tools = harness("none")) {
            assertEquals("FILE_EXISTS", tools.call("fetch", Map.of("path", "reports/q3.csv")).errorCode());
            assertEquals("old", Files.readString(downloads.resolve("q3.csv")));
            ToolResult replaced = tools.call("fetch", Map.of("path", "reports/q3.csv", "overwrite", true));
            assertEquals("", replaced.output().path("publicUrl").asText(), replaced.toString());
            assertEquals("region,total\nnorth,10\n", Files.readString(downloads.resolve("q3.csv")));
        }
    }

    @Test
    void refusesPathsThatLeaveTheirFolder() {
        try (ToolHarness tools = harness("none")) {
            assertEquals("INVALID_ARGUMENT", tools.call("fetch", Map.of("path", "../etc/passwd")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("fetch", Map.of("path", "a/b/c")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("fetch", Map.of("path", "reports/q3.csv", "saveAs", "../x")).errorCode());
            assertEquals(0, tools.routeCalls().size());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().global("DownloadDir", downloads.toString()).build()) {
            tools.assertExamples();
        }
    }
}
