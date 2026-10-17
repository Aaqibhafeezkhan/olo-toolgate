// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Task fields added to a Control invocation when a task record exists (platform-requirements.md §12.1).
 *
 * @param taskId canonical taskId value
 * @param state canonical state value
 * @param status canonical status value
 * @param ttlMs canonical ttlMs value
 * @param pollIntervalMs canonical pollIntervalMs value
 * @param statusMessage canonical statusMessage value
 * @param progress canonical progress value
 * @param inputDeadlineUnixMs canonical inputDeadlineUnixMs value
 * @param cancelIfAbandoned canonical cancelIfAbandoned value
 * @param transitions canonical transitions value
 * @param packageDigest canonical packageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InvocationTask(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "taskId", required = true) String taskId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) TaskState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) McpTaskStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ttlMs", required = true) Long ttlMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pollIntervalMs", required = true) Long pollIntervalMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "statusMessage", required = false) String statusMessage,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "progress", required = false) TaskProgress progress,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputDeadlineUnixMs", required = false) Long inputDeadlineUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cancelIfAbandoned", required = true) Boolean cancelIfAbandoned,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "transitions", required = true) java.util.List<TaskTransition> transitions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param taskId canonical taskId value
     * @param state canonical state value
     * @param status canonical status value
     * @param ttlMs canonical ttlMs value
     * @param pollIntervalMs canonical pollIntervalMs value
     * @param statusMessage canonical statusMessage value
     * @param progress canonical progress value
     * @param inputDeadlineUnixMs canonical inputDeadlineUnixMs value
     * @param cancelIfAbandoned canonical cancelIfAbandoned value
     * @param transitions canonical transitions value
     * @param packageDigest canonical packageDigest value
     */
    public InvocationTask {
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(ttlMs, "ttlMs");
        java.util.Objects.requireNonNull(pollIntervalMs, "pollIntervalMs");
        java.util.Objects.requireNonNull(cancelIfAbandoned, "cancelIfAbandoned");
        java.util.Objects.requireNonNull(transitions, "transitions");
        transitions = transitions == null ? null : java.util.List.copyOf(transitions);
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
    }
}
