// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param agentId canonical agentId value
 * @param inbox canonical inbox value
 * @param processed canonical processed value
 * @param output canonical output value
 * @param archive canonical archive value
 * @param projection canonical projection value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackFolderAgent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inbox", required = true) String inbox,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "processed", required = true) String processed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "output", required = true) String output,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "archive", required = true) Boolean archive,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "projection", required = true) String projection
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentId canonical agentId value
     * @param inbox canonical inbox value
     * @param processed canonical processed value
     * @param output canonical output value
     * @param archive canonical archive value
     * @param projection canonical projection value
     */
    public WorkflowStackFolderAgent {
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(inbox, "inbox");
        java.util.Objects.requireNonNull(processed, "processed");
        java.util.Objects.requireNonNull(output, "output");
        java.util.Objects.requireNonNull(archive, "archive");
        java.util.Objects.requireNonNull(projection, "projection");
    }
}
