// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param deploymentId canonical deploymentId value
 * @param engine canonical engine value
 * @param image canonical image value
 * @param modelFile canonical modelFile value
 * @param contextSize canonical contextSize value
 * @param threads canonical threads value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackModelEngine(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentId", required = true) String deploymentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "engine", required = true) String engine,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "image", required = true) String image,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelFile", required = true) String modelFile,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contextSize", required = true) Long contextSize,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "threads", required = true) Long threads
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deploymentId canonical deploymentId value
     * @param engine canonical engine value
     * @param image canonical image value
     * @param modelFile canonical modelFile value
     * @param contextSize canonical contextSize value
     * @param threads canonical threads value
     */
    public WorkflowStackModelEngine {
        java.util.Objects.requireNonNull(deploymentId, "deploymentId");
        java.util.Objects.requireNonNull(engine, "engine");
        if (engine != null && !java.util.Objects.equals(engine, "LLAMA_CPP")) throw new IllegalArgumentException("engine must be LLAMA_CPP");
        java.util.Objects.requireNonNull(image, "image");
        java.util.Objects.requireNonNull(modelFile, "modelFile");
        java.util.Objects.requireNonNull(contextSize, "contextSize");
        java.util.Objects.requireNonNull(threads, "threads");
    }
}
