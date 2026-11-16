// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Bounded finish model proposal; never execution authority.
 *
 * @param kind canonical kind value
 * @param result canonical result value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowFinishDecision(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = true) com.fasterxml.jackson.databind.JsonNode result
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param result canonical result value
     */
    public WorkflowFinishDecision {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "finish")) throw new IllegalArgumentException("kind must be finish");
        java.util.Objects.requireNonNull(result, "result");
    }
}
