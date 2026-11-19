// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Ordered durable progress for one tenant/run; cursor advances only through returned events.
 *
 * @param items canonical items value
 * @param nextCursor canonical nextCursor value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunProgressPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<WorkflowRunProgressEvent> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextCursor", required = true) Long nextCursor
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param nextCursor canonical nextCursor value
     */
    public WorkflowRunProgressPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
        java.util.Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
