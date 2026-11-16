// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param engine canonical engine value
 * @param image canonical image value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackRuntime(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "engine", required = true) String engine,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "image", required = true) String image
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param engine canonical engine value
     * @param image canonical image value
     */
    public WorkflowStackRuntime {
        java.util.Objects.requireNonNull(engine, "engine");
        if (engine != null && !java.util.Objects.equals(engine, "LANGGRAPH")) throw new IllegalArgumentException("engine must be LANGGRAPH");
        java.util.Objects.requireNonNull(image, "image");
    }
}
