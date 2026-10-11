// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool Host to Gateway.
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param outcome canonical outcome value
 * @param result canonical result value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ResultMessage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = false) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) InvocationOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) ResultFrame result
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param outcome canonical outcome value
     * @param result canonical result value
     */
    public ResultMessage {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(outcome, "outcome");
    }
}
