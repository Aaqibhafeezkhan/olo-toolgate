// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param folder canonical folder value
 * @param extension canonical extension value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackTriggerConfig(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "folder", required = true) String folder,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "extension", required = true) String extension
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param folder canonical folder value
     * @param extension canonical extension value
     */
    public WorkflowStackTriggerConfig {
        java.util.Objects.requireNonNull(folder, "folder");
        if (folder != null && !java.util.Objects.equals(folder, "inbox")) throw new IllegalArgumentException("folder must be inbox");
        java.util.Objects.requireNonNull(extension, "extension");
        if (extension != null && !java.util.Objects.equals(extension, ".txt")) throw new IllegalArgumentException("extension must be .txt");
    }
}
