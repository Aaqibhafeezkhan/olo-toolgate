// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A task as Control returns it to the Gateway for tasks/get (invocation-state-v2.md §7). result is present only in a terminal state.
 *
 * @param invocationId canonical invocationId value
 * @param task canonical task value
 * @param result canonical result value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskView(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "task", required = true) InvocationTask task,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) ResultFrame result
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param task canonical task value
     * @param result canonical result value
     */
    public TaskView {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(task, "task");
    }
}
