// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** An in-process task store. Tasks do not survive the process, which is the standalone contract for memory. */
public final class MemoryTaskStore implements TaskStore {
    private final Map<String, TaskRecord> tasks = new HashMap<>();

    @Override
    public synchronized void create(TaskRecord task) {
        if (tasks.putIfAbsent(task.taskId, task.copy()) != null) throw new IllegalStateException("duplicate task id");
    }

    @Override
    public synchronized Optional<TaskRecord> get(String taskId, String owner) {
        return owned(taskId, owner).map(TaskRecord::copy);
    }

    @Override
    public synchronized Optional<TaskRecord> transition(String taskId, Consumer<TaskRecord> change) {
        TaskRecord t = tasks.get(taskId);
        if (t == null) return Optional.empty();
        change.accept(t);
        return Optional.of(t.copy());
    }

    @Override
    public synchronized Optional<TaskRecord> submitInput(String taskId, String owner, ObjectNode responses) {
        Optional<TaskRecord> found = owned(taskId, owner);
        found.ifPresent(t -> {
            Iterator<Map.Entry<String, JsonNode>> answers = responses.properties().iterator();
            while (answers.hasNext()) {
                Map.Entry<String, JsonNode> a = answers.next();
                if (t.inputRequests.remove(a.getKey()) != null) t.inputResponses.set(a.getKey(), a.getValue());
            }
        });
        return found.map(TaskRecord::copy);
    }

    @Override
    public synchronized Optional<TaskRecord> requestCancel(String taskId, String owner) {
        Optional<TaskRecord> found = owned(taskId, owner);
        found.ifPresent(t -> {
            if (!t.status.terminal()) t.cancelRequested = true;
        });
        return found.map(TaskRecord::copy);
    }

    @Override
    public synchronized List<TaskRecord> purgeExpired(Instant now) {
        List<TaskRecord> purged = new ArrayList<>();
        Iterator<TaskRecord> it = tasks.values().iterator();
        while (it.hasNext()) {
            TaskRecord t = it.next();
            if (!t.expiresAt.isAfter(now)) {
                purged.add(t.copy());
                it.remove();
            }
        }
        return purged;
    }

    @Override
    public synchronized List<TaskRecord> all() {
        List<TaskRecord> copies = new ArrayList<>();
        for (TaskRecord t : tasks.values()) copies.add(t.copy());
        return copies;
    }

    private Optional<TaskRecord> owned(String taskId, String owner) {
        TaskRecord t = tasks.get(taskId);
        return t != null && t.owner.equals(owner) ? Optional.of(t) : Optional.empty();
    }
}
