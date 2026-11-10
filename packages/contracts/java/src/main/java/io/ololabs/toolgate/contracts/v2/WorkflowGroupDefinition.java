// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowGroupDefinition; eligibility and configuration confer no runtime authority.
 *
 * @param id canonical id value
 * @param displayName canonical displayName value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGroupDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "displayName", required = true) String displayName
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param displayName canonical displayName value
     */
    public WorkflowGroupDefinition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(displayName, "displayName");
    }
}
