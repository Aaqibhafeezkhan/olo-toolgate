// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.ProtocolError;
import io.ololabs.toolgate.protocol.TaskRecord;
import io.ololabs.toolgate.protocol.TaskStatus;
import io.ololabs.toolgate.protocol.TaskStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The SQLite task store of standalone mode (runtime-modes.md, protocol-layer.md §3): tasks and their results survive a
 * restart. A task that had not ended when the earlier process stopped can no longer report, so opening the store ends
 * it {@code failed} with {@code OUTCOME_UNKNOWN}. One runtime process owns a state file at a time.
 */
final class SqliteTaskStore implements TaskStore, AutoCloseable {
    static final String INTERRUPTED = "The runtime stopped before this task finished, so its effect may have happened";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection db;

    private SqliteTaskStore(Connection db) {
        this.db = db;
    }

    static SqliteTaskStore open(Path file) throws SQLException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SQLException("cannot create " + file.getParent() + ": " + e.getMessage(), e);
        }
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        SqliteTaskStore store = new SqliteTaskStore(db);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS toolgate_tasks (task_id TEXT PRIMARY KEY, owner TEXT NOT NULL,"
                    + " terminal INTEGER NOT NULL, expires_at INTEGER NOT NULL, record TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS toolgate_tasks_expiry ON toolgate_tasks (expires_at)");
            store.endInterrupted();
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e instanceof SQLException q ? q : new SQLException(e);
        }
        return store;
    }

    /** Ends every task an earlier process left running or waiting for input. */
    private void endInterrupted() throws SQLException {
        Instant now = Instant.now();
        for (TaskRecord t : select("SELECT record FROM toolgate_tasks WHERE terminal = 0")) {
            t.status = TaskStatus.FAILED;
            t.lastUpdatedAt = now;
            t.inputRequests.removeAll();
            t.inputResponses.removeAll();
            t.error = JSON.createObjectNode().put("code", ProtocolError.INTERNAL_ERROR).put("message", INTERRUPTED);
            t.error.putObject("data").put("code", StandaloneRuntime.OUTCOME_UNKNOWN).put("message", INTERRUPTED).put("retryable", false);
            write(t);
        }
    }

    @Override
    public synchronized void create(TaskRecord task) {
        try (PreparedStatement i = db.prepareStatement(
                "INSERT INTO toolgate_tasks (task_id, owner, terminal, expires_at, record) VALUES (?, ?, ?, ?, ?)")) {
            i.setString(1, task.taskId);
            i.setString(2, task.owner);
            i.setInt(3, task.status.terminal() ? 1 : 0);
            i.setLong(4, task.expiresAt.toEpochMilli());
            i.setString(5, encode(task));
            i.executeUpdate();
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public synchronized Optional<TaskRecord> get(String taskId, String owner) {
        return find(taskId).filter(t -> t.owner.equals(owner));
    }

    @Override
    public synchronized Optional<TaskRecord> transition(String taskId, Consumer<TaskRecord> change) {
        return update(taskId, null, change);
    }

    @Override
    public synchronized Optional<TaskRecord> submitInput(String taskId, String owner, ObjectNode responses) {
        return update(taskId, owner, t -> {
            Iterator<Map.Entry<String, JsonNode>> answers = responses.properties().iterator();
            while (answers.hasNext()) {
                Map.Entry<String, JsonNode> a = answers.next();
                if (t.inputRequests.remove(a.getKey()) != null) t.inputResponses.set(a.getKey(), a.getValue());
            }
        });
    }

    @Override
    public synchronized Optional<TaskRecord> requestCancel(String taskId, String owner) {
        return update(taskId, owner, t -> {
            if (!t.status.terminal()) t.cancelRequested = true;
        });
    }

    @Override
    public synchronized List<TaskRecord> purgeExpired(Instant now) {
        try {
            db.setAutoCommit(false);
            try {
                List<TaskRecord> purged = new ArrayList<>();
                try (PreparedStatement q = db.prepareStatement("SELECT record FROM toolgate_tasks WHERE expires_at <= ?")) {
                    q.setLong(1, now.toEpochMilli());
                    purged.addAll(read(q));
                }
                try (PreparedStatement d = db.prepareStatement("DELETE FROM toolgate_tasks WHERE expires_at <= ?")) {
                    d.setLong(1, now.toEpochMilli());
                    d.executeUpdate();
                }
                db.commit();
                return purged;
            } catch (SQLException | RuntimeException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public synchronized List<TaskRecord> active() {
        try {
            return select("SELECT record FROM toolgate_tasks WHERE terminal = 0");
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            // closing on shutdown
        }
    }

    private Optional<TaskRecord> find(String taskId) {
        try (PreparedStatement q = db.prepareStatement("SELECT record FROM toolgate_tasks WHERE task_id = ?")) {
            q.setString(1, taskId);
            List<TaskRecord> found = read(q);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    /** Reads, changes and writes the task in one transaction; {@code owner} null skips the ownership check. */
    private Optional<TaskRecord> update(String taskId, String owner, Consumer<TaskRecord> change) {
        try {
            db.setAutoCommit(false);
            try {
                Optional<TaskRecord> found = find(taskId).filter(t -> owner == null || t.owner.equals(owner));
                if (found.isPresent()) {
                    change.accept(found.get());
                    write(found.get());
                }
                db.commit();
                return found.map(TaskRecord::copy);
            } catch (SQLException | RuntimeException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private void write(TaskRecord t) throws SQLException {
        try (PreparedStatement u = db.prepareStatement("UPDATE toolgate_tasks SET terminal = ?, expires_at = ?, record = ? WHERE task_id = ?")) {
            u.setInt(1, t.status.terminal() ? 1 : 0);
            u.setLong(2, t.expiresAt.toEpochMilli());
            u.setString(3, encode(t));
            u.setString(4, t.taskId);
            u.executeUpdate();
        }
    }

    private List<TaskRecord> select(String sql) throws SQLException {
        try (PreparedStatement q = db.prepareStatement(sql)) {
            return read(q);
        }
    }

    private static List<TaskRecord> read(PreparedStatement q) throws SQLException {
        List<TaskRecord> tasks = new ArrayList<>();
        try (ResultSet r = q.executeQuery()) {
            while (r.next()) tasks.add(decode(r.getString(1)));
        }
        return tasks;
    }

    private static String encode(TaskRecord t) {
        ObjectNode o = JSON.createObjectNode();
        o.put("taskId", t.taskId);
        o.put("owner", t.owner);
        o.put("status", t.status.name());
        if (t.statusMessage != null) o.put("statusMessage", t.statusMessage);
        o.put("createdAt", t.createdAt.toString());
        o.put("lastUpdatedAt", t.lastUpdatedAt.toString());
        o.put("ttlMs", t.ttlMs);
        o.put("pollIntervalMs", t.pollIntervalMs);
        o.put("expiresAt", t.expiresAt.toString());
        o.put("lastPolledAt", t.lastPolledAt.toString());
        o.put("cancelIfAbandoned", t.cancelIfAbandoned);
        o.put("cancelRequested", t.cancelRequested);
        o.set("inputRequests", t.inputRequests);
        o.set("inputResponses", t.inputResponses);
        if (t.result != null) o.set("result", t.result);
        if (t.error != null) o.set("error", t.error);
        return o.toString();
    }

    private static TaskRecord decode(String text) throws SQLException {
        JsonNode o;
        try {
            o = JSON.readTree(text);
        } catch (IOException e) {
            throw new SQLException("unreadable task record", e);
        }
        TaskRecord t = new TaskRecord();
        t.taskId = o.path("taskId").asText();
        t.owner = o.path("owner").asText();
        t.status = TaskStatus.valueOf(o.path("status").asText());
        t.statusMessage = o.hasNonNull("statusMessage") ? o.get("statusMessage").asText() : null;
        t.createdAt = Instant.parse(o.path("createdAt").asText());
        t.lastUpdatedAt = Instant.parse(o.path("lastUpdatedAt").asText());
        t.ttlMs = o.path("ttlMs").asLong();
        t.pollIntervalMs = o.path("pollIntervalMs").asLong();
        t.expiresAt = Instant.parse(o.path("expiresAt").asText());
        t.lastPolledAt = Instant.parse(o.path("lastPolledAt").asText());
        t.cancelIfAbandoned = o.path("cancelIfAbandoned").asBoolean();
        t.cancelRequested = o.path("cancelRequested").asBoolean();
        t.inputRequests = (ObjectNode) o.path("inputRequests");
        t.inputResponses = (ObjectNode) o.path("inputResponses");
        t.result = o.get("result") instanceof ObjectNode r ? r : null;
        t.error = o.get("error") instanceof ObjectNode e ? e : null;
        return t;
    }

    private static IllegalStateException unavailable(SQLException e) {
        return new IllegalStateException("task store unavailable: " + e.getMessage(), e);
    }
}
