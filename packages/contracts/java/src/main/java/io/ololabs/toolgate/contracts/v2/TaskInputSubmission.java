// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** tasks/update input, relayed by the Gateway to the waiting tool (invocation-state-v2.md §7).
 *
 * @param inputResponses canonical inputResponses value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskInputSubmission(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputResponses", required = true) com.fasterxml.jackson.databind.JsonNode inputResponses
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param inputResponses canonical inputResponses value
     */
    public TaskInputSubmission {
        java.util.Objects.requireNonNull(inputResponses, "inputResponses");
    }
}
