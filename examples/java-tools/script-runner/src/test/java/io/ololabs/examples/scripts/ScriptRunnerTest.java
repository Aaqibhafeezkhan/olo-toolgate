// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ScriptRunnerTest {
    @TempDir
    Path scripts;

    private ToolHarness harness(String allowed) {
        return ToolHarness.builder().global("ScriptsDir", scripts.toString()).global("AllowedScripts", allowed).build();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void returnsTheLastLinesOfOutput() throws IOException {
        Files.writeString(scripts.resolve("count.sh"), "i=1\nwhile [ $i -le 30 ]; do echo \"line $i $1\"; i=$((i+1)); done\necho oops >&2\nexit 3\n");
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("run", Map.of("script", "count.sh", "arguments", List.of("abc"), "lines", 3));
            assertEquals(3, result.output().path("exitCode").asInt(), result.toString());
            assertEquals(31, result.output().path("totalLines").asInt());
            assertEquals("[\"line 29 abc\",\"line 30 abc\",\"oops\"]", result.output().path("lastLines").toString());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void killsARunAtItsDeadline() throws IOException {
        Files.writeString(scripts.resolve("slow.sh"), "echo started\nsleep 30\necho never\n");
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("run", Map.of("script", "slow.sh", "timeoutSeconds", 1));
            assertTrue(result.output().path("timedOut").asBoolean(), result.toString());
            assertEquals(-1, result.output().path("exitCode").asInt());
            assertEquals("[\"started\"]", result.output().path("lastLines").toString());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void runsABatchFile() throws IOException {
        Files.writeString(scripts.resolve("hello.bat"), "@echo off\r\necho one\r\necho two %1\r\n");
        try (ToolHarness tools = harness("*")) {
            ToolResult result = tools.call("run", Map.of("script", "hello.bat", "arguments", List.of("x"), "lines", 1));
            assertEquals(0, result.output().path("exitCode").asInt(), result.toString());
            assertEquals("[\"two x\"]", result.output().path("lastLines").toString());
        }
    }

    @Test
    void refusesScriptsOutsideTheAllowlist() throws IOException {
        Files.writeString(scripts.resolve("ok.sh"), "echo ok\n");
        Files.writeString(scripts.resolve("other.sh"), "echo other\n");
        try (ToolHarness tools = harness("ok.sh")) {
            assertEquals("SCRIPT_NOT_FOUND", tools.call("run", Map.of("script", "other.sh")).errorCode());
            assertEquals("SCRIPT_NOT_FOUND", tools.call("run", Map.of("script", "missing.sh")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("run", Map.of("script", "ok.exe")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("run", Map.of("script", "sub/ok.sh")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("run", Map.of("script", "ok.sh", "arguments", List.of("x | y"))).errorCode());
            assertEquals("[\"ok.sh\"]", tools.call("list", Map.of()).output().path("scripts").toString());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void refusesALinkOutOfTheScriptsDirectory(@TempDir Path elsewhere) throws IOException {
        Path target = Files.writeString(elsewhere.resolve("evil.sh"), "echo evil\n");
        Files.createSymbolicLink(scripts.resolve("evil.sh"), target);
        try (ToolHarness tools = harness("*")) {
            assertEquals("SCRIPT_NOT_FOUND", tools.call("run", Map.of("script", "evil.sh")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = harness("*")) {
            tools.assertExamples();
        }
    }
}
