// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Boolean branch destinations in the configured graph.
 *
 * @param true_ canonical true value
 * @param false_ canonical false value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowJsonBranchRoutes(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "true", required = true) String true_,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "false", required = true) String false_
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param true_ canonical true value
     * @param false_ canonical false value
     */
    public WorkflowJsonBranchRoutes {
        java.util.Objects.requireNonNull(true_, "true");
        java.util.Objects.requireNonNull(false_, "false");
    }
}
