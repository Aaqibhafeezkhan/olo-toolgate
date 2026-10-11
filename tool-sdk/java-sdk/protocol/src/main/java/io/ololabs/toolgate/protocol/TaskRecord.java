// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * One task as a {@link TaskStore} keeps it. Stores hand out copies; a change goes through
 * {@link TaskStore#transition}.
 */
public final class TaskRecord {
    public String taskId;
    /** The identity (or connection) that created it; every other owner gets not-found (plan.md §12.6). */
    public String owner;
    public TaskStatus status = TaskStatus.WORKING;
    public String statusMessage;
    public Instant createdAt;
    public Instant lastUpdatedAt;
    public long ttlMs;
    public long pollIntervalMs;
    public Instant expiresAt;
    public Instant lastPolledAt;
    public boolean cancelIfAbandoned;
    public boolean cancelRequested;
    /** Pending input requests by key, each {@code {method, params}}. */
    public ObjectNode inputRequests = JsonNodeFactory.instance.objectNode();
    /** Answers received for pending keys and not yet taken by the tool. */
    public ObjectNode inputResponses = JsonNodeFactory.instance.objectNode();
    /** The tool result, when completed. */
    public ObjectNode result;
    /** The JSON-RPC error {@code {code, message, data?}}, when failed. */
    public ObjectNode error;

    public TaskRecord copy() {
        TaskRecord c = new TaskRecord();
        c.taskId = taskId;
        c.owner = owner;
        c.status = status;
        c.statusMessage = statusMessage;
        c.createdAt = createdAt;
        c.lastUpdatedAt = lastUpdatedAt;
        c.ttlMs = ttlMs;
        c.pollIntervalMs = pollIntervalMs;
        c.expiresAt = expiresAt;
        c.lastPolledAt = lastPolledAt;
        c.cancelIfAbandoned = cancelIfAbandoned;
        c.cancelRequested = cancelRequested;
        c.inputRequests = inputRequests.deepCopy();
        c.inputResponses = inputResponses.deepCopy();
        c.result = result == null ? null : result.deepCopy();
        c.error = error == null ? null : error.deepCopy();
        return c;
    }
}
