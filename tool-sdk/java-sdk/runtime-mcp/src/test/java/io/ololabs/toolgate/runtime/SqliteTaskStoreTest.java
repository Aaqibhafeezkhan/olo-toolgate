// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.MemoryTaskStore;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.TaskRecord;
import io.ololabs.toolgate.protocol.TaskStatus;
import io.ololabs.toolgate.protocol.TaskStore;
import io.ololabs.toolgate.protocol.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The SQLite task store, alone and behind the standalone runtime. */
class SqliteTaskStoreTest {
    static Path file() throws Exception {
        return Files.createTempDirectory("toolgate-tasks").resolve("state.db");
    }

    static TaskRecord task(String id, String owner, Instant expiresAt) {
        TaskRecord t = new TaskRecord();
        t.taskId = id;
        t.owner = owner;
        t.statusMessage = "Running";
        t.createdAt = Instant.parse("2026-10-11T10:00:00.123456Z");
        t.lastUpdatedAt = t.createdAt;
        t.lastPolledAt = t.createdAt;
        t.ttlMs = 60_000;
        t.pollIntervalMs = 100;
        t.expiresAt = expiresAt;
        return t;
    }

    @Test
    void theStoreKeepsTheWholeRecordAndHidesItFromOtherOwners() throws Exception {
        Instant later = Instant.now().plusSeconds(3600);
        try (SqliteTaskStore s = SqliteTaskStore.open(file())) {
            s.create(task("a", "alice", later));
            assertThrows(IllegalStateException.class, () -> s.create(task("a", "bob", later)), "task ids are unique");
            assertTrue(s.get("a", "bob").isEmpty());
            assertTrue(s.get("missing", "alice").isEmpty());

            s.transition("a", t -> {
                t.status = TaskStatus.INPUT_REQUIRED;
                t.inputRequests.putObject("input-1").put("method", "elicitation/create");
            });
            assertTrue(s.submitInput("a", "bob", Fixtures.JSON.createObjectNode()).isEmpty());
            ObjectNode answers = Fixtures.JSON.createObjectNode();
            answers.putObject("input-1").put("action", "accept");
            answers.putObject("unknown").put("action", "accept");
            TaskRecord answered = s.submitInput("a", "alice", answers).orElseThrow();
            assertFalse(answered.inputRequests.has("input-1"));
            assertEquals("accept", answered.inputResponses.path("input-1").path("action").asText());
            assertFalse(answered.inputResponses.has("unknown"), "unknown keys are ignored");

            assertTrue(s.requestCancel("a", "bob").isEmpty());
            assertTrue(s.requestCancel("a", "alice").orElseThrow().cancelRequested);
            s.transition("a", t -> {
                t.status = TaskStatus.COMPLETED;
                t.result = Fixtures.JSON.createObjectNode().put("isError", false);
            });
            TaskRecord done = s.get("a", "alice").orElseThrow();
            assertEquals(TaskStatus.COMPLETED, done.status);
            assertEquals(Instant.parse("2026-10-11T10:00:00.123456Z"), done.createdAt, "instants keep their precision");
            assertFalse(done.result.path("isError").asBoolean());
            assertTrue(s.active().isEmpty(), "a completed task is not active");
        }
    }

    @Test
    void purgeRemovesExpiredTasksAndReturnsThem() throws Exception {
        Instant now = Instant.now();
        try (SqliteTaskStore s = SqliteTaskStore.open(file())) {
            s.create(task("old", "alice", now.minusMillis(1)));
            s.create(task("new", "alice", now.plusSeconds(60)));
            assertEquals(List.of("old"), s.purgeExpired(now).stream().map(t -> t.taskId).toList());
            assertTrue(s.get("old", "alice").isEmpty());
            assertEquals(List.of("new"), s.active().stream().map(t -> t.taskId).toList());
        }
    }

    @Test
    void tasksSurviveARestartAndUnfinishedOnesEndOutcomeUnknown() throws Exception {
        Path file = file();
        Instant later = Instant.now().plusSeconds(3600);
        try (SqliteTaskStore s = SqliteTaskStore.open(file)) {
            s.create(task("done", "alice", later));
            s.transition("done", t -> {
                t.status = TaskStatus.COMPLETED;
                t.result = Fixtures.JSON.createObjectNode().put("isError", false);
            });
            s.create(task("running", "alice", later));
            s.create(task("waiting", "alice", later));
            s.transition("waiting", t -> {
                t.status = TaskStatus.INPUT_REQUIRED;
                t.inputRequests.putObject("input-1");
            });
        }
        try (SqliteTaskStore s = SqliteTaskStore.open(file)) {
            assertEquals(TaskStatus.COMPLETED, s.get("done", "alice").orElseThrow().status);
            for (String id : List.of("running", "waiting")) {
                TaskRecord t = s.get(id, "alice").orElseThrow();
                assertEquals(TaskStatus.FAILED, t.status, id);
                assertEquals(-32603, t.error.path("code").asInt());
                assertEquals("OUTCOME_UNKNOWN", t.error.path("data").path("code").asText());
                assertTrue(t.inputRequests.isEmpty());
            }
            assertTrue(s.active().isEmpty());
        }
    }

    // ---- behind the runtime ----

    static Map<String, String> env(Path state) throws Exception {
        Path dev = Files.createTempFile("toolgate.dev", ".json");
        Files.writeString(dev, "{\"secrets\":{\"ApiKey\":\"fixture-api-value\"}}");
        return Map.of("TOOLGATE_MODE", "development", "TOOLGATE_DEV_JSON", dev.toString(), "TOOLGATE_TASK_STORE", "sqlite",
                "TOOLGATE_STATE_DB", state.toString());
    }

    static JsonNode get(Fixtures.Server s, ProtocolCore.Connection on, String taskId) {
        ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
        params.put("taskId", taskId);
        return s.request("tasks/get", params, on).path("result");
    }

    static String startTask(Fixtures.Server s, ProtocolCore.Connection on, long ms) {
        ObjectNode params = Fixtures.Server.params(RequestMeta.TASKS);
        params.put("name", "demo.demo.report");
        params.putObject("arguments").put("ms", ms);
        JsonNode created = s.request("tools/call", params, on).path("result");
        assertEquals("task", created.path("resultType").asText(), created.toString());
        return created.path("taskId").asText();
    }

    @Test
    void aRestartedRuntimeAnswersForTheTasksOfTheLastOne() throws Exception {
        ClassLoader demo = Fixtures.fixture("demo");
        Map<String, String> env = env(file());
        // Unauthenticated HTTP requests share one owner that is the same in every process.
        ProtocolCore.Connection http = new ProtocolCore.Connection("http", "http:anonymous");
        Fixtures.Server first = new Fixtures.Server(demo, env);
        String finished = startTask(first, http, 50);
        long end = System.currentTimeMillis() + 10_000;
        while (!get(first, http, finished).path("status").asText().equals("completed")) {
            if (System.currentTimeMillis() > end) throw new AssertionError("task did not complete");
            Thread.sleep(50);
        }
        String unfinished = startTask(first, http, 30_000);

        Fixtures.Server second = new Fixtures.Server(demo, env);
        JsonNode completed = get(second, http, finished);
        assertEquals("completed", completed.path("status").asText());
        assertTrue(completed.path("result").path("structuredContent").has("steps"), completed.toString());
        JsonNode failed = get(second, http, unfinished);
        assertEquals("failed", failed.path("status").asText());
        assertEquals("OUTCOME_UNKNOWN", failed.path("error").path("data").path("code").asText(), failed.toString());
        // The old process finishing later never overwrites the recorded outcome.
        first.runtime.tasks().complete(unfinished, Fixtures.JSON.createObjectNode().put("isError", false));
        assertEquals("failed", get(second, http, unfinished).path("status").asText());
        first.runtime.tasks().close();
        second.runtime.tasks().close();
    }

    @Test
    void anUnknownStoreRefusesStartup() throws Exception {
        Map<String, String> env = new java.util.HashMap<>(env(file()));
        env.put("TOOLGATE_TASK_STORE", "disk");
        StartupRefused e = assertThrows(StartupRefused.class, () -> new Fixtures.Server(Fixtures.fixture("demo"), env));
        assertTrue(e.getMessage().contains("TOOLGATE_TASK_STORE must be memory or sqlite"), e.getMessage());
        env.put("TOOLGATE_TASK_STORE", "sqlite");
        env.put("TOOLGATE_STATE_DB", Files.createTempDirectory("toolgate-not-a-file").toString());
        e = assertThrows(StartupRefused.class, () -> new Fixtures.Server(Fixtures.fixture("demo"), env));
        assertTrue(e.getMessage().contains("the task store"), e.getMessage());
    }

    @Test
    void aStoreThatCannotCreateTheTaskRunsNothing() throws Exception {
        TaskStore memory = new MemoryTaskStore();
        TaskStore broken = new TaskStore() {
            public void create(TaskRecord task) { throw new IllegalStateException("task store unavailable: disk I/O error"); }
            public Optional<TaskRecord> get(String taskId, String owner) { return memory.get(taskId, owner); }
            public Optional<TaskRecord> transition(String taskId, Consumer<TaskRecord> change) { return memory.transition(taskId, change); }
            public Optional<TaskRecord> submitInput(String taskId, String owner, ObjectNode responses) { return memory.submitInput(taskId, owner, responses); }
            public Optional<TaskRecord> requestCancel(String taskId, String owner) { return memory.requestCancel(taskId, owner); }
            public List<TaskRecord> purgeExpired(Instant now) { return memory.purgeExpired(now); }
            public List<TaskRecord> active() { return memory.active(); }
        };
        Fixtures.Server server = new Fixtures.Server(Fixtures.fixture("demo"), Map.of("TOOLGATE_MODE", "development"), new Tasks(broken, 60_000, 100));
        JsonNode result = server.callTask("demo.demo.report", "{\"ms\":10}").path("result");
        assertTrue(result.path("isError").asBoolean(), result.toString());
        JsonNode error = result.path("structuredContent").path("error");
        assertEquals("TASK_STORE_UNAVAILABLE", error.path("code").asText());
        assertTrue(error.path("retryable").asBoolean());
        server.runtime.tasks().close();
    }
}
