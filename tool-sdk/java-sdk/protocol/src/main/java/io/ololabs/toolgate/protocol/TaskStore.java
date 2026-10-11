// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The task store port of adapter A2 (protocol-layer.md §3). Memory in standalone mode here; Control in governed
 * mode. Lookups by owner return empty for another owner exactly as for an unknown id.
 */
public interface TaskStore {
    /** Stores a new task; it exists before its CreateTaskResult is sent. */
    void create(TaskRecord task);

    /** The task, if it exists and belongs to {@code owner}. */
    Optional<TaskRecord> get(String taskId, String owner);

    /** Applies {@code change} atomically; returns the task after it, or empty if the task is gone. */
    Optional<TaskRecord> transition(String taskId, Consumer<TaskRecord> change);

    /**
     * Records answers for pending input keys and removes those keys from {@code inputRequests}; unknown keys are
     * ignored. Returns the task after it, or empty if it does not exist for {@code owner}.
     */
    Optional<TaskRecord> submitInput(String taskId, String owner, ObjectNode responses);

    /** Marks a non-terminal task cancel-requested. Returns the task, or empty if it does not exist for {@code owner}. */
    Optional<TaskRecord> requestCancel(String taskId, String owner);

    /** Removes the tasks whose retention ended by {@code now} and returns them. */
    List<TaskRecord> purgeExpired(Instant now);

    /** Every stored task, for the abandonment sweep. */
    List<TaskRecord> all();
}
